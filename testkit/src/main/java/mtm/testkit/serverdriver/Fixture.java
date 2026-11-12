package mtm.testkit.serverdriver;

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
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Builds a small, fixed test network into a flat world, the same way a player's rail connector would, so every run starts
 * from the same data. All of it lies on z = 0, one block above the grass of the default flat world:
 *
 * <pre>
 * x: -140  -110  -90   -60   -15    15    60    90    110   140
 *     [W siding]-[ A platform ]-[ B platform ]-[ C platform ]-[E siding]
 * </pre>
 * <p>
 * Stations A, B and C, depots West (siding W, any number of trains) and East (siding E, at most one train), route
 * "Test Line" A→B→C served by West and "Test Line Back" C→B→A served by East, and 30 filler stations without an area.
 */
public final class Fixture {

	public static final int Y = -60;
	public static final int FILLER_STATIONS = 30;

	private Fixture() {
	}

	public static JsonObject build(ServerLevel level, String name) {
		if (!"basic".equals(name)) {
			throw new IllegalArgumentException("no fixture " + name);
		}
		final RailwayData railwayData = RailwayData.getInstance(level);
		if (railwayData == null) {
			throw new IllegalStateException("no railway data");
		}
		if (!railwayData.stations.isEmpty() || !railwayData.platforms.isEmpty()) {
			throw new IllegalStateException("the world already has railway data; the fixture is built into a new world only");
		}
		final FakePlayer player = FakePlayer.get(level);

		final int[] xs = {-140, -110, -90, -60, -15, 15, 60, 90, 110, 140};
		final RailType[] types = {RailType.SIDING, RailType.IRON, RailType.PLATFORM, RailType.IRON, RailType.PLATFORM, RailType.IRON, RailType.PLATFORM, RailType.IRON, RailType.SIDING};
		for (final int x : xs) {
			level.setBlockAndUpdate(new BlockPos(x, Y, 0), Blocks.RAIL_NODE.get().defaultBlockState().setValue(BlockNode.FACING, true));
		}
		for (int i = 0; i < types.length; i++) {
			connect(level, railwayData, player, new BlockPos(xs[i], Y, 0), new BlockPos(xs[i + 1], Y, 0), types[i]);
		}

		final Platform platformA = platformAt(railwayData.platforms, -90);
		final Platform platformB = platformAt(railwayData.platforms, -15);
		final Platform platformC = platformAt(railwayData.platforms, 60);
		final Siding sidingWest = platformAt(railwayData.sidings, -140);
		final Siding sidingEast = platformAt(railwayData.sidings, 140);

		final Station stationA = station(railwayData, "甲站|Station A", 0xE53935, -95, -55);
		final Station stationB = station(railwayData, "乙站|Station B", 0x1E88E5, -20, 20);
		final Station stationC = station(railwayData, "丙站|Station C", 0x43A047, 55, 95);

		// Stations without an area, so the dashboard's station list is longer than a page and its route and depot lists
		// are short (switching between them is test U24)
		for (int i = 1; i <= FILLER_STATIONS; i++) {
			final Station filler = new Station();
			filler.name = String.format("填充站%02d|Filler %02d", i, i);
			filler.color = 0x9E9E9E;
			railwayData.stations.add(filler);
		}

		final Route route = new Route(TransportMode.TRAIN);
		route.name = "测试线|Test Line";
		route.color = 0xFB8C00;
		route.platformIds.add(new Route.RoutePlatform(platformA.id));
		route.platformIds.add(new Route.RoutePlatform(platformB.id));
		route.platformIds.add(new Route.RoutePlatform(platformC.id));
		railwayData.routes.add(route);

		final Route routeBack = new Route(TransportMode.TRAIN);
		routeBack.name = "测试线回程|Test Line Back";
		routeBack.color = 0xFB8C00;
		routeBack.platformIds.add(new Route.RoutePlatform(platformC.id));
		routeBack.platformIds.add(new Route.RoutePlatform(platformB.id));
		routeBack.platformIds.add(new Route.RoutePlatform(platformA.id));
		railwayData.routes.add(routeBack);

		final Depot depotWest = depot(railwayData, "西车厂|West Depot", 0x8E24AA, -145, -105);
		depotWest.routeIds.add(route.id);
		final Depot depotEast = depot(railwayData, "东车厂|East Depot", 0x6D4C41, 105, 145);
		depotEast.routeIds.add(routeBack.id);

		// West keeps any number of trains, East at most one
		setSidingLimit(sidingWest, true, 0);
		setSidingLimit(sidingEast, false, 1);

		railwayData.dataCache.sync();

		final JsonObject result = new JsonObject();
		result.addProperty("y", Y);
		result.addProperty("platformA", platformA.id);
		result.addProperty("platformB", platformB.id);
		result.addProperty("platformC", platformC.id);
		result.addProperty("sidingWest", sidingWest.id);
		result.addProperty("sidingEast", sidingEast.id);
		result.addProperty("stationA", stationA.id);
		result.addProperty("stationB", stationB.id);
		result.addProperty("stationC", stationC.id);
		result.addProperty("route", route.id);
		result.addProperty("routeBack", routeBack.id);
		result.addProperty("depotWest", depotWest.id);
		result.addProperty("depotEast", depotEast.id);
		result.addProperty("fillerStations", FILLER_STATIONS);
		return result;
	}

	/**
	 * The rail connector's own steps (ItemNodeModifierBase.onEndClick and ItemRailModifier.onConnect) for a straight rail.
	 */
	private static void connect(ServerLevel level, RailwayData railwayData, FakePlayer player, BlockPos posStart, BlockPos posEnd, RailType railType) {
		final float angleDifference = (float) Math.toDegrees(Math.atan2(posEnd.getZ() - posStart.getZ(), posEnd.getX() - posStart.getX()));
		final BlockState stateStart = level.getBlockState(posStart);
		final BlockState stateEnd = level.getBlockState(posEnd);
		final float angle1 = BlockNode.getAngle(level, posStart, stateStart);
		final float angle2 = BlockNode.getAngle(level, posEnd, stateEnd);
		final RailAngle facingStart = RailAngle.fromAngle(angle1 + (RailAngle.similarFacing(angleDifference, angle1) ? 0 : 180));
		final RailAngle facingEnd = RailAngle.fromAngle(angle2 + (RailAngle.similarFacing(angleDifference, angle2) ? 180 : 0));
		final Rail rail1 = new Rail(posStart, facingStart, posEnd, facingEnd, railType, TransportMode.TRAIN);
		final Rail rail2 = new Rail(posEnd, facingEnd, posStart, facingStart, railType, TransportMode.TRAIN);
		if (!rail1.isValid() || !rail2.isValid() || !rail1.goodRadius() || !rail2.goodRadius()) {
			throw new IllegalStateException(String.format("rail %s → %s is not valid (node angles %s, %s)", posStart, posEnd, angle1, angle2));
		}
		railwayData.addRail(player, TransportMode.TRAIN, posStart, posEnd, rail1, false);
		railwayData.addRail(player, TransportMode.TRAIN, posEnd, posStart, rail2, true);
		level.setBlockAndUpdate(posStart, level.getBlockState(posStart).setValue(BlockNode.IS_CONNECTED, true));
		level.setBlockAndUpdate(posEnd, level.getBlockState(posEnd).setValue(BlockNode.IS_CONNECTED, true));
		if (!railwayData.containsRail(posStart, posEnd)) {
			throw new IllegalStateException(String.format("rail %s → %s was not added", posStart, posEnd));
		}
	}

	private static <T extends SavedRailBase> T platformAt(Set<T> savedRails, int x) {
		final BlockPos pos = new BlockPos(x, Y, 0);
		for (final T savedRail : savedRails) {
			if (savedRail.containsPos(pos)) {
				return savedRail;
			}
		}
		throw new IllegalStateException("no platform or siding at x = " + x);
	}

	private static Station station(RailwayData railwayData, String name, int color, int xMin, int xMax) {
		final Station station = new Station();
		station.name = name;
		station.color = color;
		station.corner1 = new Tuple<>(xMin, -10);
		station.corner2 = new Tuple<>(xMax, 10);
		railwayData.stations.add(station);
		return station;
	}

	private static Depot depot(RailwayData railwayData, String name, int color, int xMin, int xMax) {
		final Depot depot = new Depot(TransportMode.TRAIN);
		depot.name = name;
		depot.color = color;
		depot.corner1 = new Tuple<>(xMin, -10);
		depot.corner2 = new Tuple<>(xMax, 10);
		railwayData.depots.add(depot);
		return depot;
	}

	private static void setSidingLimit(Siding siding, boolean unlimited, int maxTrains) {
		try {
			final Field unlimitedField = Siding.class.getDeclaredField("unlimitedTrains");
			final Field maxField = Siding.class.getDeclaredField("maxTrains");
			unlimitedField.setAccessible(true);
			maxField.setAccessible(true);
			unlimitedField.setBoolean(siding, unlimited);
			maxField.setInt(siding, maxTrains);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot set the siding's train limit", e);
		}
	}
}
