package mtm.testkit.serverdriver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtm.testkit.DataJson;
import mtm.testkit.Http;
import mtm.testkit.LogCapture;
import mtm.testkit.TestKit;
import mtr.block.BlockNode;
import mtr.data.Rail;
import mtr.data.RailwayData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server side of the driver: runs commands as the console, builds fixture networks and reads the railway data.
 */
public final class ServerDriver {

	private static Http http;

	private ServerDriver() {
	}

	public static synchronized void start(MinecraftServer server) {
		stop();
		final Map<String, Http.Handler> routes = new LinkedHashMap<>();
		routes.put("/ping", (body, query) -> Http.onThread(server, () -> ping(server)));
		routes.put("/command", (body, query) -> Http.onThread(server, () -> command(server, body.get("command").getAsString())));
		routes.put("/snapshot", (body, query) -> Http.onThread(server, () -> snapshot(level(server, query.get("dimension")))));
		routes.put("/fixture", (body, query) -> Http.onThread(server, () -> Fixture.build(level(server, null), body.has("name") ? body.get("name").getAsString() : "basic")));
		routes.put("/node", (body, query) -> Http.onThread(server, () -> node(level(server, null), new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt()))));
		routes.put("/log", (body, query) -> LogCapture.since(query.containsKey("since") ? Long.parseLong(query.get("since")) : 0));
		routes.put("/stop", (body, query) -> {
			server.execute(() -> server.halt(false));
			return null;
		});
		try {
			http = Http.start("server", server.getServerDirectory().toPath(), routes);
		} catch (Exception e) {
			TestKit.LOGGER.error("[MTM_TestKit] could not start the server driver", e);
		}
	}

	public static synchronized void stop() {
		if (http != null) {
			http.stop();
			http = null;
		}
	}

	private static ServerLevel level(MinecraftServer server, String dimension) {
		if (dimension == null || dimension.isEmpty()) {
			return server.overworld();
		}
		for (final ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().equals(new ResourceLocation(dimension))) {
				return level;
			}
		}
		throw new IllegalArgumentException("no dimension " + dimension);
	}

	private static JsonObject ping(MinecraftServer server) {
		final JsonObject result = new JsonObject();
		result.addProperty("side", "server");
		result.addProperty("tick", server.getTickCount());
		final JsonArray players = new JsonArray();
		for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("name", player.getGameProfile().getName());
			entry.addProperty("x", player.getX());
			entry.addProperty("y", player.getY());
			entry.addProperty("z", player.getZ());
			entry.addProperty("dimension", player.level().dimension().location().toString());
			players.add(entry);
		}
		result.add("players", players);
		return result;
	}

	/**
	 * Runs a command as the console with permission level 4 and returns what it printed.
	 */
	private static JsonObject command(MinecraftServer server, String command) {
		final List<String> output = new ArrayList<>();
		final CommandSource source = new CommandSource() {
			@Override
			public void sendSystemMessage(Component component) {
				output.add(component.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		final ServerLevel level = server.overworld();
		final CommandSourceStack stack = new CommandSourceStack(source, Vec3.atLowerCornerOf(level.getSharedSpawnPos()), Vec2.ZERO, level, 4, "MTM_TestKit", Component.literal("MTM_TestKit"), server, null);
		final int result = server.getCommands().performPrefixedCommand(stack, command.startsWith("/") ? command.substring(1) : command);
		final JsonObject response = new JsonObject();
		response.addProperty("result", result);
		final JsonArray lines = new JsonArray();
		output.forEach(lines::add);
		response.add("output", lines);
		return response;
	}

	/**
	 * A rail node: its block, angle, whether it is a key node, and each rail from it (type, facing at both ends).
	 */
	@SuppressWarnings("unchecked")
	private static JsonObject node(ServerLevel level, BlockPos pos) {
		final RailwayData railwayData = RailwayData.getInstance(level);
		final BlockState state = level.getBlockState(pos);
		final JsonObject result = new JsonObject();
		result.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		if (state.getBlock() instanceof BlockNode) {
			result.addProperty("angle", BlockNode.getAngle(level, pos, state));
		}
		result.addProperty("keyNode", railwayData != null && railwayData.keyNodes.contains(pos));
		final JsonArray rails = new JsonArray();
		if (railwayData != null) {
			try {
				final Field field = RailwayData.class.getDeclaredField("rails");
				field.setAccessible(true);
				final Map<BlockPos, Map<BlockPos, Rail>> allRails = (Map<BlockPos, Map<BlockPos, Rail>>) field.get(railwayData);
				final Map<BlockPos, Rail> connections = allRails.get(pos);
				if (connections != null) {
					connections.entrySet().stream().sorted(Comparator.comparingLong(entry -> entry.getKey().asLong())).forEach(entry -> {
						final JsonObject rail = new JsonObject();
						final JsonArray to = new JsonArray();
						to.add(entry.getKey().getX());
						to.add(entry.getKey().getY());
						to.add(entry.getKey().getZ());
						rail.add("to", to);
						rail.addProperty("type", entry.getValue().railType.toString());
						rail.addProperty("facingStart", entry.getValue().facingStart.angleDegrees);
						rail.addProperty("facingEnd", entry.getValue().facingEnd.angleDegrees);
						rails.add(rail);
					});
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}
		result.add("rails", rails);
		return result;
	}

	private static JsonObject snapshot(ServerLevel level) {
		final RailwayData railwayData = RailwayData.getInstance(level);
		if (railwayData == null) {
			throw new IllegalStateException("no railway data in " + level.dimension().location());
		}
		final JsonObject result = DataJson.snapshot(railwayData.stations, railwayData.platforms, railwayData.sidings, railwayData.routes, railwayData.depots);
		result.addProperty("dimension", level.dimension().location().toString());
		return result;
	}
}
