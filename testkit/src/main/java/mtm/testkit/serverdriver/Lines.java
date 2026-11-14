package mtm.testkit.serverdriver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.Blocks;
import mtr.block.BlockNode;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.SavedRailBase;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.data.TransportMode;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Six five-station double-track lines, one for each track layout the depot and turn-back rules have to handle (user,
 * 2026-11-12), built side by side in one world (300 blocks apart in z) by the rail connector's own steps:
 * <ol>
 * <li>front turn-back (a crossover before the terminus) and the depot behind the terminus</li>
 * <li>a turn-back track behind the terminus, the depot behind the turn-back track</li>
 * <li>a turn-back track behind the terminus, the depot branching off before it</li>
 * <li>front turn-back and a separate platform trains leave the depot from</li>
 * <li>a turn-back track at the middle station, the depot behind it</li>
 * <li>a turn-back track at the middle station, the depot joined to one main track only</li>
 * </ol>
 * and three more (user, 2026-11-12) for signal blocks and through services:
 * <ol start="7">
 * <li>a long section: three stations, the last 2000 blocks on, one rail between them that signal blocks cut up; services
 * every 30 seconds</li>
 * <li>through services end to start: route A 一站↔三站 and route B 三站↔五站 on one track, some of A's services running on
 * as B's; A's own turn back at 三站 on a turn-back track</li>
 * <li>through services over three routes: A 一站→三站 joins B (二站→四站) at its middle stop 三站, B runs on as C (四站→五站);
 * back C→B, and B leaves at 三站 for A; 三站's platforms are timetable boundaries</li>
 * </ol>
 * and one for 站后双折返 (user, 2026-11-13):
 * <ol start="10">
 * <li>two dead-end turn-back tracks behind 五站, side by side, each reached from both main tracks over the throat's two
 * crossovers; trains take them in turn. The depot joins the westbound track behind 五站 (a branch between the
 * crossovers and the tails, as a depot behind a tail would leave that tail no longer a dead end, which is how the game
 * finds the tails to choose from)</li>
 * </ol>
 * Every line: eastbound main track at z = 0, westbound at z = 8 (relative), stations 一站…五站 with a 60-block platform on
 * each main track, a turn-back track behind 一站. Routes 上行 (一站→五站, track 0) and 下行 (五站→一站, track 8); lines 5 and 6
 * also 短上行/短下行 (一站↔三站), turning back at the middle station. Depot 1–4 is at the 五站 end, so 五站 is the near
 * ("short") end of the line for its trains and 一站 the far ("long") end.
 */
public final class Lines {

	public static final int Y = Fixture.Y;
	public static final int SPACING = 300;
	/** Where each station's platforms start (x); the gap after 三站 is longer, to fit what lines 5 and 6 put there */
	private static final int[] STATION_X = {0, 200, 400, 640, 840};
	private static final int[] LONG_STATION_X = {0, 200, 2200};
	private static final int PLATFORM = 60;
	private static final String[] STATION_NAMES = {"一站|Station 1", "二站|Station 2", "三站|Station 3", "四站|Station 4", "五站|Station 5"};
	private static final String[] LAYOUT_NAMES = {
			"站前折返+站后车库", "站后折返线后接车厂", "站后折返线前分叉接车厂", "站前折返+单独出库站台", "中间站折返线+车厂", "中间站折返线+车厂只接单边",
			"长区间信号分段", "首尾直通", "中途接入与三段直通", "站后双折返", "首尾直通冲突进路示例"
	};
	private static final int[] COLORS = {0xE53935, 0x1E88E5, 0x43A047, 0x8E24AA, 0xFB8C00, 0x00897B, 0x6D4C41, 0x3949AB, 0xC0CA33, 0xD81B60, 0x546E7A};
	public static final int LAYOUTS = 11;

	private Lines() {
	}

	/**
	 * @param body {@code train} and {@code baseType}: the vehicle every siding of the six lines runs, for looking at one
	 *             kind of train in service (2026-11-13); by default each siding keeps the one it picks for its length
	 */
	public static JsonObject build(ServerLevel level, com.google.gson.JsonObject body) {
		final RailwayData railwayData = RailwayData.getInstance(level);
		if (railwayData == null) {
			throw new IllegalStateException("no railway data");
		}
		if (!railwayData.stations.isEmpty() || !railwayData.platforms.isEmpty()) {
			throw new IllegalStateException("the world already has railway data; the fixture is built into a new world only");
		}
		final JsonObject result = new JsonObject();
		result.addProperty("y", Y);
		final JsonArray lines = new JsonArray();
		for (int layout = 1; layout <= LAYOUTS; layout++) {
			lines.add(new Builder(level, railwayData, layout, (layout - 1) * SPACING).build());
		}
		result.add("lines", lines);
		if (body != null && body.has("train")) {
			final String train = body.get("train").getAsString();
			final String baseType = body.has("baseType") ? body.get("baseType").getAsString() : "train_24_2";
			railwayData.sidings.forEach(siding -> siding.setTrainIdAndBaseType(train, baseType, packet -> {
			}));
			result.addProperty("train", train);
		}
		railwayData.dataCache.sync();
		return result;
	}

	private static final class Builder {

		private final ServerLevel level;
		private final RailwayData railwayData;
		private final FakePlayer player;
		private final int layout;
		private final int z0;
		/** The nodes along each main track (by relative z), in x order, joined one to the next at the end */
		private final Map<Integer, TreeSet<Integer>> mains = new HashMap<>();
		/** Main track spans of another type than iron (platforms): "z x1 x2" → type */
		private final Map<String, RailType> mainTypes = new HashMap<>();
		private final Set<BlockPos> placed = new HashSet<>();
		private final JsonObject ids = new JsonObject();
		private final int[] stationX;

		private Builder(ServerLevel level, RailwayData railwayData, int layout, int z0) {
			this.level = level;
			this.railwayData = railwayData;
			this.layout = layout;
			this.z0 = z0;
			stationX = layout == 7 ? LONG_STATION_X : STATION_X;
			player = FakePlayer.get(level);
		}

		private JsonObject build() {
			// The main tracks with their platforms
			for (final int x : stationX) {
				for (final int z : new int[]{0, 8}) {
					mainNode(z, x);
					mainNode(z, x + PLATFORM);
					mainTypes.put(z + " " + x + " " + (x + PLATFORM), RailType.PLATFORM);
				}
			}
			final int east = stationX[stationX.length - 1] + PLATFORM;
			final int middle = STATION_X[2] + PLATFORM;

			// Behind 一站: westbound trains run on into a turn-back track and come back out onto the eastbound track
			mainNode(8, -40);
			rail(0, 0, -40, 8, RailType.IRON);
			rail(-40, 8, -100, 8, RailType.TURN_BACK);

			final List<int[]> depotBoxes = new ArrayList<>();
			switch (layout) {
				case 1:
					frontCrossover();
					// The depot behind the terminus, a siding at the end of each main track
					mainNode(0, east + 30);
					mainNode(8, east + 30);
					rail(east + 30, 0, east + 110, 0, RailType.SIDING);
					rail(east + 30, 8, east + 110, 8, RailType.SIDING);
					// Trains come back into the eastbound track's siding and leave from the westbound one's: a shunting neck
					// behind both joins them (2026-11-13, 车厂内调车)
					shuntingNeck(east + 110, 0, 8);
					depotBoxes.add(new int[]{east + 20, -6, east + 200, 14});
					break;
				case 2:
				case 7:
				case 9:
					rearTurnBack(east);
					depotBehindTurnBack(east, depotBoxes);
					break;
				case 8:
					// Each half turns back in front of a platform of its own at 三站 (user, 2026-11-14): trains come in the
					// line's way and only cross to the other track leaving, A from 三站's eastbound platform west, B from its
					// westbound one east
					rearTurnBack(east);
					depotBehindTurnBack(east, depotBoxes);
					middleFrontCrossovers();
					break;
				case 11:
					// Line 8 as it was: both halves turning back through one dead-end turn-back track east of 三站, B's trains
					// turning three times along A's way the other way. Kept for the depot's hint of conflicting routes and the
					// rule that a train goes into a dead end only with its way out (item A of MTR-待验证.md)
					rearTurnBack(east);
					depotBehindTurnBack(east, depotBoxes);
					middleTurnBack(middle, 50);
					break;
				case 10:
					doubleRearTurnBack(east, depotBoxes);
					break;
				case 3:
					// The depot branching off the eastbound track before the turn-back track
					mainNode(0, east + 20);
					mainNode(0, east + 60);
					rail(east, 8, east + 60, 0, RailType.IRON);
					rail(east + 60, 0, east + 120, 0, RailType.TURN_BACK);
					rail(east + 20, 0, east + 60, -8, RailType.IRON);
					rail(east + 60, -8, east + 140, -8, RailType.SIDING);
					depotBoxes.add(new int[]{east + 55, -14, east + 150, -4});
					break;
				case 4:
					frontCrossover();
					// A third platform at 五站, outside the westbound track, that trains leave the depot from; it joins the
					// westbound track west of the station
					mainNode(8, STATION_X[4] - 40);
					rail(STATION_X[4], 16, east, 16, RailType.PLATFORM);
					rail(STATION_X[4], 16, STATION_X[4] - 40, 8, RailType.IRON);
					rail(east, 16, east + 30, 16, RailType.IRON);
					rail(east + 30, 16, east + 110, 16, RailType.SIDING);
					// Trains back from service run on from the eastbound platform into a siding of their own
					mainNode(0, east + 30);
					rail(east + 30, 0, east + 110, 0, RailType.SIDING);
					// A shunting neck behind both sidings takes trains back in from service to the one they leave from
					shuntingNeck(east + 110, 0, 16);
					depotBoxes.add(new int[]{east + 20, -6, east + 200, 22});
					break;
				case 5:
				case 6:
					rearTurnBack(east);
					// Line 6's dead end is shorter than its 75-block trains, which turn standing over the switch (0.79.4 says so in
					// the log). At 90 (0.80.0, with dead ends taken only with the way out) line 6 ran into another deadlock, at 一站,
					// with the rule or without it (MTR-待验证.md E), so it stays at 50 until that is found
					middleTurnBack(middle, 50);
					if (layout == 5) {
						// The depot behind the turn-back track, between the main tracks
						rail(middle + 120, 4, middle + 130, 4, RailType.IRON);
						rail(middle + 130, 4, middle + 190, 4, RailType.SIDING);
						depotBoxes.add(new int[]{middle + 125, 2, middle + 200, 6});
					} else {
						// The depot joined to the westbound track only
						mainNode(8, middle + 150);
						rail(middle + 150, 8, middle + 190, 16, RailType.IRON);
						rail(middle + 190, 16, middle + 270, 16, RailType.SIDING);
						depotBoxes.add(new int[]{middle + 185, 13, middle + 280, 19});
					}
					break;
				default:
					throw new IllegalArgumentException("no layout " + layout);
			}

			// The main tracks, node to node
			mains.forEach((z, xs) -> {
				Integer previous = null;
				for (final int x : xs) {
					if (previous != null) {
						rail(previous, z, x, z, mainTypes.getOrDefault(z + " " + previous + " " + x, RailType.IRON));
					}
					previous = x;
				}
			});

			// Stations, routes, depot
			final String prefix = layout + "号线";
			final int count = stationX.length;
			final Station[] stations = new Station[count];
			final Platform[] up = new Platform[count];
			final Platform[] down = new Platform[count];
			for (int i = 0; i < count; i++) {
				final int extra = layout == 4 && i == 4 ? 18 : 12;
				stations[i] = station(prefix + STATION_NAMES[i], COLORS[layout - 1], stationX[i] - 5, -4, stationX[i] + PLATFORM + 5, extra);
				up[i] = savedRail(railwayData.platforms, stationX[i], 0);
				down[i] = savedRail(railwayData.platforms, stationX[i], 8);
			}
			final List<Route> routes = new ArrayList<>();
			final Platform[] downReversed = reversed(down);
			if (layout == 8 || layout == 11) {
				// A 一站↔三站, B 三站↔五站
				routes.add(route(prefix + "A上行|Line " + layout + " A Up", up, 0, 2));
				routes.add(route(prefix + "A下行|Line " + layout + " A Down", downReversed, 2, 4));
				routes.add(route(prefix + "B上行|Line " + layout + " B Up", up, 2, 4));
				routes.add(route(prefix + "B下行|Line " + layout + " B Down", downReversed, 0, 2));
			} else if (layout == 9) {
				// A 一站→三站, B 二站→四站, C 四站→五站, and back; 三站 a timetable boundary both ways
				up[2].timetableBoundary = true;
				down[2].timetableBoundary = true;
				routes.add(route(prefix + "A上行|Line " + layout + " A Up", up, 0, 2));
				routes.add(route(prefix + "B上行|Line " + layout + " B Up", up, 1, 3));
				routes.add(route(prefix + "C上行|Line " + layout + " C Up", up, 3, 4));
				routes.add(route(prefix + "C下行|Line " + layout + " C Down", downReversed, 0, 1));
				routes.add(route(prefix + "B下行|Line " + layout + " B Down", downReversed, 1, 3));
				routes.add(route(prefix + "A下行|Line " + layout + " A Down", downReversed, 2, 4));
			} else {
				routes.add(route(prefix + "上行|Line " + layout + " Up", up, 0, count - 1));
				routes.add(route(prefix + "下行|Line " + layout + " Down", downReversed, 0, count - 1));
			}
			if (layout == 2) {
				// An express running through 三站 without stopping, for the timetable sensors there (2026-11-12)
				final Route express = route(prefix + "快车|Line " + layout + " Express", up, 0, count - 1);
				express.platformIds.get(2).pass = true;
				routes.add(express);
			}
			final Route upRoute = routes.get(0);
			final Route downRoute = routes.get(1);
			if (layout == 1 || layout == 4) {
				// Front turn-back: trains of 上行 may end at the westbound platform of 五站, over the crossover
				upRoute.terminalPlatforms.add(down[4].id);
			}
			if (layout == 8) {
				// Turning back in front of the platform they came in at: A下行 from 三站's eastbound platform, B上行 from its
				// westbound one
				routes.get(1).startPlatforms.add(up[2].id);
				routes.get(2).startPlatforms.add(down[2].id);
			}
			if (layout == 4) {
				// Trains from the depot start 下行 at the separate platform
				downRoute.startPlatforms.add(savedRail(railwayData.platforms, STATION_X[4], 16).id);
			}
			if (layout == 5 || layout == 6) {
				routes.add(route(prefix + "短上行|Line " + layout + " Short Up", up, 0, 2));
				routes.add(route(prefix + "短下行|Line " + layout + " Short Down", reversed(down), 2, 4));
			}
			// Eastbound on the north track (z 0), westbound on the south (z 8): looking the way a train goes, it keeps left, so
			// every line of the fixture is set to left hand running (the network's default is right hand)
			routes.forEach(route -> railwayData.dataCache.networkSettings.runningSides.put("R" + mtr.data.LineDuties.getLineKey(route), transitsim.network.RunningDirections.LEFT));
			railwayData.networkSettingsChanged = true;
			final Depot depot = new Depot(TransportMode.TRAIN);
			depot.name = prefix + "车厂|Line " + layout + " Depot";
			depot.color = COLORS[layout - 1];
			final int[] box = depotBoxes.get(0);
			depot.corner1 = new Tuple<>(box[0], z0 + box[1]);
			depot.corner2 = new Tuple<>(box[2], z0 + box[3]);
			routes.forEach(route -> depot.routeIds.add(route.id));
			railwayData.depots.add(depot);

			final JsonObject line = new JsonObject();
			line.addProperty("layout", layout);
			line.addProperty("name", LAYOUT_NAMES[layout - 1]);
			line.addProperty("z", z0);
			line.addProperty("depot", depot.id);
			line.addProperty("depotName", depot.name);
			// Where its depot is (relative to the line), so tests leave trains stored on top of each other in a siding alone
			final JsonArray depotBox = new JsonArray();
			for (final int value : box) {
				depotBox.add(value);
			}
			line.add("depotBox", depotBox);
			final JsonArray xs = new JsonArray();
			for (final int x : stationX) {
				xs.add(x);
			}
			line.add("stationX", xs);
			final JsonArray routeArray = new JsonArray();
			routes.forEach(route -> {
				final JsonObject entry = new JsonObject();
				entry.addProperty("id", route.id);
				entry.addProperty("name", route.name);
				routeArray.add(entry);
			});
			line.add("routes", routeArray);
			final JsonArray stationArray = new JsonArray();
			for (final Station station : stations) {
				stationArray.add(station.id);
			}
			line.add("stations", stationArray);
			final JsonArray sidingArray = new JsonArray();
			railwayData.sidings.stream().filter(siding -> depot.inArea(siding.getMidPos().getX(), siding.getMidPos().getZ())).forEach(siding -> sidingArray.add(siding.id));
			line.add("sidings", sidingArray);
			if (layout == 2) {
				line.add("sensors", placeSensors(STATION_X[2]));
			}
			if (layout == 10) {
				// The two tails behind 五站, as x from, x to and z (absolute), so tests can tell which one a train is on
				final JsonArray tails = new JsonArray();
				for (final int z : new int[]{0, 8}) {
					final JsonArray tail = new JsonArray();
					tail.add(east + 100);
					tail.add(east + 160);
					tail.add(z0 + z);
					tails.add(tail);
				}
				line.add("tails", tails);
			}
			return line;
		}

		/**
		 * A shunting neck behind two sidings ending at x: both join a turn-back stub further east, so a train runs out of one,
		 * changes ends on the stub and runs into the other
		 */
		private void shuntingNeck(int x, int z1, int z2) {
			final int zMiddle = (z1 + z2) / 2;
			rail(x, z1, x + 40, zMiddle, RailType.IRON);
			rail(x, z2, x + 40, zMiddle, RailType.IRON);
			rail(x + 40, zMiddle, x + 80, zMiddle, RailType.TURN_BACK);
		}

		/** A crossover before 五站 from the eastbound track to the westbound one, so trains turn back in front of it */
		/**
		 * Line 8's crossovers at 三站 (2026-11-14): west of it from the eastbound track over to the westbound one, for A's
		 * trains leaving 三站's eastbound platform westwards; east of it from the westbound track over to the eastbound one,
		 * for B's leaving its westbound platform eastwards.
		 */
		private void middleFrontCrossovers() {
			final int west = STATION_X[2] - 20;
			mainNode(0, west);
			mainNode(8, west - 40);
			rail(west, 0, west - 40, 8, RailType.IRON);
			final int east = STATION_X[2] + PLATFORM + 20;
			mainNode(8, east);
			mainNode(0, east + 40);
			rail(east, 8, east + 40, 0, RailType.IRON);
		}

		private void frontCrossover() {
			final int x = STATION_X[4];
			mainNode(0, x - 50);
			mainNode(8, x - 10);
			rail(x - 50, 0, x - 10, 8, RailType.IRON);
		}

		/**
		 * Timetable sensors 3 blocks north of the eastbound platform at x, each set another way (2026-11-12): before arrival
		 * 10 s for every train, for passing trains only, for stopping trains only; the legacy trigger for every train and
		 * for stopping trains only. Their chunks are kept loaded, so they tick with no player near, and the server driver
		 * records each time one is powered.
		 */
		private JsonArray placeSensors(int x) {
			final String[][] settings = {{"BEFORE_ARRIVAL", "0"}, {"BEFORE_ARRIVAL", "2"}, {"BEFORE_ARRIVAL", "1"}, {"LEGACY", "0"}, {"LEGACY", "1"}};
			final JsonArray sensors = new JsonArray();
			for (int i = 0; i < settings.length; i++) {
				final BlockPos pos = pos(x + 4 + i * 10, -3);
				level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
				level.setBlockAndUpdate(pos, Blocks.TRAIN_SCHEDULE_SENSOR.get().defaultBlockState());
				final net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(pos);
				if (!(entity instanceof mtr.block.BlockTrainScheduleSensor.TileEntityTrainScheduleSensor)) {
					throw new IllegalStateException("no timetable sensor at " + pos);
				}
				((mtr.block.BlockTrainScheduleSensor.TileEntityTrainScheduleSensor) entity).setData(new java.util.HashSet<>(), false, false, 10, "10", settings[i][0], settings[i][1]);
				entity.setChanged();
				ServerDriver.watchSensor(pos);
				final JsonObject sensor = new JsonObject();
				sensor.addProperty("x", pos.getX());
				sensor.addProperty("y", pos.getY());
				sensor.addProperty("z", pos.getZ());
				sensor.addProperty("trigger", settings[i][0]);
				sensor.addProperty("kind", Integer.parseInt(settings[i][1]));
				sensors.add(sensor);
			}
			return sensors;
		}

		/** A turn-back track between the main tracks after 三站, reached from both */
		private void middleTurnBack(int middle, int length) {
			mainNode(0, middle + 30);
			mainNode(8, middle + 30);
			rail(middle + 30, 0, middle + 70, 4, RailType.IRON);
			rail(middle + 30, 8, middle + 70, 4, RailType.IRON);
			rail(middle + 70, 4, middle + 70 + length, 4, RailType.TURN_BACK);
		}

		/** The depot behind the turn-back track behind the last station, as line 2's */
		private void depotBehindTurnBack(int east, List<int[]> depotBoxes) {
			rail(east + 100, 0, east + 130, 0, RailType.IRON);
			rail(east + 130, 0, east + 210, 0, RailType.SIDING);
			depotBoxes.add(new int[]{east + 120, -6, east + 220, 6});
		}

		/**
		 * Two dead-end turn-back tracks behind 五站 (站后双折返): T1 straight on from the eastbound track (z 0), T2 straight on
		 * from the westbound one (z 8). Crossover A takes eastbound trains over to T2, crossover B takes trains out of T1 back
		 * to the westbound track; trains going into T2 and out of T1 share the westbound track between the two crossovers,
		 * against each other, which the throat has to keep apart. The depot branches off the westbound track between B and
		 * T2, its trains leaving westwards.
		 */
		private void doubleRearTurnBack(int east, List<int[]> depotBoxes) {
			mainNode(0, east + 10);
			mainNode(0, east + 100);
			mainNode(8, east + 50);
			mainNode(8, east + 60);
			mainNode(8, east + 80);
			mainNode(8, east + 100);
			rail(east + 100, 0, east + 160, 0, RailType.TURN_BACK);
			rail(east + 100, 8, east + 160, 8, RailType.TURN_BACK);
			rail(east + 10, 0, east + 50, 8, RailType.IRON);
			rail(east + 100, 0, east + 60, 8, RailType.IRON);
			rail(east + 80, 8, east + 120, 16, RailType.IRON);
			rail(east + 120, 16, east + 200, 16, RailType.SIDING);
			depotBoxes.add(new int[]{east + 115, 12, east + 210, 20});
		}

		/** A turn-back track behind 五站: eastbound trains run on into it and come back out onto the westbound track */
		private void rearTurnBack(int east) {
			mainNode(0, east + 40);
			rail(east, 8, east + 40, 0, RailType.IRON);
			rail(east + 40, 0, east + 100, 0, RailType.TURN_BACK);
		}

		private void mainNode(int z, int x) {
			mains.computeIfAbsent(z, key -> new TreeSet<>()).add(x);
		}

		private BlockPos pos(int x, int z) {
			return new BlockPos(x, Y, z0 + z);
		}

		private void rail(int x1, int z1, int x2, int z2, RailType railType) {
			final BlockPos start = pos(x1, z1);
			final BlockPos end = pos(x2, z2);
			for (final BlockPos node : new BlockPos[]{start, end}) {
				if (placed.add(node)) {
					// Every track runs east and west, so every node faces that way
					level.setBlockAndUpdate(node, Blocks.RAIL_NODE.get().defaultBlockState().setValue(BlockNode.FACING, true));
				}
			}
			Fixture.connectRail(level, railwayData, player, start, end, railType);
		}

		private <T extends SavedRailBase> T savedRail(Set<T> savedRails, int x, int z) {
			final BlockPos node = pos(x, z);
			for (final T savedRail : savedRails) {
				if (savedRail.containsPos(node)) {
					return savedRail;
				}
			}
			throw new IllegalStateException("layout " + layout + ": no platform or siding at " + node);
		}

		private Station station(String name, int color, int xMin, int zMin, int xMax, int zMax) {
			final Station station = new Station();
			station.name = name;
			station.color = color;
			station.corner1 = new Tuple<>(xMin, z0 + zMin);
			station.corner2 = new Tuple<>(xMax, z0 + zMax);
			railwayData.stations.add(station);
			return station;
		}

		private Route route(String name, Platform[] platforms, int from, int to) {
			final Route route = new Route(TransportMode.TRAIN);
			route.name = name;
			route.color = COLORS[layout - 1];
			for (int i = from; i <= to; i++) {
				route.platformIds.add(new Route.RoutePlatform(platforms[i].id));
			}
			railwayData.routes.add(route);
			return route;
		}

		private static Platform[] reversed(Platform[] platforms) {
			final Platform[] result = new Platform[platforms.length];
			for (int i = 0; i < platforms.length; i++) {
				result[i] = platforms[platforms.length - 1 - i];
			}
			return result;
		}
	}
}
