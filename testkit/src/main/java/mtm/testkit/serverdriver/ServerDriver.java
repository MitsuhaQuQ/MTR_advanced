package mtm.testkit.serverdriver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtm.testkit.DataJson;
import mtm.testkit.Http;
import mtm.testkit.LogCapture;
import mtm.testkit.TestKit;
import mtr.block.BlockNode;
import mtr.data.Depot;
import mtr.data.Rail;
import mtr.data.Siding;
import mtr.data.TrainServer;
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
import java.util.Set;
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
	/**
	 * Timetable sensors the fixture placed, and each time one was powered (index, epoch millis), recorded every tick, as a
	 * sample every second can miss a pulse.
	 */
	private static final List<BlockPos> watchedSensors = new ArrayList<>();
	private static final List<long[]> sensorPulses = new ArrayList<>();
	private static int[] lastPowered = new int[0];

	public static synchronized void watchSensor(BlockPos pos) {
		watchedSensors.add(pos.immutable());
		lastPowered = java.util.Arrays.copyOf(lastPowered, watchedSensors.size());
	}

	/**
	 * Called every server tick: the watched sensors that were powered since the last tick.
	 */
	public static synchronized void tickSensors(MinecraftServer server) {
		if (watchedSensors.isEmpty()) {
			return;
		}
		final ServerLevel level = server.overworld();
		for (int i = 0; i < watchedSensors.size(); i++) {
			final BlockState state = level.getBlockState(watchedSensors.get(i));
			final int powered = state.hasProperty(mtr.block.BlockTrainPoweredSensorBase.POWERED) ? state.getValue(mtr.block.BlockTrainPoweredSensorBase.POWERED) : 0;
			if (powered == 2 && lastPowered[i] != 2) {
				sensorPulses.add(new long[]{i, System.currentTimeMillis()});
			}
			lastPowered[i] = powered;
		}
	}

	private static synchronized JsonObject sensors() {
		final JsonObject result = new JsonObject();
		final JsonArray pulses = new JsonArray();
		sensorPulses.forEach(pulse -> {
			final JsonObject entry = new JsonObject();
			entry.addProperty("sensor", pulse[0]);
			entry.addProperty("millis", pulse[1]);
			pulses.add(entry);
		});
		result.add("pulses", pulses);
		return result;
	}

	private ServerDriver() {
	}

	public static synchronized void start(MinecraftServer server) {
		stop();
		final Map<String, Http.Handler> routes = new LinkedHashMap<>();
		routes.put("/ping", (body, query) -> Http.onThread(server, () -> ping(server)));
		routes.put("/command", (body, query) -> Http.onThread(server, () -> command(server, body.get("command").getAsString())));
		routes.put("/snapshot", (body, query) -> Http.onThread(server, () -> snapshot(level(server, query.get("dimension")))));
		routes.put("/fixture", (body, query) -> Http.onThread(server, () -> {
			final String name = body.has("name") ? body.get("name").getAsString() : "basic";
			if ("sidings".equals(name)) {
				return Sidings.build(level(server, null), body);
			}
			return "lines".equals(name) ? Lines.build(level(server, null), body) : Fixture.build(level(server, null), name);
		}));
		routes.put("/node", (body, query) -> Http.onThread(server, () -> node(level(server, null), new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt()))));
		routes.put("/trains", (body, query) -> Http.onThread(server, () -> trains(level(server, query.get("dimension")))));
		routes.put("/clock", (body, query) -> clock());
		routes.put("/sensors", (body, query) -> sensors());
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

	/**
	 * Every train of every siding: where its head is, its speed, whether it is out on its route, its doors, how far along
	 * its path it is. Sampled over time by the tests, to check trains leave, run, turn back and come back without jumping.
	 */
	@SuppressWarnings("unchecked")
	private static JsonObject trains(ServerLevel level) {
		final RailwayData railwayData = RailwayData.getInstance(level);
		final JsonObject result = new JsonObject();
		result.addProperty("millis", System.currentTimeMillis());
		result.addProperty("tick", level.getServer().getTickCount());
		final JsonArray array = new JsonArray();
		try {
			final Field trainsField = Siding.class.getDeclaredField("trains");
			trainsField.setAccessible(true);
			for (final Siding siding : railwayData.sidings) {
				final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
				for (final TrainServer train : (Set<TrainServer>) trainsField.get(siding)) {
					final JsonObject entry = new JsonObject();
					entry.addProperty("id", train.id);
					entry.addProperty("siding", siding.id);
					entry.addProperty("depot", depot == null ? 0 : depot.id);
					final Vec3 head = train.getTrackPosition(0, 0);
					if (head != null) {
						entry.addProperty("x", Math.round(head.x * 100) / 100.0);
						entry.addProperty("y", Math.round(head.y * 100) / 100.0);
						entry.addProperty("z", Math.round(head.z * 100) / 100.0);
					}
					entry.addProperty("speed", train.getSpeed());
					entry.addProperty("onRoute", train.isOnRoute());
					entry.addProperty("doors", train.getDoorValue());
					entry.addProperty("progress", train.getRailProgress());
					// The route and service it runs now, for telling services every 30 seconds apart
					entry.addProperty("route", String.valueOf(train.getService().getTripRouteId()));
					entry.addProperty("trip", train.getService().getTripMillis());
					array.add(entry);
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		result.add("trains", array);
		return result;
	}

	/** Now, in the time zone and operating day the timetables are written in */
	private static JsonObject clock() {
		final long now = System.currentTimeMillis();
		final java.time.ZonedDateTime time = java.time.Instant.ofEpochMilli(now).atZone(transitsim.plan.OperatingDay.getTimeZone());
		final JsonObject result = new JsonObject();
		result.addProperty("millis", now);
		result.addProperty("zone", transitsim.plan.OperatingDay.getTimeZone().getId());
		result.addProperty("time", String.format("%02d:%02d:%02d", time.getHour(), time.getMinute(), time.getSecond()));
		result.addProperty("dayStartHour", transitsim.plan.OperatingDay.getDayStartHour());
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
