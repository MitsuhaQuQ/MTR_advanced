package transitsim.sim;

import transitsim.io.BinaryKeyed;
import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;
import transitsim.io.ByteArrayBinary;
import transitsim.motion.SpeedCurves;
import transitsim.plan.PathTimes;
import transitsim.plan.PlanRoute;
import transitsim.plan.PlanStop;
import transitsim.plan.PlanTrack;
import transitsim.plan.RouteTimetable;
import transitsim.plan.StopTimes;
import transitsim.plan.TimeGenerator;
import transitsim.plan.TimeSegment;
import transitsim.plan.TimetableBinary;
import transitsim.plan.TripPath;
import transitsim.plan.TurnBacks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What the simulator needs of a saved world, in the component's own format (S4e): routes with their stops and
 * timetables, depots with their routes and duties, and sidings with their trains and paths. The game writes it into its
 * database when it saves, and the day tool reads it without the game.
 * <p>
 * Each world built from it has routes and trains of its own, so forks of a simulation started from it share nothing.
 */
public final class SimDataset {

	/**
	 * Version 2 adds the trains as they were (G0), version 3 the signal blocks (G1); older data is read without them.
	 */
	public static final int VERSION = 3;
	/**
	 * How many trains a siding with no limit has out at most, as in the game.
	 */
	public static final int UNLIMITED_TRAINS = 256;

	public final List<RouteData> routes = new ArrayList<>();
	public final List<DepotData> depots = new ArrayList<>();
	public final List<SidingData> sidings = new ArrayList<>();
	/**
	 * The trains as they were when the data was written (G0), to simulate on from them, or null if there are none.
	 */
	public RunRecord trains;
	/**
	 * The signal blocks, each with the keys of its rails ({@link DataTrack#getTrackKey}), as the game's signals make them.
	 */
	public final List<List<UUID>> signalBlocks = new ArrayList<>();

	public byte[] toBytes() {
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		writer.writeInt(VERSION);
		writer.writeVarInt(routes.size());
		routes.forEach(route -> route.write(writer));
		writer.writeVarInt(depots.size());
		depots.forEach(depot -> depot.write(writer));
		writer.writeVarInt(sidings.size());
		sidings.forEach(siding -> siding.write(writer));
		writer.writeBoolean(trains != null);
		if (trains != null) {
			writeBytes(writer, trains.toBytes());
		}
		writer.writeVarInt(signalBlocks.size());
		signalBlocks.forEach(rails -> {
			writer.writeVarInt(rails.size());
			rails.forEach(rail -> {
				writer.writeLong(rail.getMostSignificantBits());
				writer.writeLong(rail.getLeastSignificantBits());
			});
		});
		return writer.toBytes();
	}

	public static SimDataset fromBytes(byte[] bytes) {
		final ByteArrayBinary.Reader reader = new ByteArrayBinary.Reader(bytes);
		final int version = reader.readInt();
		if (version < 1 || version > VERSION) {
			throw new IllegalArgumentException("Unknown simulation data version " + version);
		}
		final SimDataset dataset = new SimDataset();
		for (int i = reader.readVarInt(); i > 0; i--) {
			dataset.routes.add(RouteData.read(reader));
		}
		for (int i = reader.readVarInt(); i > 0; i--) {
			dataset.depots.add(DepotData.read(reader));
		}
		for (int i = reader.readVarInt(); i > 0; i--) {
			dataset.sidings.add(SidingData.read(reader));
		}
		if (version >= 2 && reader.readBoolean()) {
			dataset.trains = RunRecord.fromBytes(readBytes(reader));
		}
		if (version >= 3) {
			for (int i = reader.readVarInt(); i > 0; i--) {
				final List<UUID> rails = new ArrayList<>();
				for (int j = reader.readVarInt(); j > 0; j--) {
					rails.add(new UUID(reader.readLong(), reader.readLong()));
				}
				dataset.signalBlocks.add(rails);
			}
		}
		return dataset;
	}

	/**
	 * Puts a route in place of the one with its ID, as for a draft of its timetable (S5).
	 *
	 * @return false if there is no route with its ID
	 */
	public boolean replaceRoute(RouteData route) {
		for (int i = 0; i < routes.size(); i++) {
			if (routes.get(i).id == route.id) {
				routes.set(i, route);
				return true;
			}
		}
		return false;
	}

	/**
	 * Gives a depot other duties, as for a draft of them (S5).
	 *
	 * @param duties as {@link transitsim.plan.DepotDuties#toBytes} gives them, or empty for none
	 * @return false if there is no depot with this ID
	 */
	public boolean replaceDuties(long depotId, byte[] duties) {
		for (int i = 0; i < depots.size(); i++) {
			final DepotData depot = depots.get(i);
			if (depot.id == depotId) {
				depots.set(i, new DepotData(depot.id, depot.routeIds, depot.cycleRoutes, duties));
				return true;
			}
		}
		return false;
	}

	/**
	 * @return makes the world of the saved data afresh each time, starting at this time, without its trains
	 */
	public static Supplier<TickRunner<DataTrack>> start(byte[] bytes, long startMillis) {
		return () -> fromBytes(bytes).buildRunner(startMillis);
	}

	/**
	 * Builds a world of its own from the data: new routes, depots and sidings, which make their trains as they run.
	 */
	public TickRunner<DataTrack> buildRunner(long startMillis) {
		final Map<Long, DataRoute> routeMap = buildRoutes();
		final TickRunner<DataTrack> runner = new TickRunner<>(startMillis, routeMap::get);
		runner.setSignalBlocks(signalBlocks);
		final Map<Long, DepotBoard> boards = new HashMap<>();
		for (final DepotData depot : depots) {
			final DepotBoard board = new DepotBoard(toRoutes(depot.routeIds, routeMap));
			board.cycleRoutes = depot.cycleRoutes;
			if (depot.duties.length > 0) {
				board.duties.fromBytes(depot.duties);
			}
			boards.put(depot.id, board);
		}
		for (final SidingData sidingData : sidings) {
			final DepotBoard board = boards.get(sidingData.depotId);
			if (board == null || sidingData.path.isEmpty()) {
				continue;
			}
			final String trainId = "siding:" + sidingData.id;
			board.setTrainRoutes(trainId, toRoutes(sidingData.routeIds, routeMap));
			final TimeGenerator<DataTrack> generator = timeGenerator(untimed(sidingData));
			final StopTimes stopTimes = getStopTimes(sidingData);
			final TickRunner.Siding<DataTrack> siding = new TickRunner.Siding<>(sidingData.path, sidingData.repeatIndex1, sidingData.repeatIndex2, stopTimes, trainId, sidingData.trainCars, sidingData.spacing, sidingData.railLength, sidingData.acceleration, sidingData.deceleration, sidingData.maxTrains);
			siding.minSpeed = sidingData.minSpeed;
			siding.usesSpeedCurve = sidingData.usesSpeedCurve;
			sidingData.tripPaths.forEach(tripPath -> {
				final TripPath<DataTrack> newTripPath = new TripPath<>(new BinaryKeyed.Reader(new ByteArrayBinary.Reader(tripPath.values)), tripPath.path);
				newTripPath.generateTimes(generator);
				siding.tripPaths.put(newTripPath.key, newTripPath);
			});
			if (sidingData.turnBacks.length > 0) {
				siding.turnBacks = TurnBacks.read(new BinaryKeyed.Reader(new ByteArrayBinary.Reader(sidingData.turnBacks)));
			}
			runner.addSiding(sidingData.id, board, siding);
		}
		return runner;
	}

	/**
	 * The routes as plans, with the services that only run as another route's continuation marked (S6).
	 */
	public Map<Long, PlanRoute> getPlanRoutes() {
		return new TreeMap<>(buildRoutes());
	}

	/**
	 * The fastest arrival and departure at each stop of the depot's routes, worked out along each of its sidings' paths as
	 * the game's siding does, from the first siding that reaches every stop (S6).
	 */
	public Map<Long, long[][]> getFastestTimes(long depotId) {
		final Map<Long, DataRoute> routeMap = buildRoutes();
		final Map<Long, long[][]> fastestTimes = new TreeMap<>();
		for (final SidingData sidingData : sidings) {
			if (sidingData.depotId != depotId || sidingData.path.isEmpty()) {
				continue;
			}
			final StopTimes stopTimes = getStopTimes(sidingData);
			final List<PlanRoute> sidingRoutes = toRoutes(sidingData.routeIds, routeMap);
			for (final PlanRoute route : sidingRoutes) {
				final long[][] times = stopTimes.getFastestTimes(route, sidingRoutes);
				if (!fastestTimes.containsKey(route.getRouteId()) && Arrays.stream(times).noneMatch(time -> time[0] < 0)) {
					fastestTimes.put(route.getRouteId(), times);
				}
			}
		}
		return fastestTimes;
	}

	private Map<Long, DataRoute> buildRoutes() {
		final Map<Long, DataRoute> routeMap = new TreeMap<>();
		routes.forEach(route -> routeMap.put(route.id, new DataRoute(route)));
		// Services that only run as the continuation of a service of another route aren't sent out by any depot
		routeMap.values().forEach(route -> route.timetable.getTables().forEach(table -> table.links.values().forEach(link -> {
			final DataRoute linkedRoute = routeMap.get(link.routeId);
			if (linkedRoute != null) {
				linkedRoute.timetable.addLinkedService(link.service);
			}
		})));
		return routeMap;
	}

	/**
	 * A siding's train with no stop times yet, to work them out with.
	 */
	private static TickRunner.Siding<DataTrack> untimed(SidingData sidingData) {
		final TickRunner.Siding<DataTrack> untimed = new TickRunner.Siding<>(sidingData.path, sidingData.repeatIndex1, sidingData.repeatIndex2, null, "siding:" + sidingData.id, sidingData.trainCars, sidingData.spacing, sidingData.railLength, sidingData.acceleration, sidingData.deceleration, sidingData.maxTrains);
		untimed.minSpeed = sidingData.minSpeed;
		return untimed;
	}

	private static StopTimes getStopTimes(SidingData sidingData) {
		final StopTimes stopTimes = new StopTimes();
		timeGenerator(untimed(sidingData)).generateTimeSegments(sidingData.path, new ArrayList<>(), stopTimes, sidingData.loopJoinIndex, sidingData.repeatIndex2);
		return stopTimes;
	}

	/**
	 * Works out times along a path on speed curves the way the game's siding does.
	 */
	private static TimeGenerator<DataTrack> timeGenerator(TickRunner.Siding<DataTrack> siding) {
		return new TimeGenerator<DataTrack>() {
			@Override
			public StopTimes calculateStopTimes(List<DataTrack> path) {
				final StopTimes stopTimes = new StopTimes();
				generateTimeSegments(path, new ArrayList<>(), stopTimes, 0, 0);
				return stopTimes;
			}

			@Override
			public void generateTimeSegments(List<DataTrack> path, List<TimeSegment> timeSegments, StopTimes stopTimes, int joinIndex, int lapEndIndex) {
				timeSegments.clear();
				stopTimes.clear();
				stopTimes.setJoinIndex(joinIndex);
				if (!path.isEmpty()) {
					final SpeedCurves curves = SpeedCurves.of(RunTrack.toTrackView(path, siding.minSpeed), (siding.railLength + siding.trainCars * siding.spacing) / 2, siding.trainCars * siding.spacing, siding.acceleration, siding.deceleration);
					PathTimes.curveTimes(path, curves, timeSegments, new HashMap<>(), stopTimes);
				}
				stopTimes.linkJoinStops(lapEndIndex);
			}
		};
	}

	private static List<PlanRoute> toRoutes(long[] routeIds, Map<Long, DataRoute> routeMap) {
		final List<PlanRoute> list = new ArrayList<>();
		for (final long routeId : routeIds) {
			final DataRoute route = routeMap.get(routeId);
			if (route != null) {
				list.add(route);
			}
		}
		return list;
	}

	private static void writeLongs(BinaryWriter writer, long[] values) {
		writer.writeVarInt(values.length);
		for (final long value : values) {
			writer.writeLong(value);
		}
	}

	private static long[] readLongs(BinaryReader reader) {
		final long[] values = new long[reader.readVarInt()];
		for (int i = 0; i < values.length; i++) {
			values[i] = reader.readLong();
		}
		return values;
	}

	private static void writeBytes(BinaryWriter writer, byte[] bytes) {
		writer.writeVarInt(bytes.length);
		for (final byte value : bytes) {
			writer.writeByte(value);
		}
	}

	private static byte[] readBytes(BinaryReader reader) {
		final byte[] bytes = new byte[reader.readVarInt()];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = reader.readByte();
		}
		return bytes;
	}

	private static void writeFloat(BinaryWriter writer, float value) {
		writer.writeInt(Float.floatToIntBits(value));
	}

	private static float readFloat(BinaryReader reader) {
		return Float.intBitsToFloat(reader.readInt());
	}

	private static void writePath(BinaryWriter writer, List<DataTrack> path) {
		writer.writeVarInt(path.size());
		path.forEach(track -> track.write(writer));
	}

	private static List<DataTrack> readPath(BinaryReader reader) {
		final List<DataTrack> path = new ArrayList<>();
		for (int i = reader.readVarInt(); i > 0; i--) {
			path.add(DataTrack.read(reader));
		}
		return path;
	}

	/**
	 * A route: its stops in order, with the times set at each for each table, and its timetable as bytes.
	 */
	public static final class RouteData {

		public final long id;
		public final int transportModeId;
		public final long[] platformIds;
		public final boolean[] passes;
		/**
		 * For each table, then each stop, the arrival, departure and whether the train waits to be overtaken.
		 */
		public final int[][] arrivals;
		public final int[][] departures;
		public final boolean[][] overtakes;
		public final byte[] timetable;

		public RouteData(PlanRoute route) {
			id = route.getRouteId();
			transportModeId = route.getTransportModeId();
			final List<? extends PlanStop> stops = route.getStops();
			final int tableCount = route.getTimetable().getTableCount();
			platformIds = new long[stops.size()];
			passes = new boolean[stops.size()];
			arrivals = new int[tableCount][stops.size()];
			departures = new int[tableCount][stops.size()];
			overtakes = new boolean[tableCount][stops.size()];
			for (int i = 0; i < stops.size(); i++) {
				platformIds[i] = stops.get(i).getPlatformId();
				passes[i] = stops.get(i).isPass();
				for (int table = 0; table < tableCount; table++) {
					arrivals[table][i] = stops.get(i).getArrival(table);
					departures[table][i] = stops.get(i).getDeparture(table);
					overtakes[table][i] = stops.get(i).getOvertake(table);
				}
			}
			final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
			TimetableBinary.write(writer, route.getTimetable());
			timetable = writer.toBytes();
		}

		private RouteData(long id, int transportModeId, long[] platformIds, boolean[] passes, int[][] arrivals, int[][] departures, boolean[][] overtakes, byte[] timetable) {
			this.id = id;
			this.transportModeId = transportModeId;
			this.platformIds = platformIds;
			this.passes = passes;
			this.arrivals = arrivals;
			this.departures = departures;
			this.overtakes = overtakes;
			this.timetable = timetable;
		}

		/**
		 * @return the route on its own, as a draft is sent to be simulated (S5)
		 */
		public byte[] toBytes() {
			final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
			write(writer);
			return writer.toBytes();
		}

		public static RouteData fromBytes(byte[] bytes) {
			return read(new ByteArrayBinary.Reader(bytes));
		}

		private void write(BinaryWriter writer) {
			writer.writeLong(id);
			writer.writeInt(transportModeId);
			writeLongs(writer, platformIds);
			writer.writeVarInt(arrivals.length);
			for (int i = 0; i < platformIds.length; i++) {
				writer.writeBoolean(passes[i]);
				for (int table = 0; table < arrivals.length; table++) {
					writer.writeInt(arrivals[table][i]);
					writer.writeInt(departures[table][i]);
					writer.writeBoolean(overtakes[table][i]);
				}
			}
			writeBytes(writer, timetable);
		}

		private static RouteData read(BinaryReader reader) {
			final long id = reader.readLong();
			final int transportModeId = reader.readInt();
			final long[] platformIds = readLongs(reader);
			final int tableCount = reader.readVarInt();
			final boolean[] passes = new boolean[platformIds.length];
			final int[][] arrivals = new int[tableCount][platformIds.length];
			final int[][] departures = new int[tableCount][platformIds.length];
			final boolean[][] overtakes = new boolean[tableCount][platformIds.length];
			for (int i = 0; i < platformIds.length; i++) {
				passes[i] = reader.readBoolean();
				for (int table = 0; table < tableCount; table++) {
					arrivals[table][i] = reader.readInt();
					departures[table][i] = reader.readInt();
					overtakes[table][i] = reader.readBoolean();
				}
			}
			return new RouteData(id, transportModeId, platformIds, passes, arrivals, departures, overtakes, readBytes(reader));
		}
	}

	public static final class DepotData {

		public final long id;
		public final long[] routeIds;
		public final boolean cycleRoutes;
		/**
		 * The depot's duties as {@link transitsim.plan.DepotDuties#toBytes} gives them, or empty.
		 */
		public final byte[] duties;

		public DepotData(long id, long[] routeIds, boolean cycleRoutes, byte[] duties) {
			this.id = id;
			this.routeIds = routeIds;
			this.cycleRoutes = cycleRoutes;
			this.duties = duties;
		}

		private void write(BinaryWriter writer) {
			writer.writeLong(id);
			writeLongs(writer, routeIds);
			writer.writeBoolean(cycleRoutes);
			writeBytes(writer, duties);
		}

		private static DepotData read(BinaryReader reader) {
			return new DepotData(reader.readLong(), readLongs(reader), reader.readBoolean(), readBytes(reader));
		}
	}

	/**
	 * A siding: the trains it makes, the routes they run, its usual path and the paths of services leaving it.
	 */
	public static final class SidingData {

		public final long id;
		public final long depotId;
		public final long[] routeIds;
		public final int trainCars;
		public final int spacing;
		public final float railLength;
		public final float acceleration;
		public final float deceleration;
		public final int maxTrains;
		public final boolean usesSpeedCurve;
		public final float minSpeed;
		public final int repeatIndex1;
		public final int repeatIndex2;
		public final int loopJoinIndex;
		public final List<DataTrack> path;
		public final List<TripPathData> tripPaths = new ArrayList<>();
		/**
		 * As {@link TurnBacks#write} writes them into a {@link BinaryKeyed.Writer}, or empty for none.
		 */
		public final byte[] turnBacks;

		public SidingData(long id, long depotId, long[] routeIds, int trainCars, int spacing, float railLength, float acceleration, float deceleration, int maxTrains, boolean usesSpeedCurve, float minSpeed, int repeatIndex1, int repeatIndex2, int loopJoinIndex, List<DataTrack> path, byte[] turnBacks) {
			this.id = id;
			this.depotId = depotId;
			this.routeIds = routeIds;
			this.trainCars = trainCars;
			this.spacing = spacing;
			this.railLength = railLength;
			this.acceleration = acceleration;
			this.deceleration = deceleration;
			this.maxTrains = maxTrains;
			this.usesSpeedCurve = usesSpeedCurve;
			this.minSpeed = minSpeed;
			this.repeatIndex1 = repeatIndex1;
			this.repeatIndex2 = repeatIndex2;
			this.loopJoinIndex = loopJoinIndex;
			this.path = path;
			this.turnBacks = turnBacks;
		}

		private void write(BinaryWriter writer) {
			writer.writeLong(id);
			writer.writeLong(depotId);
			writeLongs(writer, routeIds);
			writer.writeInt(trainCars);
			writer.writeInt(spacing);
			writeFloat(writer, railLength);
			writeFloat(writer, acceleration);
			writeFloat(writer, deceleration);
			writer.writeInt(maxTrains);
			writer.writeBoolean(usesSpeedCurve);
			writeFloat(writer, minSpeed);
			writer.writeInt(repeatIndex1);
			writer.writeInt(repeatIndex2);
			writer.writeInt(loopJoinIndex);
			writePath(writer, path);
			writer.writeVarInt(tripPaths.size());
			tripPaths.forEach(tripPath -> {
				writeBytes(writer, tripPath.values);
				writePath(writer, tripPath.path);
			});
			writeBytes(writer, turnBacks);
		}

		private static SidingData read(BinaryReader reader) {
			final long id = reader.readLong();
			final long depotId = reader.readLong();
			final long[] routeIds = readLongs(reader);
			final int trainCars = reader.readInt();
			final int spacing = reader.readInt();
			final float railLength = readFloat(reader);
			final float acceleration = readFloat(reader);
			final float deceleration = readFloat(reader);
			final int maxTrains = reader.readInt();
			final boolean usesSpeedCurve = reader.readBoolean();
			final float minSpeed = readFloat(reader);
			final int repeatIndex1 = reader.readInt();
			final int repeatIndex2 = reader.readInt();
			final int loopJoinIndex = reader.readInt();
			final List<DataTrack> path = readPath(reader);
			final List<TripPathData> tripPaths = new ArrayList<>();
			for (int i = reader.readVarInt(); i > 0; i--) {
				tripPaths.add(new TripPathData(readBytes(reader), readPath(reader)));
			}
			final SidingData siding = new SidingData(id, depotId, routeIds, trainCars, spacing, railLength, acceleration, deceleration, maxTrains, usesSpeedCurve, minSpeed, repeatIndex1, repeatIndex2, loopJoinIndex, path, readBytes(reader));
			siding.tripPaths.addAll(tripPaths);
			return siding;
		}
	}

	/**
	 * A path of a service leaving a siding: its values as {@link TripPath#write} writes them into a
	 * {@link BinaryKeyed.Writer}, and its tracks.
	 */
	public static final class TripPathData {

		public final byte[] values;
		public final List<DataTrack> path;

		public TripPathData(byte[] values, List<DataTrack> path) {
			this.values = values;
			this.path = path;
		}
	}

	/**
	 * A track of a saved path.
	 */
	public static final class DataTrack implements RunTrack {

		private final long startNode;
		private final long endNode;
		private final double length;
		private final boolean platform;
		private final boolean siding;
		private final long stopId;
		private final int dwellTime;
		private final float maxSpeed;
		private final boolean canAccelerate;
		private final boolean hasSignal;
		private final boolean twoWay;
		private int stopIndex;

		public DataTrack(long startNode, long endNode, double length, boolean platform, boolean siding, long stopId, int dwellTime, int stopIndex, float maxSpeed, boolean canAccelerate, boolean hasSignal, boolean twoWay) {
			this.startNode = startNode;
			this.endNode = endNode;
			this.length = length;
			this.platform = platform;
			this.siding = siding;
			this.stopId = stopId;
			this.dwellTime = dwellTime;
			this.stopIndex = stopIndex;
			this.maxSpeed = maxSpeed;
			this.canAccelerate = canAccelerate;
			this.hasSignal = hasSignal;
			this.twoWay = twoWay;
		}

		private void write(BinaryWriter writer) {
			writer.writeLong(startNode);
			writer.writeLong(endNode);
			writer.writeLong(Double.doubleToLongBits(length));
			writer.writeByte((platform ? 1 : 0) | (siding ? 2 : 0) | (canAccelerate ? 4 : 0) | (hasSignal ? 8 : 0) | (twoWay ? 16 : 0));
			writer.writeLong(stopId);
			writer.writeInt(dwellTime);
			writer.writeInt(stopIndex);
			writeFloat(writer, maxSpeed);
		}

		private static DataTrack read(BinaryReader reader) {
			final long startNode = reader.readLong();
			final long endNode = reader.readLong();
			final double length = Double.longBitsToDouble(reader.readLong());
			final int flags = reader.readUnsignedByte();
			final long stopId = reader.readLong();
			final int dwellTime = reader.readInt();
			final int stopIndex = reader.readInt();
			final float maxSpeed = readFloat(reader);
			return new DataTrack(startNode, endNode, length, (flags & 1) != 0, (flags & 2) != 0, stopId, dwellTime, stopIndex, maxSpeed, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0);
		}

		@Override
		public float getMaxSpeed() {
			return maxSpeed;
		}

		@Override
		public boolean canAccelerate() {
			return canAccelerate;
		}

		@Override
		public boolean hasSignal() {
			return hasSignal;
		}

		@Override
		public boolean isTwoWay() {
			return twoWay;
		}

		@Override
		public long getStartNode() {
			return startNode;
		}

		@Override
		public long getEndNode() {
			return endNode;
		}

		@Override
		public double getTrackLength() {
			return length;
		}

		@Override
		public int getDwellTime() {
			return dwellTime;
		}

		@Override
		public boolean isPlatformTrack() {
			return platform;
		}

		@Override
		public boolean isSidingTrack() {
			return siding;
		}

		@Override
		public long getStopId() {
			return stopId;
		}

		@Override
		public int getStopIndex() {
			return stopIndex;
		}

		@Override
		public void setStopIndex(int stopIndex) {
			this.stopIndex = stopIndex;
		}

		@Override
		public boolean isOppositeOf(PlanTrack track) {
			return track instanceof DataTrack && ((DataTrack) track).startNode == endNode && ((DataTrack) track).endNode == startNode;
		}

		@Override
		public UUID getTrackKey() {
			return new UUID(Math.min(startNode, endNode), Math.max(startNode, endNode));
		}
	}

	/**
	 * A route built from its data, with a timetable of its own.
	 */
	private static final class DataRoute implements PlanRoute {

		private final long id;
		private final int transportModeId;
		private final RouteTimetable timetable;
		private final List<DataStop> stops = new ArrayList<>();

		private DataRoute(RouteData data) {
			id = data.id;
			transportModeId = data.transportModeId;
			timetable = TimetableBinary.read(new ByteArrayBinary.Reader(data.timetable));
			for (int i = 0; i < data.platformIds.length; i++) {
				stops.add(new DataStop(data, i));
			}
		}

		@Override
		public long getRouteId() {
			return id;
		}

		@Override
		public RouteTimetable getTimetable() {
			return timetable;
		}

		@Override
		public List<DataStop> getStops() {
			return stops;
		}

		@Override
		public boolean isLoop() {
			return stops.size() > 2 && stops.get(0).getPlatformId() == getLastPlatformId();
		}

		@Override
		public int getTransportModeId() {
			return transportModeId;
		}
	}

	private static final class DataStop implements PlanStop {

		private final RouteData data;
		private final int index;

		private DataStop(RouteData data, int index) {
			this.data = data;
			this.index = index;
		}

		@Override
		public long getPlatformId() {
			return data.platformIds[index];
		}

		@Override
		public boolean isPass() {
			return data.passes[index];
		}

		@Override
		public int getArrival(int table) {
			return table >= 0 && table < data.arrivals.length ? data.arrivals[table][index] : -1;
		}

		@Override
		public int getDeparture(int table) {
			return table >= 0 && table < data.departures.length ? data.departures[table][index] : -1;
		}

		@Override
		public boolean getOvertake(int table) {
			return table >= 0 && table < data.overtakes.length && data.overtakes[table][index];
		}
	}
}
