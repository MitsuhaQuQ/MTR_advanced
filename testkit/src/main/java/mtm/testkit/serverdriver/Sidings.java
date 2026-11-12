package mtm.testkit.serverdriver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.Blocks;
import mtr.block.BlockNode;
import mtr.data.Depot;
import mtr.data.RailType;
import mtr.data.RailwayData;
import mtr.data.Siding;
import mtr.data.TransportMode;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Tuple;

import java.util.ArrayList;
import java.util.List;

/**
 * Parallel sidings for measuring how fast the client draws parked trains (user, 2026-11-13: FPS drops sharply with 10–20
 * sidings 4 blocks apart all showing trains). A yard is {@code count} sidings of {@code length} blocks running east from
 * x = 0, the first at {@code z} and each next {@code spacing} blocks further south, all in one depot without routes, so
 * each siding keeps the one train it makes standing where it is. Its trains are {@code trains} (custom IDs or built-in
 * types, given to the sidings in turn), on base type {@code baseType}. Several yards far enough apart (600 blocks) let one
 * world hold every case of a measurement, as the client is sent only the trains near the player.
 */
public final class Sidings {

	public static final int Y = Fixture.Y;

	private Sidings() {
	}

	/**
	 * Builds the yards in {@code body.yards} (or one yard from {@code body} itself); a yard's fields are {@code count}
	 * (default 20), {@code length} (150), {@code spacing} (4), {@code z} (where its first siding lies, 0), {@code trains}
	 * (["sp1900"]) and {@code baseType} ("train_24_2").
	 */
	public static JsonObject build(ServerLevel level, JsonObject body) {
		final RailwayData railwayData = RailwayData.getInstance(level);
		if (railwayData == null) {
			throw new IllegalStateException("no railway data");
		}
		if (!railwayData.sidings.isEmpty()) {
			throw new IllegalStateException("the world already has sidings; the fixture is built into a new world only");
		}
		final List<JsonObject> yards = new ArrayList<>();
		if (body.has("yards")) {
			body.getAsJsonArray("yards").forEach(element -> yards.add(element.getAsJsonObject()));
		} else {
			yards.add(body);
		}
		final JsonArray results = new JsonArray();
		for (final JsonObject yard : yards) {
			results.add(yard(level, railwayData, yard));
		}
		railwayData.dataCache.sync();
		final JsonObject result = new JsonObject();
		result.addProperty("y", Y);
		result.add("yards", results);
		return result;
	}

	private static JsonObject yard(ServerLevel level, RailwayData railwayData, JsonObject body) {
		final int count = body.has("count") ? body.get("count").getAsInt() : 20;
		final int length = body.has("length") ? body.get("length").getAsInt() : 150;
		final int spacing = body.has("spacing") ? body.get("spacing").getAsInt() : 4;
		final int z0 = body.has("z") ? body.get("z").getAsInt() : 0;
		final String baseType = body.has("baseType") ? body.get("baseType").getAsString() : "train_24_2";
		final List<String> trains = new ArrayList<>();
		if (body.has("trains")) {
			body.getAsJsonArray("trains").forEach(element -> trains.add(element.getAsString()));
		}
		if (trains.isEmpty()) {
			trains.add("sp1900");
		}
		final FakePlayer player = FakePlayer.get(level);

		for (int i = 0; i < count; i++) {
			final BlockPos start = new BlockPos(0, Y, z0 + i * spacing);
			final BlockPos end = new BlockPos(length, Y, z0 + i * spacing);
			level.setBlockAndUpdate(start, Blocks.RAIL_NODE.get().defaultBlockState().setValue(BlockNode.FACING, true));
			level.setBlockAndUpdate(end, Blocks.RAIL_NODE.get().defaultBlockState().setValue(BlockNode.FACING, true));
			Fixture.connectRail(level, railwayData, player, start, end, RailType.SIDING);
		}

		// Trains ["none"]: sidings without a depot and so without trains, and a curved rail beside them, to look at the
		// rails themselves
		final boolean railsOnly = trains.size() == 1 && "none".equals(trains.get(0));
		final JsonObject result = new JsonObject();
		if (railsOnly) {
			final int curveZ = z0 + count * spacing + 10;
			final BlockPos start = new BlockPos(0, Y, curveZ);
			final BlockPos end = new BlockPos(40, Y, curveZ + 40);
			level.setBlockAndUpdate(start, Blocks.RAIL_NODE.get().defaultBlockState().setValue(BlockNode.FACING, true));
			level.setBlockAndUpdate(end, Blocks.RAIL_NODE.get().defaultBlockState().setValue(BlockNode.FACING, false));
			try {
				Fixture.connectRail(level, railwayData, player, start, end, RailType.IRON);
				result.addProperty("curve", true);
			} catch (IllegalStateException e) {
				result.addProperty("curve", e.getMessage());
			}
			result.addProperty("z", z0);
			result.addProperty("count", count);
			return result;
		}

		final Depot depot = new Depot(TransportMode.TRAIN);
		depot.name = String.format("存车场%d|Siding Yard %d", z0, z0);
		depot.color = 0x546E7A;
		depot.corner1 = new Tuple<>(-5, z0 - 5);
		depot.corner2 = new Tuple<>(length + 5, z0 + Math.max(0, count - 1) * spacing + 5);
		railwayData.depots.add(depot);

		final JsonArray sidingIds = new JsonArray();
		for (int i = 0; i < count; i++) {
			final Siding siding = sidingAt(railwayData, z0 + i * spacing);
			siding.setTrainIdAndBaseType(trains.get(i % trains.size()), baseType, packet -> {
			});
			sidingIds.add(siding.id);
		}

		result.addProperty("z", z0);
		result.addProperty("count", count);
		result.addProperty("length", length);
		result.addProperty("spacing", spacing);
		result.add("trains", body.has("trains") ? body.getAsJsonArray("trains") : new JsonArray());
		result.addProperty("depot", depot.id);
		result.add("sidings", sidingIds);
		return result;
	}

	private static Siding sidingAt(RailwayData railwayData, int z) {
		// A siding's positions are its two end nodes
		final BlockPos pos = new BlockPos(0, Y, z);
		for (final Siding siding : railwayData.sidings) {
			if (siding.containsPos(pos)) {
				return siding;
			}
		}
		throw new IllegalStateException("no siding at z = " + z);
	}
}
