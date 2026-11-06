package transitsim.sim;

import transitsim.io.BinaryKeyed;
import transitsim.io.ByteArrayBinary;
import transitsim.motion.MotionConstants;
import transitsim.motion.MotionCurves;
import transitsim.motion.MotionEvent;
import transitsim.motion.MotionHooks;
import transitsim.motion.MotionState;
import transitsim.motion.MotionTrack;
import transitsim.motion.MotionVehicle;
import transitsim.motion.RunSegment;
import transitsim.motion.SpeedCurves;
import transitsim.motion.SpeedProfile;
import transitsim.motion.TrainMotion;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.RouteTimetable;
import transitsim.plan.StopTimes;
import transitsim.plan.TripPath;
import transitsim.plan.TurnBacks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * Runs trains tick by tick the way the game's server does, without a world (S4a): each siding's trains in turn by
 * {@link TrainMotion}, {@link TrainRules} and {@link TrainService}, then the runs asked for, then the depots sending trains
 * out. Rails are taken the way an {@link Occupancy} has them and runs of trains on duties are kept in
 * {@link RunReservations}.
 * <p>
 * Skipping (S4c2, S4f), a train is only moved in the ticks in which something can change for it. In the others it only
 * goes on along its segment or stands, which it catches up on when it is next moved or looked at. A train left alone is
 * woken when a rail it looked at is taken or let go, when the services its depot has taken change, and for every input.
 * Ticks in which no train moves are skipped altogether. The results are the same as moving every train in every tick.
 * <p>
 * It is what the event driven simulator is compared against, and runs the fixed whole-chain scenarios. Signal blocks
 * spanning several rails are taken as the game's signals take them (G1), and depots send trains out by a timetable, by
 * duties or by frequency as the game's do. A siding whose trains may be driven by hand has them stand until the host
 * takes them over and drives them out. Left out, as they need a world or a player: driving by hand itself, passengers and
 * doors' platforms.
 */
public final class TickRunner<T extends RunTrack> {

	private RunReservations reservations = new RunReservations();
	/**
	 * The interlocking zone of each rail in one, by its key; see {@link #setInterlockZones}.
	 */
	private final Map<UUID, Long> zoneOfRail = new HashMap<>();
	/**
	 * Whether the host deals with the requests for runs once its tick is over, as the reservations are shared with its own
	 * trains (G1).
	 */
	private boolean hostResolves;
	private final Map<Long, SidingRun> sidings = new TreeMap<>();
	/**
	 * The nodes joined to each node by the rails of the sidings' paths, for the turnouts of station throats
	 * ({@link ThroatLocks}); a rail no path uses can't be in anybody's way. Made again when a siding is added.
	 */
	private Map<Long, Set<Long>> nodeNeighbours;
	/**
	 * Each depot once, in the order of its first siding, and its sidings in order.
	 */
	private final List<DepotBoard> depots = new ArrayList<>();
	private final Map<DepotBoard, List<SidingRun>> depotSidings = new HashMap<>();
	/**
	 * The IDs of the trains kept in the sidings' sheds (P2-C), which no new train is given.
	 */
	private final Set<Long> shedIds = new HashSet<>();
	private final Map<Long, HeadlessTrain> trainsById = new HashMap<>();
	private final List<MotionEvent> events = new ArrayList<>();
	/**
	 * Rails taken by something the runner doesn't move, such as a driven train, until a time.
	 */
	private final Map<UUID, Long> externalUntil = new TreeMap<>();
	/**
	 * The rails of trains the runner doesn't move, by their IDs, and who stands for each of them on the rails (G0).
	 */
	private final Map<Long, Set<UUID>> externalTrainRails = new HashMap<>();
	private final Map<Long, Occupant> externalTrainOccupants = new HashMap<>();
	/**
	 * The runs held by trains the runner doesn't move, by their IDs, held again in every tick (G1).
	 */
	private final Map<Long, List<RunReservations.Move>> externalRuns = new TreeMap<>();
	/**
	 * What has each rail, as an {@link Occupancy} has it in this tick and the one before.
	 */
	private final Map<UUID, RailEntry> rails = new HashMap<>();
	/**
	 * For each rail of a signal block, the rails of every block it is in (G1).
	 */
	private final Map<UUID, List<UUID>> blockRails = new HashMap<>();
	/**
	 * The signal blocks as given.
	 */
	private final List<List<UUID>> signalBlockList = new ArrayList<>();
	/**
	 * The signal blocks cut into block sections, worked out once the paths are known; see {@link #getBlockSections}.
	 */
	private transitsim.network.BlockSections blockSections;
	/**
	 * Rails whose occupants changed since the end of the last tick.
	 */
	private final List<RailEntry> changedRails = new ArrayList<>();
	/**
	 * Tracks gone from the network, which no train runs onto (contracts document 5.1, rule 5).
	 */
	private final Set<UUID> removedTracks = new HashSet<>();
	/**
	 * The trains moved in this tick, in the order they moved.
	 */
	private final List<HeadlessTrain> movedTrains = new ArrayList<>();
	/**
	 * Trains taken away in this tick, whose rails stay taken until its end.
	 */
	private final List<HeadlessTrain> removedTrains = new ArrayList<>();
	/**
	 * Trains whose services reserved while they were left alone are let go when the depots start their tick, as the
	 * services they reserved in every tick would be.
	 */
	private final List<HeadlessTrain> standingDrops = new ArrayList<>();
	/**
	 * The depots a train that was moved in this tick could leave from.
	 */
	private final Set<DepotBoard> readyDepots = new HashSet<>();
	private final LongFunction<? extends PlanRoute> routes;
	private long millis;
	private long nextTrainId = 1;
	/**
	 * Gives each new train its ID, or null to count up from 1.
	 */
	private LongSupplier trainIds;
	/**
	 * Whether trains are left alone in ticks in which nothing can change for them (S4c2, S4f).
	 */
	private boolean skipping;
	private Runnable afterTick = () -> {
	};
	private TrainListener<T> trainListener;
	/**
	 * Ticks until the trains restored from a record are put where the plan has them by now, and where they were put.
	 */
	private int recoveryTicks;
	private final List<String> recovered = new ArrayList<>();
	/**
	 * While trains are put where the plan has them, a rail anybody else has counts as taken, as they move one by one.
	 */
	private boolean aligning;
	private long skippedTicks;
	private long trainTicks;
	/**
	 * The last tick run, so the depots forget the services reserved in the ticks before a skip.
	 */
	private long lastTickMillis;
	private boolean inTick;
	/**
	 * How far the tick has got through the trains in their order: trains after this one still move in it.
	 */
	private Occupant tickPosition = Occupant.FIRST;
	private HeadlessTrain movingTrain;
	/**
	 * The train working out how long it can be left alone, which watches the rails it looks at.
	 */
	private HeadlessTrain quietTrain;

	/**
	 * The occupant of rails taken from outside.
	 */
	public static final long EXTERNAL_OCCUPANT = -1;
	private static final long TICK = MotionConstants.MILLIS_PER_TICK;
	/**
	 * The longest a standing train is left alone before it works out again how long it stands, ten minutes.
	 */
	private static final long MAX_STANDING_TICKS = 12000;

	/**
	 * @param startMillis the time of the first tick, as the timetable counts it
	 * @param routes      finds a route by ID
	 */
	public TickRunner(long startMillis, LongFunction<? extends PlanRoute> routes) {
		millis = startMillis;
		lastTickMillis = startMillis;
		this.routes = routes;
	}

	public long getMillis() {
		return millis;
	}

	/**
	 * Adds a siding of a depot, which makes its first train at the next tick.
	 */
	public SidingRun addSiding(long sidingId, DepotBoard depot, Siding<T> siding) {
		final SidingRun sidingRun = new SidingRun(sidingId, depot, siding);
		sidings.put(sidingId, sidingRun);
		nodeNeighbours = null;
		blockSections = null;
		depots.clear();
		depotSidings.clear();
		sidings.values().forEach(eachSidingRun -> {
			if (!depotSidings.containsKey(eachSidingRun.depot)) {
				depots.add(eachSidingRun.depot);
			}
			depotSidings.computeIfAbsent(eachSidingRun.depot, key -> new ArrayList<>()).add(eachSidingRun);
		});
		depot.returnChooser = (eachSidingId, depotId, canGoBack) -> depotId == 0 ? chooseReturnSiding(depot, eachSidingId, canGoBack) : chooseOrderedReturnSiding(depot, eachSidingId, depotId, canGoBack);
		return sidingRun;
	}

	private Object getPoolKey(SidingRun sidingRun) {
		return sidingRun.siding.poolKey.isEmpty() ? (Object) sidingRun.sidingId : sidingRun.siding.poolKey;
	}

	/**
	 * @return the sidings of the depot whose trains can run each other's duties, this one among them
	 */
	private List<SidingRun> getPool(SidingRun sidingRun) {
		final Object poolKey = getPoolKey(sidingRun);
		final List<SidingRun> pool = new ArrayList<>();
		for (final SidingRun other : depotSidings.getOrDefault(sidingRun.depot, Collections.emptyList())) {
			if (other == sidingRun || getPoolKey(other).equals(poolKey)) {
				pool.add(other);
			}
		}
		return pool;
	}

	/**
	 * @return the depot's pool of the siding, with the sidings of the other depots of its operating line in the same pool
	 * across them, which its trains go back to as well
	 */
	private List<SidingRun> getLinePool(SidingRun sidingRun) {
		final List<SidingRun> pool = getPool(sidingRun);
		final String linePool = sidingRun.siding.linePool;
		if (!linePool.isEmpty()) {
			depotSidings.forEach((depot, depotSidingRuns) -> {
				if (depot != sidingRun.depot) {
					depotSidingRuns.forEach(other -> {
						if (linePool.equals(other.siding.linePool)) {
							pool.add(other);
						}
					});
				}
			});
		}
		return pool;
	}

	/**
	 * @return the trains of the siding's pool across its line (P2-C): on the line, standing in its sidings and kept in their
	 * sheds, the ones coming back in this tick among them
	 */
	private int getPoolCount(SidingRun sidingRun) {
		int count = 0;
		for (final SidingRun poolSiding : getLinePool(sidingRun)) {
			count += poolSiding.trains.size() + poolSiding.shed.size() + poolSiding.arriving.size();
		}
		return count;
	}

	/**
	 * @return the trains the siding's pool has in all (P2-C), or -1 for as many as its duties need
	 */
	private int getPoolFleet(SidingRun sidingRun) {
		return sidingRun.siding.poolFleet;
	}

	/**
	 * See {@link DepotDispatcher#chooseReturnSiding}: a siding has room while the trains it holds and the ones on their way
	 * to it are fewer than it holds at most.
	 */
	private long chooseReturnSiding(DepotBoard depot, long sidingId, java.util.function.LongPredicate canGoBack) {
		final SidingRun own = sidings.get(sidingId);
		if (own == null) {
			return sidingId;
		}
		final List<SidingRun> pool = getLinePool(own);
		if (pool.size() < 2) {
			return sidingId;
		}
		final Map<Long, SidingRun> poolById = new HashMap<>();
		pool.forEach(sidingRun -> poolById.put(sidingRun.sidingId, sidingRun));
		// Each siding's slots and what has run today are its own depot's
		return DepotDispatcher.chooseReturnSiding(sidingId, poolById.keySet(), eachSidingId -> poolById.get(eachSidingId).depot.duties.getSlots(eachSidingId), (eachSidingId, slot, day) -> poolById.get(eachSidingId).depot.dispatcher.hasDutyRun(eachSidingId, slot, day), millis, otherSidingId -> {
			final SidingRun other = poolById.get(otherSidingId);
			if (!canGoBack.test(otherSidingId)) {
				return false;
			}
			if (otherSidingId == sidingId || other.siding.maxTrains >= SimDataset.UNLIMITED_TRAINS) {
				return true;
			}
			final String returnKey = TripPath.getReturnKey("", otherSidingId);
			int coming = 0;
			for (final SidingRun sidingRun : pool) {
				for (final HeadlessTrain train : sidingRun.trains) {
					if (train.onRoute && train.service.getTripPathKey().endsWith(returnKey)) {
						coming++;
					}
				}
			}
			return other.getHeld() + coming < other.siding.maxTrains + 1;
		});
	}

	/**
	 * A siding of the depot a duty is set to go back to, with the same vehicle and room, the one needing a train soonest;
	 * the usual choice if it has none.
	 */
	private long chooseOrderedReturnSiding(DepotBoard depot, long sidingId, long depotId, java.util.function.LongPredicate canGoBack) {
		final SidingRun own = sidings.get(sidingId);
		if (own == null) {
			return sidingId;
		}
		final Object poolKey = own.siding.poolKey.isEmpty() ? null : own.siding.poolKey;
		final Map<Long, SidingRun> candidates = new TreeMap<>();
		sidings.values().forEach(sidingRun -> {
			if (sidingRun.depot.depotId == depotId && (sidingRun == own || poolKey != null && poolKey.equals(sidingRun.siding.poolKey)) && hasRoom(sidingRun, own) && canGoBack.test(sidingRun.sidingId)) {
				candidates.put(sidingRun.sidingId, sidingRun);
			}
		});
		if (candidates.isEmpty()) {
			// Told rather than silently going back elsewhere (P1-B)
			depot.returnOrderMisses.add(sidingId + " " + depotId);
			return chooseReturnSiding(depot, sidingId, canGoBack);
		}
		final long first = candidates.containsKey(sidingId) ? sidingId : candidates.keySet().iterator().next();
		return DepotDispatcher.chooseReturnSiding(first, candidates.keySet(), eachSidingId -> candidates.get(eachSidingId).depot.duties.getSlots(eachSidingId), (eachSidingId, slot, day) -> candidates.get(eachSidingId).depot.dispatcher.hasDutyRun(eachSidingId, slot, day), millis, eachSidingId -> true);
	}

	/**
	 * @return whether a siding has room for one more train, counting those on their way to it and back there already
	 */
	private boolean hasRoom(SidingRun sidingRun, SidingRun own) {
		if (sidingRun == own || sidingRun.siding.maxTrains >= SimDataset.UNLIMITED_TRAINS) {
			return true;
		}
		final String returnKey = TripPath.getReturnKey("", sidingRun.sidingId);
		int coming = 0;
		for (final SidingRun other : sidings.values()) {
			for (final HeadlessTrain train : other.trains) {
				if (train.onRoute && train.service.getTripPathKey().endsWith(returnKey)) {
					coming++;
				}
			}
		}
		return sidingRun.getHeld() + coming < sidingRun.siding.maxTrains + 1;
	}

	public RunReservations getReservations() {
		return reservations;
	}

	/**
	 * Has the trains hold and ask for their runs in the host's reservations, shared with the host's own trains, before the
	 * first tick (G1). The host deals with the requests once its tick is over, and calls {@link #renewReservations} in
	 * every tick of its own, as the runner may not tick in each of them.
	 */
	/**
	 * The signal blocks cut into block sections ({@link transitsim.network.BlockSections}), with the lengths and speed limits
	 * of the rails on the sidings' paths and their trips' paths.
	 */
	private transitsim.network.BlockSections getBlockSections() {
		if (blockSections == null) {
			final Map<UUID, RunTrack> tracks = new HashMap<>();
			sidings.values().forEach(sidingRun -> {
				sidingRun.siding.path.forEach(track -> tracks.putIfAbsent(track.getTrackKey(), track));
				sidingRun.siding.tripPaths.values().forEach(tripPath -> tripPath.path.forEach(track -> tracks.putIfAbsent(track.getTrackKey(), track)));
			});
			blockSections = new transitsim.network.BlockSections(signalBlockList, new transitsim.network.BlockSections.RailInfo() {
				@Override
				public double getLength(UUID rail) {
					final RunTrack track = tracks.get(rail);
					return track == null ? 0 : track.getTrackLength();
				}

				@Override
				public float getMaxSpeed(UUID rail) {
					final RunTrack track = tracks.get(rail);
					return track == null ? 0 : cutSpeed(track);
				}
			});
		}
		return blockSections;
	}

	/**
	 * @return the speed limit a track is cut into block sections by, or -1 for one never cut ({@link transitsim.network.BlockSections})
	 */
	private static float cutSpeed(RunTrack track) {
		return track.canAccelerate() ? track.getMaxSpeed() : -1;
	}

		private Map<Long, Set<Long>> getNodeNeighbours() {
		if (nodeNeighbours == null) {
			nodeNeighbours = new HashMap<>();
			sidings.values().forEach(sidingRun -> sidingRun.siding.path.forEach(track -> {
				nodeNeighbours.computeIfAbsent(track.getStartNode(), key -> new HashSet<>()).add(track.getEndNode());
				nodeNeighbours.computeIfAbsent(track.getEndNode(), key -> new HashSet<>()).add(track.getStartNode());
			}));
		}
		return nodeNeighbours;
	}

	public void shareReservations(RunReservations shared) {
		reservations = shared;
		hostResolves = true;
	}

	/**
	 * Every train holds again what it holds and asks again for the run it waits for, as it does in a tick, so a tick of the
	 * host in which the runner didn't tick doesn't take them away.
	 */
	public void renewReservations() {
		sidings.values().forEach(sidingRun -> sidingRun.trains.forEach(HeadlessTrain::renewReservations));
	}

	public LongFunction<? extends PlanRoute> getRoutes() {
		return routes;
	}

	/**
	 * Takes tracks out of the network. Trains stop short of them; the caller deals with trains standing on them.
	 */
	public void removeTracks(Set<UUID> tracks) {
		catchUpAll();
		removedTracks.addAll(tracks);
		wakeAll();
	}

	public boolean isRemoved(UUID track) {
		return removedTracks.contains(track);
	}

	/**
	 * Takes a rail from now until this time, as a train driven by hand or another train the host moves would.
	 */
	public void occupyUntil(UUID rail, long untilMillis) {
		externalUntil.put(rail, untilMillis);
		addOccupant(rail, Occupant.EXTERNAL);
	}

	/**
	 * The rails a train the runner doesn't move has now, in place of the ones it had (G0). Like rails taken from outside,
	 * they come before any train's in a tick.
	 */
	public void setExternalTrain(long trainId, Set<UUID> newRails) {
		final Occupant occupant = externalTrainOccupants.computeIfAbsent(trainId, key -> new Occupant(Long.MIN_VALUE, key == Long.MIN_VALUE ? Long.MIN_VALUE + 1 : key));
		final Set<UUID> oldRails = externalTrainRails.getOrDefault(trainId, Collections.emptySet());
		oldRails.forEach(rail -> {
			if (!newRails.contains(rail)) {
				removeOccupant(rail, occupant);
			}
		});
		newRails.forEach(rail -> {
			if (!oldRails.contains(rail)) {
				addOccupant(rail, occupant);
			}
		});
		if (newRails.isEmpty()) {
			externalTrainRails.remove(trainId);
			externalTrainOccupants.remove(trainId);
		} else {
			externalTrainRails.put(trainId, new HashSet<>(newRails));
		}
	}

	/**
	 * The runs a train the runner doesn't move holds now, in place of the ones it held (G1). The runner's trains aren't
	 * given runs against them.
	 */
	public void setExternalRun(long trainId, List<RunReservations.Move> moves) {
		if (moves.isEmpty()) {
			externalRuns.remove(trainId);
			reservations.release(trainId);
		} else {
			externalRuns.put(trainId, new ArrayList<>(moves));
			reservations.hold(trainId, moves);
		}
	}

	/**
	 * Stops moving a train, as the host has taken it over (3.1, rule 4). Its rails stay taken, as an external train's.
	 *
	 * @return false if there is no such train
	 */
	public boolean takeOver(long trainId) {
		final HeadlessTrain train = getTrain(trainId);
		if (train == null || train.takenOver) {
			return false;
		}
		final Set<UUID> heldRails = new HashSet<>(train.heldRails);
		train.dropRails();
		train.takenOver = true;
		train.requestedDeploy = false;
		train.dueMillis = Long.MAX_VALUE;
		train.dropStandingTrips();
		reservations.release(trainId);
		setExternalTrain(trainId, heldRails);
		return true;
	}

	/**
	 * Moves a train taken over again from where the host left it (3.1, rule 4): its run is worked out afresh from there and
	 * its runs asked for again. The rails reported for it go, and it takes its own from the next tick.
	 *
	 * @return false if there is no such train taken over
	 */
	public boolean release(long trainId, double railProgress, float speed) {
		final HeadlessTrain train = trainsById.get(trainId);
		if (train == null || !train.takenOver) {
			return false;
		}
		setExternalTrain(trainId, Collections.emptySet());
		train.takenOver = false;
		train.placeAt(railProgress, speed);
		wake(train);
		return true;
	}

	/**
	 * @return the trains as the game saves them, and the depots' services sent out, for carrying on from them later (G0)
	 */
	public RunRecord record() {
		catchUpAll();
		final RunRecord record = new RunRecord(millis);
		final Set<DepotBoard> recordedDepots = new HashSet<>();
		sidings.values().forEach(sidingRun -> {
			if (recordedDepots.add(sidingRun.depot)) {
				record.lastDeployedTrips.put(sidingRun.sidingId, new TreeMap<>(sidingRun.depot.dispatcher.lastDeployedTrips));
				record.dutyRunDays.put(sidingRun.sidingId, new TreeMap<>(sidingRun.depot.dispatcher.dutyRunDays));
				record.lastDeployedMillis.put(sidingRun.sidingId, sidingRun.depot.lastDeployedMillis);
				record.deployIndices.put(sidingRun.sidingId, sidingRun.depot.deployIndex);
			}
			sidingRun.trains.forEach(train -> record.trains.add(train.toRecord()));
			if (!sidingRun.shed.isEmpty() || !sidingRun.arriving.isEmpty()) {
				final List<Long> shed = new ArrayList<>(sidingRun.shed);
				shed.addAll(sidingRun.arriving);
				record.sheds.put(sidingRun.sidingId, shed);
			}
		});
		return record;
	}

	/**
	 * Puts the recorded trains back, before the first tick (G0). Carrying on (contracts document 6.4), they run on as they
	 * were. Recovering after a long stop (6.3), they are put where the plan has them once they have all taken their rails in
	 * the first tick, furthest along first, each falling back to a free stop before; lateness and the runs held are gone.
	 */
	public void restore(RunRecord record, boolean recover) {
		record.lastDeployedTrips.forEach((sidingId, trips) -> {
			final SidingRun sidingRun = sidings.get(sidingId);
			if (sidingRun != null) {
				sidingRun.depot.dispatcher.lastDeployedTrips.putAll(trips);
			}
		});
		record.dutyRunDays.forEach((sidingId, days) -> {
			final SidingRun sidingRun = sidings.get(sidingId);
			if (sidingRun != null) {
				sidingRun.depot.dispatcher.dutyRunDays.putAll(days);
			}
		});
		record.lastDeployedMillis.forEach((sidingId, lastDeployedMillis) -> {
			final SidingRun sidingRun = sidings.get(sidingId);
			if (sidingRun != null) {
				sidingRun.depot.lastDeployedMillis = lastDeployedMillis;
			}
		});
		record.deployIndices.forEach((sidingId, deployIndex) -> {
			final SidingRun sidingRun = sidings.get(sidingId);
			if (sidingRun != null) {
				sidingRun.depot.deployIndex = deployIndex;
			}
		});
		for (final RunRecord.TrainRecord trainRecord : record.trains) {
			final SidingRun sidingRun = sidings.get(trainRecord.sidingId);
			if (sidingRun == null || trainsById.containsKey(trainRecord.trainId)) {
				continue;
			}
			final HeadlessTrain train = new HeadlessTrain(trainRecord.trainId, sidingRun);
			if (train.restore(trainRecord)) {
				sidingRun.trains.add(train);
				trainsById.put(train.id, train);
				nextTrainId = Math.max(nextTrainId, train.id + 1);
			}
		}
		sidings.values().forEach(sidingRun -> sidingRun.trains.sort(Comparator.comparingLong(train -> train.id)));
		record.sheds.forEach((sidingId, ids) -> {
			final SidingRun sidingRun = sidings.get(sidingId);
			if (sidingRun != null) {
				ids.forEach(id -> {
					if (!trainsById.containsKey(id) && shedIds.add(id)) {
						sidingRun.shed.add(id);
						nextTrainId = Math.max(nextTrainId, id + 1);
					}
				});
			}
		});
		recoveryTicks = recover ? 2 : 0;
	}

	/**
	 * @return where each train was put on recovering, as {@code train rail}, once, after the tick it was done in
	 */
	public List<String> takeRecovered() {
		final List<String> placements = new ArrayList<>(recovered);
		recovered.clear();
		return placements;
	}

	/**
	 * Every train on the line goes to where the plan has it now (6.3), furthest along first.
	 */
	/**
	 * Puts a train on every duty of the depots running by duties that is out now and has no train, where the duty has it
	 * now: at the stop it stands at, or on its run between two (decoupling document, step D). Nothing is worked out from
	 * the start of the day, so it is done at once. The duties started earliest are put first, as they are furthest along,
	 * and a train whose planned place is taken goes to the free stop before it. The duties are marked as sent out today.
	 * Called on a runner just built, before its first tick.
	 *
	 * @return how many trains were put on the line
	 */
	public int placeDutyTrains() {
		final long dayStart = DutyShapes.getDayStart(millis);
		final long day = DutyShapes.getDayNumber(dayStart);
		final int weekday = OperatingDay.getWeekday(millis);
		final List<Object[]> due = new ArrayList<>();
		for (final DepotBoard depot : depots) {
			if (!depot.duties.diagramMode) {
				continue;
			}
			for (final SidingRun sidingRun : depotSidings.get(depot)) {
				final List<DepotDuties.Slot> slots = depot.duties.getSlots(sidingRun.sidingId);
				for (int i = 0; i < slots.size(); i++) {
					final DepotDuties.DayDuty duty = slots.get(i).getDuty(weekday);
					if (duty == null || duty.segments.isEmpty() || depot.dispatcher.hasDutyRun(sidingRun.sidingId, i, day)) {
						continue;
					}
					final String key = DutyShapes.getSlotKey(slots, i, weekday, routes);
					final TripPath<T> dutyPath = key == null ? null : sidingRun.siding.tripPaths.get(key);
					if (dutyPath == null) {
						continue;
					}
					final long leaveMillis = DutyShapes.getServiceMillis(dayStart, duty.segments.get(0).service) - dutyPath.stopTimes.getLeadMillis();
					if (leaveMillis <= millis) {
						due.add(new Object[]{leaveMillis, sidingRun, i, dutyPath, depot});
					}
				}
			}
		}
		due.sort(Comparator.comparingLong(entry -> (Long) entry[0]));
		int placed = 0;
		aligning = true;
		for (final Object[] entry : due) {
			final SidingRun sidingRun = (SidingRun) entry[1];
			final int slot = (Integer) entry[2];
			@SuppressWarnings("unchecked") final TripPath<T> dutyPath = (TripPath<T>) entry[3];
			final DepotBoard depot = (DepotBoard) entry[4];
			// A train kept in a shed of the pool first, so the trains stay the pool's (P2-C)
			final long keptId = takeFromPoolShed(sidingRun);
			final HeadlessTrain train = new HeadlessTrain(keptId != 0 ? keptId : newTrainId(), sidingRun);
			train.service.setStopTimes(sidingRun.siding.stopTimes, sidingRun.siding.tripPaths, sidingRun.siding.turnBacks);
			train.service.deployDuty(slot, dayStart, dutyPath);
			train.onRoute = true;
			movingTrain = train;
			train.service.alignToTimetable(true);
			if (train.onRoute) {
				sidingRun.trains.add(train);
				trainsById.put(train.id, train);
				train.writePositions();
				depot.dispatcher.markDutyRun(sidingRun.sidingId, slot, day);
				placed++;
			} else if (keptId != 0) {
				shedIds.add(keptId);
				sidingRun.shed.addFirst(keptId);
			}
		}
		aligning = false;
		movingTrain = null;
		sidings.values().forEach(sidingRun -> sidingRun.trains.forEach(this::wake));
		return placed;
	}

	private void recover() {
		// Done at the start of a tick, so only up to the end of the last one; trains due in this one still move in it
		sidings.values().forEach(sidingRun -> sidingRun.trains.forEach(train -> train.catchUp(millis - TICK)));
		final List<HeadlessTrain> trains = new ArrayList<>();
		sidings.values().forEach(sidingRun -> trains.addAll(sidingRun.trains));
		trains.sort(Comparator.comparingDouble((HeadlessTrain train) -> -train.railProgress).thenComparingLong(train -> train.id));
		aligning = true;
		for (final HeadlessTrain train : trains) {
			movingTrain = train;
			train.service.alignToTimetable(train.onRoute && !train.takenOver);
			train.writePositions();
			recovered.add(train.id + " " + train.getHeadRail());
		}
		aligning = false;
		movingTrain = null;
		trains.forEach(this::wake);
	}

	/**
	 * Leaves trains alone in ticks in which nothing can change for them, which gives the same results as moving every
	 * train in every tick (S4c2, S4f). Off, the runner moves every train in every tick, as the game does.
	 */
	public void setSkipping(boolean skipping) {
		this.skipping = skipping;
	}

	/**
	 * Has the host give new trains their IDs (G1), such as random ones as the game gives them, so they don't clash with
	 * the IDs of the trains the simulation doesn't run. An ID a train already has is asked again.
	 */
	public void setTrainIds(LongSupplier trainIds) {
		this.trainIds = trainIds;
	}

	private long newTrainId() {
		if (trainIds == null) {
			while (shedIds.contains(nextTrainId)) {
				nextTrainId++;
			}
			return nextTrainId++;
		}
		long id;
		do {
			id = trainIds.getAsLong();
		} while (trainsById.containsKey(id) || shedIds.contains(id));
		return id;
	}

	/**
	 * Takes the first train kept in a shed of the siding's pool, its own first.
	 *
	 * @return its ID, or 0 if they have none
	 */
	private long takeFromPoolShed(SidingRun sidingRun) {
		SidingRun from = sidingRun.shed.isEmpty() ? null : sidingRun;
		if (from == null) {
			for (final SidingRun poolSiding : getLinePool(sidingRun)) {
				if (!poolSiding.shed.isEmpty()) {
					from = poolSiding;
					break;
				}
			}
		}
		if (from == null) {
			return 0;
		}
		final long id = from.shed.removeFirst();
		shedIds.remove(id);
		return id;
	}

	/**
	 * @return the trains kept in a siding's shed (P2-C), oldest first, the ones coming back in this tick last; empty for
	 * a siding it doesn't run
	 */
	public List<Long> getShed(long sidingId) {
		final SidingRun sidingRun = sidings.get(sidingId);
		if (sidingRun == null) {
			return Collections.emptyList();
		}
		final List<Long> ids = new ArrayList<>(sidingRun.shed);
		ids.addAll(sidingRun.arriving);
		return ids;
	}

	/**
	 * The signal blocks (G1): a train on a rail of a block with a signal takes every rail of its blocks with it, as the
	 * game's {@code SignalBlocks} has it, unless something else already has one of them.
	 *
	 * @param blocks each with the keys of its rails
	 */
	public void setSignalBlocks(Collection<? extends Collection<UUID>> blocks) {
		signalBlockList.clear();
		blocks.forEach(block -> signalBlockList.add(new ArrayList<>(block)));
		blockSections = null;
		blockRails.clear();
		final Map<UUID, Set<UUID>> railSets = new HashMap<>();
		blocks.forEach(block -> block.forEach(rail -> railSets.computeIfAbsent(rail, key -> new LinkedHashSet<>()).addAll(block)));
		railSets.forEach((rail, rails) -> blockRails.put(rail, new ArrayList<>(rails)));
	}

	/**
	 * The interlocking zones (L2): signal blocks of the interlocking colour, each taken as a whole by a train on duty
	 * routed through a station throat over it. Each zone's ID is its place in the list, from 1.
	 *
	 * @param zones for each signal block in order, its rails if it is a zone, or empty
	 */
	public void setInterlockZones(List<? extends Collection<UUID>> zones) {
		zoneOfRail.clear();
		for (int i = 0; i < zones.size(); i++) {
			final long zoneId = i + 1;
			zones.get(i).forEach(rail -> zoneOfRail.putIfAbsent(rail, zoneId));
		}
	}

	/**
	 * Told about each event of a train as it happens, while the train is as it was then (S5). It must only read the train.
	 */
	public void setTrainListener(TrainListener<T> trainListener) {
		this.trainListener = trainListener;
	}

	/**
	 * Called at the end of every tick that is run, not skipped.
	 */
	public void setAfterTick(Runnable afterTick) {
		this.afterTick = afterTick;
	}

	/**
	 * @return how many ticks were skipped since the start, with no train moving in them
	 */
	public long getSkippedTicks() {
		return skippedTicks;
	}

	/**
	 * @return how many times a train was moved through a tick since the start
	 */
	public long getTrainTicks() {
		return trainTicks;
	}

	/**
	 * Runs ticks until this time. Trains left alone are brought up to it when they are looked at through
	 * {@link #getTrain} or {@link #getTrains}.
	 */
	public void runUntil(long endMillis) {
		while (millis + TICK <= endMillis) {
			if (skipping) {
				final long nextMillis = getNextTickMillis();
				final long lastMillis = millis + (endMillis - millis) / TICK * TICK;
				final long toMillis = Math.min(nextMillis - TICK, lastMillis);
				if (toMillis > millis) {
					skippedTicks += (toMillis - millis) / TICK;
					millis = toMillis;
				}
				if (nextMillis > lastMillis) {
					break;
				}
			}
			tick();
		}
	}

	/**
	 * @return the next tick in which a train has to move or something taken from outside is let go
	 */
	private long getNextTickMillis() {
		// What everybody has must be seen as the tick before once, and services let go when the depots next start their tick
		if (!changedRails.isEmpty() || !standingDrops.isEmpty()) {
			return millis + TICK;
		}
		long nextMillis = Long.MAX_VALUE;
		for (final long untilMillis : externalUntil.values()) {
			// Something taken from outside is let go in the first tick after its time
			nextMillis = Math.min(nextMillis, millis + Math.max(1, (untilMillis - millis) / TICK + 1) * TICK);
		}
		for (final SidingRun sidingRun : sidings.values()) {
			nextMillis = Math.min(nextMillis, sidingRun.dueMillis);
		}
		return Math.max(nextMillis, millis + TICK);
	}

	public void tick() {
		millis += TICK;
		inTick = true;
		tickPosition = Occupant.FIRST;
		if (recoveryTicks > 0 && --recoveryTicks == 0) {
			recover();
		}
		if (lastTickMillis != millis - TICK) {
			// A depot forgets the services no train reserved in the tick before, and in the ticks skipped only trains left alone did
			depots.forEach(depot -> {
				depot.dispatcher.startTick();
				depot.dispatcher.startTick();
			});
		}
		final Iterator<Map.Entry<UUID, Long>> iterator = externalUntil.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<UUID, Long> entry = iterator.next();
			if (entry.getValue() < millis) {
				iterator.remove();
				removeOccupant(entry.getKey(), Occupant.EXTERNAL);
			}
		}
		// What trains the runner doesn't move hold is held again, as each holder renews it every tick
		externalRuns.forEach(reservations::hold);
		movedTrains.clear();
		readyDepots.clear();
		for (final SidingRun sidingRun : sidings.values()) {
			if (!skipping || sidingRun.dueMillis <= millis) {
				sidingRun.tick();
			}
		}
		movingTrain = null;
		tickPosition = Occupant.LAST;
		if (!hostResolves) {
			reservations.resolve(millis);
		}
		standingDrops.forEach(HeadlessTrain::dropStandingTrips);
		standingDrops.clear();
		depots.forEach(this::deploy);
		endTick();
		inTick = false;
		lastTickMillis = millis;
		afterTick.run();
	}

	/**
	 * What everybody has at the end of the tick is what the next one sees as taken the tick before. Then the trains moved
	 * work out how long they can be left alone.
	 */
	private void endTick() {
		for (final RailEntry entry : changedRails) {
			entry.before = entry.now.isEmpty() ? Collections.emptyList() : new ArrayList<>(entry.now);
			entry.changed = false;
		}
		changedRails.clear();
		removedTrains.forEach(HeadlessTrain::dropRails);
		removedTrains.clear();
		// Trains back in a shed are kept there from the next tick, once what they held is let go (P2-C)
		for (final SidingRun sidingRun : sidings.values()) {
			if (!sidingRun.arriving.isEmpty()) {
				sidingRun.shed.addAll(sidingRun.arriving);
				sidingRun.arriving.clear();
				sidingRun.dueMillis = Math.min(sidingRun.dueMillis, millis + TICK);
			}
		}
		// A service taken or let go also wakes the trains waiting in the depots sharing its routes
		final Set<DepotBoard> changedDepots = new HashSet<>();
		for (final DepotBoard depot : depots) {
			if (depot.changed) {
				changedDepots.add(depot);
				changedDepots.addAll(depot.peers);
			}
		}
		for (final DepotBoard depot : depots) {
			depot.changed = false;
			if (changedDepots.contains(depot)) {
				final List<SidingRun> depotRuns = depotSidings.get(depot);
				if (depotRuns != null) {
					depotRuns.forEach(sidingRun -> sidingRun.trains.forEach(train -> {
						if (!train.onRoute) {
							wake(train);
						}
					}));
				}
			}
		}
		for (final SidingRun sidingRun : sidings.values()) {
			if (sidingRun.tickMillis == millis) {
				sidingRun.endTick();
			}
		}
	}

	/**
	 * @return the events of the trains since the start, in the order they happened
	 */
	public List<MotionEvent> getEvents() {
		return Collections.unmodifiableList(events);
	}

	public boolean hasTrain(long trainId) {
		return trainsById.containsKey(trainId);
	}

	/**
	 * @return the train that has the rail now, as the game's train positions would have it, or 0 if nothing has it
	 */
	public long getOccupant(UUID rail) {
		catchUpAll();
		final RailEntry entry = rails.get(rail);
		return entry == null || entry.now.isEmpty() ? 0 : entry.now.get(0).trainId;
	}

	/**
	 * @return the train with this ID as it is now, or null
	 */
	public HeadlessTrain getTrain(long trainId) {
		final HeadlessTrain train = trainsById.get(trainId);
		if (train != null) {
			train.catchUp(millis);
		}
		return train;
	}

	/**
	 * Takes a train away, which gives up everything it holds. Its siding makes a new one when it needs it.
	 *
	 * @return false if there is no such train
	 */
	public boolean removeTrain(long trainId) {
		final HeadlessTrain train = trainsById.remove(trainId);
		if (train == null) {
			return false;
		}
		train.sidingRun.trains.remove(train);
		train.removed = true;
		reservations.release(trainId);
		// The rails it had stay taken as the tick before for one more tick, and its services until the depots start that tick
		train.dropRails();
		standingDrops.add(train);
		return true;
	}

	/**
	 * @return the trains as they are now
	 */
	public List<HeadlessTrain> getTrains() {
		catchUpAll();
		final List<HeadlessTrain> trains = new ArrayList<>();
		sidings.values().forEach(sidingRun -> trains.addAll(sidingRun.trains));
		return trains;
	}

	/**
	 * @return the trains moved in the last tick that are still there, which are the only ones anything can have changed for
	 */
	public List<HeadlessTrain> getMovedTrains() {
		final List<HeadlessTrain> trains = new ArrayList<>();
		movedTrains.forEach(train -> {
			if (!train.removed) {
				trains.add(train);
			}
		});
		return trains;
	}

	/**
	 * Brings every train left alone up to now, as before an input changes what they go by.
	 */
	public void catchUpAll() {
		sidings.values().forEach(sidingRun -> sidingRun.trains.forEach(train -> train.catchUp(millis)));
	}

	/**
	 * Moves every train in the next tick, as after an input.
	 */
	public void wakeAll() {
		sidings.values().forEach(sidingRun -> sidingRun.trains.forEach(this::wake));
	}

	/**
	 * Has a train left alone moved in this tick if it hasn't had its turn in it yet, or else in the next one.
	 */
	private void wake(HeadlessTrain train) {
		if (train == movingTrain || train.removed || train.takenOver) {
			return;
		}
		final long dueMillis = inTick && train.occupant.compareTo(tickPosition) > 0 ? millis : millis + TICK;
		train.dueMillis = Math.min(train.dueMillis, dueMillis);
		train.sidingRun.dueMillis = Math.min(train.sidingRun.dueMillis, dueMillis);
	}

	private RailEntry getEntry(UUID rail) {
		return rails.computeIfAbsent(rail, key -> new RailEntry());
	}

	private void addOccupant(UUID rail, Occupant occupant) {
		final RailEntry entry = getEntry(rail);
		int index = 0;
		while (index < entry.now.size() && entry.now.get(index).compareTo(occupant) < 0) {
			index++;
		}
		if (index < entry.now.size() && entry.now.get(index) == occupant) {
			return;
		}
		entry.now.add(index, occupant);
		railChanged(entry);
	}

	private void removeOccupant(UUID rail, Occupant occupant) {
		final RailEntry entry = rails.get(rail);
		if (entry != null && entry.now.remove(occupant)) {
			railChanged(entry);
		}
	}

	private void railChanged(RailEntry entry) {
		if (!entry.changed) {
			entry.changed = true;
			changedRails.add(entry);
		}
		entry.watchers.forEach(this::wake);
	}

	/**
	 * Whether something other than this train has the rail, as {@link Occupancy#isTakenByOther} has it: taken in the tick
	 * before, or in this tick by something that moved first. A train working out how long it can be left alone sees what
	 * everybody had at the end of this tick, and watches the rail.
	 */
	private boolean isTakenByOther(UUID rail, HeadlessTrain train) {
		if (aligning) {
			final RailEntry entry = rails.get(rail);
			return entry != null && (entry.now.stream().anyMatch(occupant -> occupant != train.occupant) || entry.before.stream().anyMatch(occupant -> occupant != train.occupant));
		}
		if (quietTrain == train) {
			final RailEntry entry = getEntry(rail);
			if (!entry.watchers.contains(train)) {
				entry.watchers.add(train);
				train.watched.add(entry);
			}
			return !entry.before.isEmpty() && entry.before.get(0) != train.occupant;
		}
		final RailEntry entry = rails.get(rail);
		return entry != null && (!entry.before.isEmpty() && entry.before.get(0) != train.occupant || !entry.now.isEmpty() && entry.now.get(0).compareTo(train.occupant) < 0);
	}

	/**
	 * Sends out one train of the depot that is ready, for the first service of the timetable no train has taken, as the
	 * game's depot does. A depot running by duties sends the train of the duty due first.
	 */
	private void deploy(DepotBoard depot) {
		if (skipping && !readyDepots.contains(depot) && !depot.changedWithPeers() && !depot.duties.diagramMode) {
			// The trains left alone that could leave can't yet, and no service was taken or let go meanwhile
			depot.dispatcher.startTick();
			return;
		}
		// Every train that could leave, the ones left alone as well
		final List<SidingRun> ready = new ArrayList<>();
		for (final SidingRun sidingRun : depotSidings.get(depot)) {
			if (sidingRun.tickMillis == millis) {
				for (int i = 0; i < sidingRun.readyCount; i++) {
					ready.add(sidingRun);
				}
			} else {
				sidingRun.waitingTrain = null;
				for (final HeadlessTrain train : sidingRun.trains) {
					if (train.requestedDeploy) {
						sidingRun.waitingTrain = train;
						ready.add(sidingRun);
					}
				}
			}
		}
		deploy(depot, ready);
	}

	private void deploy(DepotBoard depot, List<SidingRun> ready) {
		depot.dispatcher.startTick();
		if (depot.duties.diagramMode) {
			final Map<Long, HeadlessTrain> readyTrains = new TreeMap<>();
			ready.forEach(sidingRun -> readyTrains.put(sidingRun.sidingId, sidingRun.waitingTrain));
			final DepotDispatcher.DutyDeploy<T> deploy = depot.dispatcher.findDutyDeploy(millis, readyTrains.keySet(), depot.duties, sidingId -> sidings.containsKey(sidingId) ? sidings.get(sidingId).siding.tripPaths : null, routes, sidingId -> sidings.containsKey(sidingId) ? getPoolKey(sidings.get(sidingId)) : sidingId);
			if (deploy != null) {
				final HeadlessTrain train = readyTrains.get(deploy.sidingId);
				train.catchUp(millis);
				train.deployDuty(deploy.slot, deploy.dutySidingId, deploy.dayStart, deploy.path);
				if (deploy.late) {
					train.joinDutyLate();
				}
				depot.changed = true;
				wake(train);
			}
			return;
		}
		// A depot of trains sends them out by duties only (R2)
		if (depot.dutiesOnly) {
			return;
		}
		if (depot.sidingOrder.isEmpty()) {
			for (final SidingRun sidingRun : ready) {
				if (deployTrain(depot, sidingRun, false)) {
					break;
				}
			}
			return;
		}
		// The game's depot goes through its sidings from where it left off, the last train ready in each
		final Map<Long, SidingRun> readySidings = new HashMap<>();
		ready.forEach(sidingRun -> readySidings.put(sidingRun.sidingId, sidingRun));
		final int size = depot.sidingOrder.size();
		for (int i = depot.deployIndex; i < depot.deployIndex + size; i++) {
			final SidingRun sidingRun = readySidings.get(depot.sidingOrder.get(i % size));
			if (sidingRun != null && deployTrain(depot, sidingRun, true)) {
				break;
			}
		}
	}

	/**
	 * Sends out the siding's train that is ready, for the first service of the timetable no train has taken, or by
	 * frequency when the depot's next departure is due.
	 *
	 * @param inTurn whether the depot takes its sidings in turn, as the game's does
	 * @return whether it was sent out
	 */
	private boolean deployTrain(DepotBoard depot, SidingRun sidingRun, boolean inTurn) {
		final HeadlessTrain train = sidingRun.waitingTrain;
		final RouteTimetable timetable = depot.getTimetable(sidingRun.siding.trainId);
		// A siding whose path runs over a track gone from the network sends no more trains
		if (train == null || timetable == null && depot.departures.isEmpty() || train.findRemovedRail() >= 0) {
			return false;
		}
		final PlanRoute firstRoute = timetable == null ? null : depot.getTrainRoutes(sidingRun.siding.trainId).get(0);
		final long tripMillis = timetable == null ? 0 : train.service.findDeployService(depot.dispatcher.getLastDeployedTrip(firstRoute.getRouteId()));
		if (timetable == null ? DepotDispatcher.getMillisUntilDeploy(depot.departures, depot.lastDeployedMillis, millis, 1) != 0 : tripMillis == 0) {
			return false;
		}
		train.catchUp(millis);
		depot.lastDeployedMillis = millis;
		if (inTurn) {
			depot.deployIndex = depot.deployIndex + 1 >= depot.sidingOrder.size() ? 0 : depot.deployIndex + 1;
		}
		if (timetable != null) {
			depot.dispatcher.lastDeployedTrips.put(firstRoute.getRouteId(), tripMillis);
			depot.reserveTrip(firstRoute.getRouteId(), tripMillis);
			train.service.deploy(tripMillis, firstRoute.getRouteId());
		}
		train.canDeploy = true;
		depot.changed = true;
		wake(train);
		return true;
	}

	public interface TrainListener<T extends RunTrack> {

		void onEvent(MotionEvent event, TickRunner<T>.HeadlessTrain train);
	}

	/**
	 * Where something comes in the order things take rails in each tick: rails taken from outside first, then trains by
	 * siding and, as a siding keeps its trains in the order it made them, by ID.
	 */
	private static final class Occupant implements Comparable<Occupant> {

		private final long sidingId;
		private final long trainId;

		private static final Occupant FIRST = new Occupant(Long.MIN_VALUE, Long.MIN_VALUE);
		private static final Occupant EXTERNAL = new Occupant(Long.MIN_VALUE, EXTERNAL_OCCUPANT);
		private static final Occupant LAST = new Occupant(Long.MAX_VALUE, Long.MAX_VALUE);

		private Occupant(long sidingId, long trainId) {
			this.sidingId = sidingId;
			this.trainId = trainId;
		}

		@Override
		public int compareTo(Occupant occupant) {
			final int bySiding = Long.compare(sidingId, occupant.sidingId);
			return bySiding != 0 ? bySiding : Long.compare(trainId, occupant.trainId);
		}
	}

	/**
	 * A rail, what has it and who watches it.
	 */
	private final class RailEntry {

		/**
		 * What has the rail now, in order, so the first is the one {@link Occupancy} would have.
		 */
		private final List<Occupant> now = new ArrayList<>(2);
		/**
		 * What had it at the end of the last tick.
		 */
		private List<Occupant> before = Collections.emptyList();
		/**
		 * Trains left alone that looked at the rail, which are woken when what has it changes.
		 */
		private final List<HeadlessTrain> watchers = new ArrayList<>(1);
		private boolean changed;
	}

	/**
	 * What a siding gives its trains: its usual path, the paths of services leaving it and the trains it makes.
	 */
	public static final class Siding<T extends RunTrack> {

		public final List<T> path;
		public final List<Double> distances = new ArrayList<>();
		public final int repeatIndex1;
		public final int repeatIndex2;
		public final StopTimes stopTimes;
		public final Map<String, TripPath<T>> tripPaths = new HashMap<>();
		public TurnBacks turnBacks = TurnBacks.NONE;
		public final String trainId;
		public final int trainCars;
		public final int spacing;
		public final float railLength;
		public final float acceleration;
		public final float deceleration;
		public final int maxTrains;
		public boolean usesSpeedCurve = true;
		/**
		 * Whether its trains may be driven by hand, which makes them stand in the siding until the host drives them out.
		 */
		public boolean manual;
		/**
		 * The highest speed limit of the path, for how far ahead a train may have to look.
		 */
		public final float maxSpeed;
		/**
		 * The lowest cruising speed of the kind of vehicle, in blocks per tick.
		 */
		public float minSpeed = 0.5F;
		/**
		 * What the depot's sidings whose trains can run each other's duties have in common, or empty for a pool of its own
		 * (decoupling document, step D, depots sending trains out 4 and 5).
		 */
		public String poolKey = "";
		/**
		 * The pool across the depots of its operating line, or empty (see {@link SimDataset.SidingData#linePool}).
		 */
		public String linePool = "";
		/**
		 * The trains its pool has in all (P2-C), the same for each siding of the pool, or -1 for as many as its duties need;
		 * {@link #maxTrains} is then how many the siding holds.
		 */
		public int poolFleet = -1;

		public Siding(List<T> path, int repeatIndex1, int repeatIndex2, StopTimes stopTimes, String trainId, int trainCars, int spacing, float railLength, float acceleration, float deceleration, int maxTrains) {
			this.path = path;
			double distance = 0;
			float highestSpeed = 0;
			for (final T track : path) {
				distance += track.getTrackLength();
				distances.add(distance);
				highestSpeed = Math.max(highestSpeed, track.getMaxSpeed());
			}
			maxSpeed = highestSpeed;
			this.repeatIndex1 = repeatIndex1;
			this.repeatIndex2 = repeatIndex2;
			this.stopTimes = stopTimes;
			this.trainId = trainId;
			this.trainCars = trainCars;
			this.spacing = spacing;
			this.railLength = railLength;
			this.acceleration = acceleration;
			this.deceleration = deceleration;
			this.maxTrains = maxTrains;
		}
	}

	/**
	 * A siding and its trains as they run.
	 */
	public final class SidingRun {

		public final long sidingId;
		public final DepotBoard depot;
		public final Siding<T> siding;
		private final List<HeadlessTrain> trains = new ArrayList<>();
		/**
		 * The trains of its pool kept in the siding beyond the one standing there, oldest first (P2-C), and the ones back in
		 * this tick, kept from the next.
		 */
		private final ArrayDeque<Long> shed = new ArrayDeque<>();
		private final List<Long> arriving = new ArrayList<>();
		private HeadlessTrain waitingTrain;
		/**
		 * How many of its trains asked to leave in its last tick.
		 */
		private int readyCount;
		/**
		 * The next tick in which one of its trains moves or it may make or clear away a train.
		 */
		private long dueMillis;
		private long tickMillis;

		private SidingRun(long sidingId, DepotBoard depot, Siding<T> siding) {
			this.sidingId = sidingId;
			this.depot = depot;
			this.siding = siding;
			dueMillis = millis + TICK;
		}

		private void tick() {
			tickMillis = millis;
			waitingTrain = null;
			readyCount = 0;
			int trainsAtDepot = 0;
			boolean spawnTrain = true;
			final List<HeadlessTrain> trainsToRemove = new ArrayList<>();
			for (final HeadlessTrain train : trains) {
				tickPosition = train.occupant;
				// A train left alone asks to leave as it did when it last moved; one taken over doesn't move at all
				if (!train.takenOver && (!skipping || train.dueMillis <= millis)) {
					train.move();
					if (train.requestedDeploy) {
						readyDepots.add(depot);
					}
				}
				if (train.requestedDeploy) {
					waitingTrain = train;
					readyCount++;
				}
				if (!train.onRoute || train.railProgress < siding.spacing * siding.trainCars + siding.railLength) {
					spawnTrain = false;
				}
				// Back in another siding of the pool, the same train is kept in its shed (P2-C)
				final long returnedTo = train.service.takeReturnedElsewhereSiding();
				if (returnedTo != 0) {
					trainsToRemove.add(train);
					final SidingRun target = sidings.get(returnedTo);
					if (target != null) {
						target.arriving.add(train.id);
						shedIds.add(train.id);
					}
				} else if (!train.onRoute && ++trainsAtDepot > 1) {
					trainsToRemove.add(train);
					// By duties, a second train standing here is kept in the shed rather than taken away
					if (depot.duties.diagramMode || depot.dutiesOnly) {
						arriving.add(train.id);
						shedIds.add(train.id);
					}
				}
			}
			movingTrain = null;
			for (final HeadlessTrain train : trainsToRemove) {
				trains.remove(train);
				trainsById.remove(train.id);
				train.removed = true;
				removedTrains.add(train);
				standingDrops.add(train);
			}
			// By duties, the trains are the pool's, across the sidings of the other depots of its line too, as trains go back to
			// those (P2-C): a siding with none standing takes the one kept longest in its shed, and a new train is made only
			// while the pool has fewer than it has in all, or than its duties need when that is not set
			// A depot of trains has its sidings by duties only (R3), even before it has duties
			if (depot.duties.diagramMode || depot.dutiesOnly) {
				if (trains.isEmpty() || spawnTrain) {
					final long keptId = shed.isEmpty() ? 0 : shed.removeFirst();
					if (keptId != 0) {
						shedIds.remove(keptId);
						addStanding(keptId);
					} else {
						final int poolFleet = getPoolFleet(this);
						int poolSlots = 0;
						if (poolFleet < 0) {
							for (final SidingRun poolSiding : getLinePool(this)) {
								poolSlots += poolSiding.depot.duties.getSlots(poolSiding.sidingId).size();
							}
						}
						final int poolTrains = getPoolCount(this);
						final boolean roomHere = siding.maxTrains >= SimDataset.UNLIMITED_TRAINS || getHeld() <= siding.maxTrains;
						// As many as needed: as many as its duties, each siding with one standing at least
						if (poolFleet >= 0 ? poolTrains < poolFleet && roomHere : trains.isEmpty() || poolTrains < poolSlots && trains.size() <= siding.maxTrains) {
							addStanding(newTrainId());
						}
					}
				}
			} else if (trains.isEmpty() || spawnTrain && trains.size() <= siding.maxTrains) {
				addStanding(newTrainId());
			}
		}

		private void addStanding(long id) {
			final HeadlessTrain train = new HeadlessTrain(id, this);
			trains.add(train);
			trainsById.put(train.id, train);
		}

		/**
		 * @return the trains standing in the siding and kept in its shed, the ones coming back in this tick among them
		 */
		private int getHeld() {
			int standing = 0;
			for (final HeadlessTrain train : trains) {
				if (!train.onRoute) {
					standing++;
				}
			}
			return standing + shed.size() + arriving.size();
		}

		/**
		 * Works out when the siding and the trains moved in its tick are next due.
		 */
		private void endTick() {
			final boolean everyTrain = needsEveryTrain();
			long nextMillis = everyTrain ? millis + TICK : Long.MAX_VALUE;
			for (final HeadlessTrain train : trains) {
				if (train.movedMillis == millis) {
					train.updateDue();
				}
				if (everyTrain && !train.takenOver) {
					train.dueMillis = Math.min(train.dueMillis, millis + TICK);
				}
				nextMillis = Math.min(nextMillis, train.dueMillis);
			}
			dueMillis = nextMillis;
		}

		/**
		 * @return true if all its trains move in the next tick: trains by duties, a siding about to make or clear away a
		 * train, or two trains in the same place, which change at once
		 */
		private boolean needsEveryTrain() {
			if (!skipping || depot.duties.diagramMode || trains.isEmpty()) {
				return true;
			}
			int trainsAtDepot = 0;
			boolean spawnTrain = true;
			final Set<Long> railProgressSet = new HashSet<>();
			for (final HeadlessTrain train : trains) {
				if (!train.onRoute || train.railProgress < siding.spacing * siding.trainCars + siding.railLength) {
					spawnTrain = false;
				}
				if (!train.onRoute && ++trainsAtDepot > 1 || !railProgressSet.add(Math.round(train.railProgress * 10))) {
					return true;
				}
			}
			return spawnTrain && trains.size() <= siding.maxTrains;
		}
	}

	/**
	 * One train, standing in for the game's server train: the state it keeps and the plumbing between the motion rules,
	 * the stop rules and its service. Every decision is the shared rules'.
	 */
	public final class HeadlessTrain implements MotionState, MotionTrack, MotionVehicle, MotionHooks, MotionCurves, TrainRules.Train, TrainService.Vehicle<T> {

		public final long id;
		public final TrainService<T> service;
		private final SidingRun sidingRun;
		private final Siding<T> siding;
		private List<T> path;
		private ThroatLocks throatLocks;
		/**
		 * What the train's run request asks for, asked again as it is when the host ticks without the runner.
		 */
		private List<RunReservations.Move> requestedMoves = Collections.emptyList();
		private List<Double> distances;
		private int pathRepeatIndex1;
		private int pathRepeatIndex2;
		private int repeatIndex1;
		private int repeatIndex2;
		private float speed;
		private double railProgress;
		private boolean doorTarget;
		private float doorValue;
		private float elapsedDwellTicks;
		private int nextStoppingIndex;
		private int nextPlatformIndex;
		private boolean reversed;
		private boolean onRoute;
		private int manualNotch;
		private RunSegment runSegment;
		private boolean canDeploy;
		private boolean requestedDeploy;
		private float cruiseCap = Float.POSITIVE_INFINITY;
		private List<UUID> turnBackRails = Collections.emptyList();
		private int turnBackUntil;
		private int grantedRunEnd = -1;
		private int requestedRunEnd = -1;
		private long eventSequence;
		/**
		 * Why the host holds the train at its stops, or null.
		 */
		private String hostHold;
		private SpeedCurves curves;
		private List<T> curvesPath;
		private SpeedCurves.Run cappedRun;
		private float cappedCap;
		private SpeedProfile cappedProfile;
		private final Occupant occupant;
		/**
		 * The time its state is for, which for a train left alone is earlier than now.
		 */
		private long syncedMillis;
		/**
		 * The next tick it moves in, and the last one it moved in.
		 */
		private long dueMillis;
		private long movedMillis;
		private boolean removed;
		/**
		 * Whether the host has taken the train over, so it doesn't move (G0).
		 */
		private boolean takenOver;
		/**
		 * The rails it has, and the ones it looked at while working out how long it can be left alone.
		 */
		private List<UUID> heldRails = Collections.emptyList();
		private final List<RailEntry> watched = new ArrayList<>();
		/**
		 * The rails of the signal blocks it is in, which it takes or not depending on who else has them, so it is woken
		 * when they change.
		 */
		private List<UUID> blockWatch = Collections.emptyList();
		private int blockedRail = -1;
		/**
		 * The services it reserved in its last tick, and the ones kept reserved for it while it is left alone.
		 */
		private List<Long> recordedTrips = Collections.emptyList();
		private List<Long> standingTrips = Collections.emptyList();
		/**
		 * What it goes by while it stands at a stop and is left alone.
		 */
		private Standing standing;

		private HeadlessTrain(long id, SidingRun sidingRun) {
			this.id = id;
			this.sidingRun = sidingRun;
			occupant = new Occupant(sidingRun.sidingId, id);
			syncedMillis = millis;
			dueMillis = millis + TICK;
			siding = sidingRun.siding;
			path = siding.path;
			distances = siding.distances;
			pathRepeatIndex1 = siding.repeatIndex1;
			pathRepeatIndex2 = siding.repeatIndex2;
			repeatIndex1 = siding.repeatIndex1;
			repeatIndex2 = siding.repeatIndex2;
			service = new TrainService<>(siding.trainId, this, () -> millis);
			service.setSiding(sidingRun.sidingId);
			service.setNetwork(sidingRun.depot, routes, null);
		}

		public boolean isTakenOver() {
			return takenOver;
		}

		private RunRecord.TrainRecord toRecord() {
			final BinaryKeyed.Writer values = new BinaryKeyed.Writer();
			try {
				service.write(values);
			} catch (java.io.IOException e) {
				throw new IllegalStateException(e);
			}
			final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
			values.writeTo(writer);
			return new RunRecord.TrainRecord(sidingRun.sidingId, id, speed, railProgress, elapsedDwellTicks, nextStoppingIndex, nextPlatformIndex, reversed, onRoute, writer.toBytes(), cruiseCap);
		}

		/**
		 * Takes the state the game saves of a train, the way the game reads it back.
		 *
		 * @return false if the path of its service is gone
		 */
		private boolean restore(RunRecord.TrainRecord trainRecord) {
			speed = trainRecord.speed;
			railProgress = trainRecord.railProgress;
			elapsedDwellTicks = trainRecord.elapsedDwellTicks;
			nextStoppingIndex = trainRecord.nextStoppingIndex;
			nextPlatformIndex = trainRecord.nextPlatformIndex;
			reversed = trainRecord.reversed;
			onRoute = trainRecord.onRoute;
			service.read(new BinaryKeyed.Reader(new ByteArrayBinary.Reader(trainRecord.service)));
			service.setStopTimes(siding.stopTimes, siding.tripPaths, siding.turnBacks);
			if (!service.restoreTripPath(siding.tripPaths) || nextStoppingIndex >= path.size()) {
				return false;
			}
			if (!Float.isNaN(trainRecord.cruiseCap)) {
				cruiseCap = trainRecord.cruiseCap;
			} else if (onRoute && speed > 0) {
				// Saved without it, a train on its way works it out again from where it is, which is close
				cruiseCap = TrainRules.getCruiseCap(this);
			}
			return true;
		}

		/**
		 * Puts the train somewhere along its path at a speed, as the host gives it back: its next stop, cruising cap and run
		 * are worked out afresh from there, and the runs it held are asked for again.
		 */
		private void placeAt(double newRailProgress, float newSpeed) {
			railProgress = newRailProgress;
			speed = Math.max(0, newSpeed);
			onRoute = true;
			runSegment = null;
			elapsedDwellTicks = 0;
			doorTarget = false;
			doorValue = 0;
			manualNotch = speed > 0 ? 0 : -2;
			grantedRunEnd = -1;
			requestedRunEnd = -1;
			nextStoppingIndex = TrainRules.findNextStoppingIndex(this, MotionConstants.findIndex(this, railProgress, false));
			nextPlatformIndex = nextStoppingIndex;
			// A stopped train counts as standing at its stop, so one given back anywhere else sets off as it does from a stop
			if (speed <= 0 && Math.abs(distances.get(nextStoppingIndex) - railProgress) > 1E-3) {
				speed = MotionConstants.ACCELERATION_DEFAULT;
			}
			cruiseCap = TrainRules.getCruiseCap(this);
			syncedMillis = millis;
			dueMillis = millis + TICK;
		}

		/**
		 * Keeps the train at its next stop with its doors open, or lets it go again with null.
		 */
		public void setHostHold(String reason) {
			hostHold = reason;
		}

		public String getHostHold() {
			return hostHold;
		}

		/**
		 * @return the rail of the path the head is on, which for a train standing at the end of a rail is that rail
		 */
		public int getHeadRail() {
			return path.isEmpty() ? 0 : Math.max(0, MotionConstants.findIndex(this, railProgress, true));
		}

		/**
		 * Moves the train through this tick, first catching up on the ticks it was left alone.
		 */
		private void move() {
			catchUp(millis - TICK);
			movingTrain = this;
			dueMillis = Long.MAX_VALUE;
			movedMillis = millis;
			trainTicks++;
			service.setStopTimes(siding.stopTimes, siding.tripPaths, siding.turnBacks);
			simulate();
			syncedMillis = millis;
			writePositions();
			movedTrains.add(this);
			if (!standingTrips.isEmpty()) {
				standingDrops.add(this);
			}
		}

		/**
		 * Brings a train left alone on to this time, as if it had moved through every tick: it only went along its segment
		 * or stood.
		 */
		private void catchUp(long toMillis) {
			if (takenOver) {
				syncedMillis = toMillis;
			} else if (toMillis > syncedMillis) {
				// The rules count from the clock as it was at the end of the ticks left out
				final long currentMillis = millis;
				millis = toMillis;
				skip((toMillis - syncedMillis) / TICK);
				millis = currentMillis;
				syncedMillis = toMillis;
			}
		}

		private void simulate() {
			requestedDeploy = false;
			blockedRail = -1;
			service.updateOvernight();
			TrainMotion.tick(this, this, this, this, this, 1);
			holdRun();
			// What it reserves now it reserves again in every tick it is left alone
			final List<Long> trips = new ArrayList<>(2);
			sidingRun.depot.record(trips);
			service.update(onRoute);
			sidingRun.depot.record(null);
			if (!trips.equals(recordedTrips)) {
				recordedTrips = trips;
				sidingRun.depot.changed = true;
			}
		}

		private void writePositions() {
			if (path.isEmpty()) {
				return;
			}
			final List<UUID> newRails = new ArrayList<>(2);
			final int headIndex = MotionConstants.findIndex(this, railProgress, true);
			final int tailIndex = MotionConstants.findIndex(this, railProgress - siding.trainCars * siding.spacing, false);
			List<UUID> newBlockWatch = Collections.emptyList();
			final double tailPosition = railProgress - siding.trainCars * siding.spacing;
			for (int i = tailIndex; i <= headIndex; i++) {
				final T track = path.get(i);
				if (i > 0 && track.getStopId() != sidingRun.sidingId && track.hasSignal()) {
					// The block sections of the rail the train is on: the whole rail, or the pieces of a long one it covers
					for (final UUID rail : getCoveredUnits(i, tailPosition, railProgress)) {
						if (!newRails.contains(rail)) {
							newRails.add(rail);
						}
						// The whole section of its signal block or nothing more, as the game's signals have it
						final List<UUID> block = getBlockSections().getSection(rail);
						if (block != null) {
							if (newBlockWatch.isEmpty()) {
								newBlockWatch = new ArrayList<>();
							}
							newBlockWatch.addAll(block);
							if (block.stream().noneMatch(blockRail -> isTakenByOther(blockRail, this))) {
								block.forEach(blockRail -> {
									if (!newRails.contains(blockRail)) {
										newRails.add(blockRail);
									}
								});
							}
						}
					}
				}
			}
			blockWatch = newBlockWatch;
			if (!turnBackRails.isEmpty()) {
				if (!onRoute || tailIndex >= turnBackUntil) {
					turnBackRails = Collections.emptyList();
				} else {
					turnBackRails.forEach(rail -> {
						if (!newRails.contains(rail)) {
							newRails.add(rail);
						}
					});
				}
			}
			if (!newRails.equals(heldRails)) {
				heldRails.forEach(rail -> {
					if (!newRails.contains(rail)) {
						removeOccupant(rail, occupant);
					}
				});
				newRails.forEach(rail -> {
					if (!heldRails.contains(rail)) {
						addOccupant(rail, occupant);
					}
				});
				heldRails = newRails;
			}
		}

		/**
		 * Lets go of its rails and stops watching, once it is taken away.
		 */
		private void dropRails() {
			heldRails.forEach(rail -> removeOccupant(rail, occupant));
			heldRails = Collections.emptyList();
			unwatch();
		}

		private void unwatch() {
			watched.forEach(entry -> entry.watchers.remove(this));
			watched.clear();
		}

		private void dropStandingTrips() {
			sidingRun.depot.dispatcher.removeStandingTrips(standingTrips);
			standingTrips = Collections.emptyList();
		}

		/**
		 * Works out, at the end of a tick it moved in, the next tick it has to move in, and keeps its services reserved
		 * until then.
		 */
		private void updateDue() {
			unwatch();
			standing = null;
			long quiet = 0;
			if (skipping) {
				quietTrain = this;
				quiet = getQuietTicks();
				quietTrain = null;
				blockWatch.forEach(rail -> {
					final RailEntry entry = getEntry(rail);
					if (!entry.watchers.contains(this)) {
						entry.watchers.add(this);
						watched.add(entry);
					}
				});
			}
			dueMillis = Math.min(dueMillis, quiet >= (Long.MAX_VALUE - millis) / TICK - 1 ? Long.MAX_VALUE : millis + (quiet + 1) * TICK);
			if (dueMillis > millis + TICK && !recordedTrips.isEmpty()) {
				standingTrips = recordedTrips;
				sidingRun.depot.dispatcher.addStandingTrips(standingTrips);
			}
		}

		/**
		 * @return how many of the next ticks change nothing for this train but where it is along its segment, or how long it
		 * has stood, or 0
		 */
		private long getQuietTicks() {
			// A new train gets its stop times in its first tick, and works out when to leave from them
			if (!siding.usesSpeedCurve || path.isEmpty() || service.isOnDuty() || sidingRun.depot.duties.diagramMode || service.getStopTimes() == null) {
				return 0;
			}
			final int trainLength = siding.trainCars * siding.spacing;
			if (!onRoute) {
				if (canDeploy) {
					return 0;
				}
				// A train driven by hand only leaves when the host drives it
				if (siding.manual) {
					return Long.MAX_VALUE;
				}
				if (path.size() > 1 && !isRailOccupied(1) && sidingRun.depot.getTimetable(siding.trainId) == null) {
					// Sent out by frequency, which is once the depot's next departure has passed
					final int untilMillis = sidingRun.depot.departures.isEmpty() ? -1 : DepotDispatcher.getMillisUntilDeploy(sidingRun.depot.departures, sidingRun.depot.lastDeployedMillis, millis, 1);
					return untilMillis < 0 ? Long.MAX_VALUE : Math.max(0, untilMillis / MotionConstants.MILLIS_PER_TICK - 1);
				}
				if (path.size() > 1 && !isRailOccupied(1)) {
					// Waiting to be sent out, which can only be once a service comes near enough
					final List<PlanRoute> depotRoutes = sidingRun.depot.getTrainRoutes(siding.trainId);
					if (depotRoutes.isEmpty()) {
						return Long.MAX_VALUE;
					}
					final long nextDeployMillis = service.getNextDeployMillis(sidingRun.depot.dispatcher.getLastDeployedTrip(depotRoutes.get(0).getRouteId()));
					return nextDeployMillis == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, (nextDeployMillis - millis) / MotionConstants.MILLIS_PER_TICK - 1);
				}
				return Long.MAX_VALUE;
			}
			if (nextStoppingIndex >= path.size()) {
				return 0;
			}
			if (railProgress >= distances.get(distances.size() - 1) - (siding.railLength - trainLength) / 2 && !keepsOnLineAtEnd()) {
				return 0;
			}
			if (speed <= 0) {
				return getStandingQuietTicks();
			}
			return doorValue != (doorTarget ? 1 : 0) ? 0 : getMovingQuietTicks(trainLength);
		}

		private long getStandingQuietTicks() {
			standing = new Standing();
			if (getTotalDwellTicks() == 0) {
				// Stopped short of a taken rail, which stays taken until something moves off it
				return standing.blocked && !doorTarget ? Long.MAX_VALUE : 0;
			}
			if (isRepeat()) {
				return 0;
			}
			float elapsed = elapsedDwellTicks;
			for (long ticks = 1; ticks <= MAX_STANDING_TICKS; ticks++) {
				elapsed = standing.step(elapsed, millis + ticks * TICK);
				if (elapsed < 0) {
					return ticks - 1;
				}
			}
			return MAX_STANDING_TICKS;
		}

		private long getMovingQuietTicks(int trainLength) {
			final RunSegment segment = runSegment;
			if (segment == null || segment.isFinished() || nextStoppingIndex != nextPlatformIndex || isRepeat()) {
				return 0;
			}
			final int headIndex = MotionConstants.findIndex(this, railProgress, false);
			final int tailIndex = MotionConstants.findIndex(this, railProgress - trainLength, false);
			// Head and tail stay on their rails, the train stays clear of the siding and of where it stops
			double bound = Math.min(getNextSectionStart(headIndex, railProgress), distances.get(nextStoppingIndex));
			if (railProgress - trainLength >= 0) {
				bound = Math.min(bound, getNextSectionStart(tailIndex, railProgress - trainLength) + trainLength);
			}
			final double depotThreshold = siding.spacing * siding.trainCars + siding.railLength;
			if (railProgress < depotThreshold) {
				bound = Math.min(bound, depotThreshold);
			}
			// A train goes back into its siding before the end of the last rail
			if (!keepsOnLineAtEnd()) {
				bound = Math.min(bound, distances.get(distances.size() - 1) - (siding.railLength - trainLength) / 2);
			}
			bound -= 1E-3;
			long low = 0;
			long high = Math.max(0, (long) Math.floor(segment.getDuration() - segment.getElapsedTicks()) - 2);
			while (low < high) {
				final long middle = (low + high + 1) >>> 1;
				if (segment.getPositionAfter(middle) < bound) {
					low = middle;
				} else {
					high = middle - 1;
				}
			}
			if (low <= 0) {
				return 0;
			}
			// Nothing within reach of braking on the way is taken, so the train won't have to stop short
			final double reach = segment.getPositionAfter(low) + 0.5 * siding.maxSpeed * siding.maxSpeed / siding.deceleration + siding.maxSpeed + MotionConstants.BRAKING_MARGIN;
			final int lastCheckIndex = nextPlatformIndex > 0 ? Math.min(nextPlatformIndex, path.size() - 1) : path.size() - 1;
			// A taken section ahead on the rail the head is on
			if (!Double.isNaN(getSectionBlockedPosition(headIndex, railProgress))) {
				return 0;
			}
			// The next rail always counts, however far it starts, as the train's own look ahead has it
			final int nextRail = MotionConstants.findIndex(this, railProgress, true) + 1;
			for (int i = nextRail; i == nextRail || i <= lastCheckIndex && distances.get(i - 1) <= reach; i++) {
				if (isRailOccupied(i)) {
					return 0;
				}
			}
			return low;
		}

		private void skip(long ticks) {
			if (!onRoute) {
				return;
			}
			if (speed <= 0) {
				if (getTotalDwellTicks() > 0) {
					// As it stood in each of the ticks; the clock is at the end of them
					for (long tick = 1; tick <= ticks; tick++) {
						elapsedDwellTicks = standing.step(elapsedDwellTicks, millis - (ticks - tick) * TICK);
						if (elapsedDwellTicks < 0) {
							throw new IllegalStateException("Train " + id + " was left alone through a tick that changed it");
						}
					}
				}
				// The doors slide a sixty-fourth in every tick, which adds up the same in one go
				if (doorValue != (doorTarget ? 1 : 0)) {
					final float step = (float) (doorTarget ? 1 : -1) / MotionConstants.DOOR_MOVE_TIME;
					if (doorTarget || MotionConstants.clamp(doorValue + step, 0, 1) != 0) {
						manualNotch = -2;
					}
					doorValue = MotionConstants.clamp(doorValue + step * ticks, 0, 1);
				}
				return;
			}
			final float beforeSpeed = (float) runSegment.getSpeedAfter(ticks - 1);
			runSegment.advance(ticks);
			railProgress = runSegment.getPosition();
			final float lastSpeed = (float) runSegment.getSpeed();
			// The notch of the last tick, which compares with the speed the tick before
			manualNotch = lastSpeed > beforeSpeed ? 2 : 0;
			speed = lastSpeed;
		}

		/**
		 * What a train standing at a stop goes by, which stays the same while it stands: its stop time, its planned
		 * departure, what holds it and whether the way on is taken (S4f). From these it goes on from tick to tick by the same
		 * rules {@link TrainMotion} and {@link TrainRules} have, much quicker than moving it.
		 */
		private final class Standing {

			private final int dwellTicks;
			private final boolean planned;
			private final long departureMillis;
			private final boolean timed;
			private final boolean atPlatform;
			private final boolean blocked;
			private final boolean hostHeld;
			/**
			 * Whether asking if the train is held changes nothing and only depends on the clock.
			 */
			private final boolean heldByClock;

			private Standing() {
				dwellTicks = path.get(nextStoppingIndex).getDwellTime() * 10;
				final RouteTimetable.StopPlan stopPlan = service.getPlanAt(nextStoppingIndex);
				planned = stopPlan != null;
				departureMillis = planned ? service.getPlannedMillis(stopPlan.departureMillis) : 0;
				timed = planned && stopPlan.isTimed;
				atPlatform = getStopId(nextStoppingIndex) != 0;
				final int headIndex = MotionConstants.findIndex(HeadlessTrain.this, railProgress, true);
				final boolean isOppositeRail = headIndex + 1 < path.size() && headIndex >= 0 && isOppositeRail(headIndex, headIndex + 1);
				// As the motion rules have it: only where it would move onto the next rail, or just ahead on its own
				final int nextRail = headIndex + (isOppositeRail ? 2 : 1);
				blocked = TrainMotion.isTakenAtStart(HeadlessTrain.this, HeadlessTrain.this, nextRail, isRailOccupied(nextRail)) || !isOppositeRail && TrainMotion.isSectionTakenJustAhead(HeadlessTrain.this, headIndex, railProgress);
				hostHeld = hostHold != null;
				// Overtaking trains aren't followed here, so only choosing a way on or a duty asks anything else
				heldByClock = hostHeld || !service.isStayingOnLine() && service.getDutyDepartureMillis() <= 0 && !service.isOnDuty() && !service.mayChooseWayAtStop();
			}

			private int getTotalDwellTicks(float elapsed, long atMillis) {
				return planned ? TrainRules.getTotalDwellTicks(dwellTicks, elapsed, departureMillis, atMillis) : dwellTicks;
			}

			/**
			 * One tick of {@link TrainMotion} for a train standing at its stop.
			 *
			 * @return the time stood after the tick at this time, or -1 if the train has to move through it: its doors open or
			 * close, it leaves, or it asks something that can change more than the time it has stood
			 */
			private float step(float elapsed, long atMillis) {
				final int totalDwellTicks = getTotalDwellTicks(elapsed, atMillis);
				if (elapsed == 0) {
					return -1;
				}
				final boolean beforeHolding = elapsed < totalDwellTicks - MotionConstants.DOOR_MOVE_TIME - MotionConstants.DOOR_DELAY - 1F;
				if (!beforeHolding && !heldByClock) {
					return -1;
				}
				final float newElapsed = beforeHolding || !(hostHeld || timed && TrainRules.isWaitingForDeparture(departureMillis, atMillis)) && !blocked ? elapsed + 1F : elapsed;
				final boolean doorsOpen = atPlatform && TrainRules.areDoorsOpen(newElapsed, getTotalDwellTicks(newElapsed, atMillis));
				return doorsOpen != doorTarget || newElapsed >= totalDwellTicks && !blocked ? -1 : newElapsed;
			}
		}

		private boolean isRailOccupied(int index) {
			if (index >= path.size()) {
				return false;
			}
			final UUID rail = path.get(index).getTrackKey();
			if (removedTracks.contains(rail)) {
				return true;
			}
			for (final UUID unit : getCoveredUnits(index, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
				if (isTakenByOther(unit, this)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * @return the block sections of a rail of the path a part of the path covers: the rail's own key, or the pieces of a
		 * rail cut into block sections
		 */
		private List<UUID> getCoveredUnits(int index, double fromPosition, double toPosition) {
			final T track = path.get(index);
			final double start = index == 0 ? 0 : distances.get(index - 1);
			final double length = track.getTrackLength();
			return transitsim.network.BlockSections.getPieces(track.getTrackKey(), length, cutSpeed(track), track.getStartNode() < track.getEndNode(), Math.max(0, fromPosition - start), Math.min(length, toPosition - start));
		}

		@Override
		public double getSectionBlockedPosition(int rail, double fromPosition) {
			if (rail <= 0 || rail >= path.size() || !path.get(rail).hasSignal()) {
				return Double.NaN;
			}
			final T track = path.get(rail);
			final double start = distances.get(rail - 1);
			for (final Map.Entry<Double, UUID> piece : transitsim.network.BlockSections.getPieceStarts(track.getTrackKey(), track.getTrackLength(), cutSpeed(track), track.getStartNode() < track.getEndNode())) {
				final double pieceStart = start + piece.getKey();
				// Only sections from here on; the one the train is in is its own
				if (pieceStart >= fromPosition - 1E-6 && isTakenByOther(piece.getValue(), this)) {
					return pieceStart;
				}
			}
			return Double.NaN;
		}

		/**
		 * @return where on the path the next block section of this rail after this position starts, or the rail's end
		 */
		private double getNextSectionStart(int rail, double position) {
			final T track = path.get(rail);
			final double start = rail == 0 ? 0 : distances.get(rail - 1);
			for (final Map.Entry<Double, UUID> piece : transitsim.network.BlockSections.getPieceStarts(track.getTrackKey(), track.getTrackLength(), cutSpeed(track), track.getStartNode() < track.getEndNode())) {
				if (start + piece.getKey() > position + 1E-6) {
					return start + piece.getKey();
				}
			}
			return distances.get(rail);
		}

		/**
		 * @return true if the train stands on a track gone from the network, counted from tail to head the way what it takes
		 * is
		 */
		public boolean isOnRemovedTrack() {
			final int headIndex = MotionConstants.findIndex(this, railProgress, true);
			final int tailIndex = MotionConstants.findIndex(this, railProgress - siding.trainCars * siding.spacing, false);
			for (int i = Math.max(tailIndex, 0); i <= headIndex && i < path.size(); i++) {
				if (removedTracks.contains(path.get(i).getTrackKey())) {
					return true;
				}
			}
			return false;
		}

		/**
		 * @return the first rail of the path from the tail on whose track is gone from the network, or -1
		 */
		public int findRemovedRail() {
			final int tailIndex = Math.max(0, MotionConstants.findIndex(this, railProgress - siding.trainCars * siding.spacing, true));
			for (int i = onRoute ? tailIndex : 0; i < path.size(); i++) {
				if (removedTracks.contains(path.get(i).getTrackKey())) {
					return i;
				}
			}
			return -1;
		}

		private void holdRun() {
			if (!onRoute || !service.isOnDuty() || path.isEmpty()) {
				reservations.release(id);
				grantedRunEnd = -1;
				return;
			}
			if (grantedRunEnd < nextPlatformIndex) {
				grantedRunEnd = -1;
			}
			final List<RunReservations.Move> moves = getRunMoves(Math.max(TrainRules.findRunEnd(this, nextPlatformIndex), grantedRunEnd));
			final ThroatLocks locks = getThroatLocks();
			if (locks != null) {
				final int headIndex = Math.max(MotionConstants.findIndex(this, railProgress, true), 0);
				final int tailIndex = getTailIndex();
				// Not while waiting to leave a stop, whose request is the run's
				if (requestedRunEnd < 0) {
					locks.approach(reservations, id, moves, headIndex, tailIndex, index -> getDistance(index - 1) - railProgress, 0.5 * speed * speed / siding.deceleration, TrainRules.getPlannedDepartureMillis(this));
				}
				moves.addAll(locks.getHeld(tailIndex));
			}
			reservations.hold(id, moves);
		}

		private int getTailIndex() {
			return Math.max(MotionConstants.findIndex(this, railProgress - siding.trainCars * siding.spacing, true), 0);
		}

		/**
		 * @return the throat locks of the train's path, made again when the path changes, or null if they are off or the
		 * train isn't on duty
		 */
		private ThroatLocks getThroatLocks() {
			if (!ThroatLocks.enabled || !service.isOnDuty() || path.isEmpty() || !sidingRun.depot.throatLocksAllowed) {
				return null;
			}
			if (throatLocks == null || !throatLocks.isFor(path, path.size() + 1)) {
				final long[] nodes = new long[path.size() + 1];
				final List<Integer> stopRails = new ArrayList<>();
				for (int i = 0; i < path.size(); i++) {
					final T track = path.get(i);
					nodes[i] = track.getStartNode();
					if (track.isSidingTrack() || track.isPlatformTrack() && track.getDwellTime() > 0) {
						stopRails.add(i);
					}
				}
				nodes[path.size()] = path.get(path.size() - 1).getEndNode();
				final Map<Long, Set<Long>> neighbours = getNodeNeighbours();
				// Its own path may run where no siding's does, as on the way back to another siding
				path.forEach(track -> {
					neighbours.computeIfAbsent(track.getStartNode(), key -> new HashSet<>()).add(track.getEndNode());
					neighbours.computeIfAbsent(track.getEndNode(), key -> new HashSet<>()).add(track.getStartNode());
				});
				throatLocks = new ThroatLocks(path, nodes, stopRails.stream().mapToInt(Integer::intValue).toArray(), node -> neighbours.getOrDefault(node, Collections.emptySet()).size(), rail -> zoneOfRail.getOrDefault(rail, 0L));
			}
			return throatLocks;
		}

		/**
		 * See {@link TickRunner#renewReservations}.
		 */
		private void renewReservations() {
			if (takenOver || removed) {
				return;
			}
			final List<RunReservations.Move> held = reservations.getHeld(id);
			if (!held.isEmpty()) {
				reservations.hold(id, held);
			}
			if (requestedRunEnd >= 0) {
				reservations.request(id, requestedMoves.isEmpty() ? getRunMoves(requestedRunEnd) : requestedMoves, TrainRules.getPlannedDepartureMillis(this));
			}
		}

		private List<RunReservations.Move> getRunMoves(int runEnd) {
			final List<RunReservations.Move> moves = new ArrayList<>();
			final int tailIndex = Math.max(MotionConstants.findIndex(this, railProgress - siding.trainCars * siding.spacing, true), 0);
			for (int i = tailIndex; i <= Math.min(runEnd, path.size() - 1); i++) {
				final T track = path.get(i);
				if (track.isTwoWay()) {
					moves.add(new RunReservations.Move(track.getStartNode(), track.getEndNode()));
				}
			}
			return moves;
		}

		// TrainRules.Train

		@Override
		public long getMillis() {
			return millis;
		}

		@Override
		public TrainService<?> getService() {
			return service;
		}

		@Override
		public MotionState getState() {
			return this;
		}

		@Override
		public MotionTrack getTrack() {
			return this;
		}

		@Override
		public long getStopId(int rail) {
			return path.get(rail).getStopId();
		}

		@Override
		public boolean isContinuousMovement() {
			return false;
		}

		@Override
		public boolean usesSpeedCurve() {
			return siding.usesSpeedCurve;
		}

		@Override
		public SpeedCurves getSpeedCurves() {
			if (curves == null || curvesPath != path) {
				curves = SpeedCurves.of(RunTrack.toTrackView(path, siding.minSpeed), (siding.railLength + siding.trainCars * siding.spacing) / 2, siding.trainCars * siding.spacing, siding.acceleration, siding.deceleration);
				curvesPath = path;
				cappedProfile = null;
			}
			return curves;
		}

		@Override
		public boolean isOvertakingTrainComing(long platformId, long arrivalMillis, long departureMillis, long currentMillis) {
			return false;
		}

		@Override
		public boolean isRunGranted(int fromIndex) {
			final int runEnd = TrainRules.findRunEnd(this, fromIndex);
			if (grantedRunEnd == runEnd) {
				return true;
			}
			final ThroatLocks locks = getThroatLocks();
			if (requestedRunEnd == runEnd && reservations.takeGrant(id)) {
				grantedRunEnd = runEnd;
				requestedRunEnd = -1;
				requestedMoves = Collections.emptyList();
				if (locks != null) {
					locks.onGranted();
				}
				return true;
			}
			requestedRunEnd = runEnd;
			final List<RunReservations.Move> moves = getRunMoves(runEnd);
			if (locks != null) {
				// The way out through the throat of the stop it is leaving goes with the run, all of it or none
				final List<transitsim.network.ThroatRoutes.Item> exit = locks.getExit(fromIndex - 1);
				moves.addAll(locks.getHeld(getTailIndex()));
				moves.addAll(ThroatLocks.toMoves(exit));
				locks.setRequested(exit);
			}
			requestedMoves = moves;
			reservations.request(id, moves, TrainRules.getPlannedDepartureMillis(this));
			return false;
		}

		// MotionState

		@Override
		public float getSpeed() {
			return speed;
		}

		@Override
		public void setSpeed(float speed) {
			this.speed = speed;
		}

		@Override
		public double getRailProgress() {
			return railProgress;
		}

		@Override
		public void setRailProgress(double railProgress) {
			this.railProgress = railProgress;
		}

		@Override
		public boolean getDoorTarget() {
			return doorTarget;
		}

		@Override
		public void setDoorTarget(boolean doorTarget) {
			this.doorTarget = doorTarget;
		}

		@Override
		public float getDoorValue() {
			return doorValue;
		}

		@Override
		public void setDoorValue(float doorValue) {
			this.doorValue = doorValue;
		}

		@Override
		public float getElapsedDwellTicks() {
			return elapsedDwellTicks;
		}

		@Override
		public void setElapsedDwellTicks(float elapsedDwellTicks) {
			this.elapsedDwellTicks = elapsedDwellTicks;
		}

		@Override
		public int getNextStoppingIndex() {
			return nextStoppingIndex;
		}

		@Override
		public void setNextStoppingIndex(int nextStoppingIndex) {
			this.nextStoppingIndex = nextStoppingIndex;
		}

		@Override
		public int getNextPlatformIndex() {
			return nextPlatformIndex;
		}

		@Override
		public void setNextPlatformIndex(int nextPlatformIndex) {
			this.nextPlatformIndex = nextPlatformIndex;
		}

		@Override
		public boolean isReversed() {
			return reversed;
		}

		@Override
		public void setReversed(boolean reversed) {
			this.reversed = reversed;
		}

		@Override
		public boolean isOnRoute() {
			return onRoute;
		}

		@Override
		public void setOnRoute(boolean onRoute) {
			this.onRoute = onRoute;
		}

		@Override
		public boolean isCurrentlyManual() {
			// As in the game, a train that may be driven by hand counts as driven while it is in its siding
			return siding.manual && !onRoute;
		}

		@Override
		public int getManualNotch() {
			return manualNotch;
		}

		@Override
		public void setManualNotch(int manualNotch) {
			this.manualNotch = manualNotch;
		}

		@Override
		public RunSegment getRunSegment() {
			return runSegment;
		}

		/**
		 * @return what makes the segment the train runs on now, by the runner's clock, or null if it isn't running on one,
		 * as when it stands, is in its siding, or has just been set off and starts its next segment in the next tick (G2)
		 */
		public RunSegment.Spec getSegmentSpec() {
			if (runSegment == null || !onRoute || takenOver || speed <= 0 || runSegment.isFinished() || Math.abs(runSegment.getPosition() - railProgress) > RunSegment.POSITION_TOLERANCE) {
				return null;
			}
			return runSegment.describe(millis);
		}

		@Override
		public void setRunSegment(RunSegment runSegment) {
			this.runSegment = runSegment;
		}

		// MotionTrack

		@Override
		public int size() {
			return path.size();
		}

		@Override
		public int getDistanceCount() {
			return distances.size();
		}

		@Override
		public double getDistance(int rail) {
			return distances.get(rail);
		}

		@Override
		public int getDwellTime(int rail) {
			return path.get(rail).getDwellTime();
		}

		@Override
		public boolean canAccelerate(int rail) {
			return path.get(rail).canAccelerate();
		}

		@Override
		public float getMaxSpeed(int rail) {
			return path.get(rail).getMaxSpeed();
		}

		@Override
		public boolean isOppositeRail(int rail, int nextRail) {
			return path.get(rail).isOppositeOf(path.get(nextRail));
		}

		@Override
		public int getRepeatIndex1() {
			return repeatIndex1;
		}

		@Override
		public int getRepeatIndex2() {
			return repeatIndex2;
		}

		// MotionVehicle

		@Override
		public int getTrainCars() {
			return siding.trainCars;
		}

		@Override
		public int getSpacing() {
			return siding.spacing;
		}

		@Override
		public float getRailLength() {
			return siding.railLength;
		}

		@Override
		public float getAcceleration() {
			return siding.acceleration;
		}

		@Override
		public float getDeceleration() {
			return siding.deceleration;
		}

		@Override
		public float getDefaultMaxSpeed() {
			return siding.minSpeed;
		}

		@Override
		public float getManualMaxSpeed() {
			return siding.minSpeed;
		}

		// MotionHooks

		@Override
		public boolean isClientSide() {
			return false;
		}

		@Override
		public int getTotalDwellTicks() {
			return TrainRules.getTotalDwellTicks(this, path.get(nextStoppingIndex).getDwellTime() * 10);
		}

		@Override
		public boolean canDeploy() {
			// A train that can't leave skips this departure instead of queueing, so the timetable doesn't drift
			if (path.size() > 1 && !isRailOccupied(1)) {
				requestedDeploy = true;
			}
			// A train sent out for a duty also waits for the run out of the siding, like any other run
			return canDeploy && (!service.isOnDuty() || isRunGranted(1));
		}

		@Override
		public void startUp(boolean isOppositeRail) {
			canDeploy = false;
			onRoute = true;
			elapsedDwellTicks = 0;
			speed = MotionConstants.ACCELERATION_DEFAULT;
			if (isOppositeRail) {
				railProgress += siding.trainCars * siding.spacing;
				reversed = !reversed;
			}
			nextStoppingIndex = TrainRules.findNextStoppingIndex(this, MotionConstants.findIndex(this, railProgress, false));
			cruiseCap = TrainRules.getCruiseCap(this);
			doorTarget = false;
			doorValue = 0;
			nextPlatformIndex = nextStoppingIndex;
		}

		@Override
		public boolean keepsOnLineAtEnd() {
			return service.endsOnLine();
		}

		@Override
		public void clearRiders() {
		}

		@Override
		public boolean isRepeat() {
			return repeatIndex1 > 0 && repeatIndex2 > 0;
		}

		@Override
		public void onLapComplete() {
			service.onLapComplete();
		}

		@Override
		public void onStopReached() {
			service.onStopReached();
		}

		@Override
		public boolean isHeldAtStop() {
			return hostHold != null || TrainRules.isHeldAtStop(this);
		}

		@Override
		public boolean isRailBlocked(int rail) {
			// The rail onto the next station's throat, until the train is given its way through
			final ThroatLocks locks = getThroatLocks();
			if (locks != null && locks.isBlocked(rail, Math.max(MotionConstants.findIndex(this, railProgress, true), 0))) {
				blockedRail = rail;
				return true;
			}
			final boolean blocked = isRailOccupied(rail);
			if (blocked) {
				blockedRail = rail;
			}
			return blocked;
		}

		public long getSidingId() {
			return sidingRun.sidingId;
		}

		/**
		 * @return the rails it keeps for turning back, until its tail reaches {@link #getTurnBackUntil}
		 */
		public List<UUID> getTurnBackRails() {
			return turnBackRails;
		}

		public int getTurnBackUntil() {
			return turnBackUntil;
		}

		/**
		 * @return the services its depot sent out last and the days its duties ran, as the game's depot saves them
		 */
		public DepotDispatcher getDispatcher() {
			return sidingRun.depot.dispatcher;
		}

		public DepotBoard getDepot() {
			return sidingRun.depot;
		}

		/**
		 * @return the rail of its path it last waited for, in the last tick it moved in, or -1, as the game marks a delay
		 */
		public int getBlockedRail() {
			return blockedRail;
		}

		@Override
		public boolean openDoors() {
			return TrainRules.openDoors(this);
		}

		@Override
		public void emit(MotionEvent.Type type, int rail) {
			final MotionEvent event = new MotionEvent(millis, type, id, ++eventSequence, rail, runSegment == null ? 0 : runSegment.version);
			events.add(event);
			if (trainListener != null) {
				trainListener.onEvent(event, this);
			}
		}

		// MotionCurves

		@Override
		public SpeedProfile getProfile(double position) {
			final SpeedCurves.Run run = getSpeedCurves().getRun(position);
			if (run == null || Float.isInfinite(cruiseCap)) {
				return run == null ? null : run.fastest;
			}
			if (cappedProfile == null || cappedRun != run || cappedCap != cruiseCap) {
				cappedProfile = run.withCap(cruiseCap);
				cappedRun = run;
				cappedCap = cruiseCap;
			}
			return cappedProfile;
		}

		@Override
		public SpeedCurves.Run getRun(double position) {
			return getSpeedCurves().getRun(position);
		}

		@Override
		public double getCruiseCap() {
			return cruiseCap;
		}

		// TrainService.Vehicle

		@Override
		public List<T> getPath() {
			return path;
		}

		@Override
		public int getPathRepeatIndex1() {
			return pathRepeatIndex1;
		}

		@Override
		public int getPathRepeatIndex2() {
			return pathRepeatIndex2;
		}

		@Override
		public void setLastLap(boolean isLastLap) {
			repeatIndex1 = isLastLap ? 0 : pathRepeatIndex1;
			repeatIndex2 = isLastLap ? 0 : pathRepeatIndex2;
		}

		@Override
		public void followPath(TripPath<T> tripPath) {
			path = tripPath == null ? siding.path : tripPath.path;
			distances = tripPath == null ? siding.distances : tripPath.distances;
			pathRepeatIndex1 = tripPath == null ? siding.repeatIndex1 : tripPath.repeatIndex1;
			pathRepeatIndex2 = tripPath == null ? siding.repeatIndex2 : tripPath.repeatIndex2;
			// Runs are rails of the path, so a run given on the old one is asked for again
			grantedRunEnd = -1;
			requestedRunEnd = -1;
		}

		@Override
		public void changePath(TripPath<T> tripPath, int newStopIndex) {
			final double distanceToStop = distances.get(nextStoppingIndex) - railProgress;
			followPath(tripPath);
			railProgress = distances.get(newStopIndex) - distanceToStop;
			nextStoppingIndex = newStopIndex;
			nextPlatformIndex = newStopIndex;
		}

		@Override
		public boolean isTurnBackFree(List<UUID> rails) {
			for (final UUID rail : rails) {
				if (isTakenByOther(rail, this)) {
					return false;
				}
			}
			return true;
		}

		@Override
		public void reserveTurnBack(List<UUID> rails, int untilIndex) {
			turnBackRails = rails;
			turnBackUntil = untilIndex;
		}

		@Override
		public boolean isWayClear(List<UUID> rails) {
			return isTurnBackFree(rails) && !reservations.isHeldByOther(id, rails);
		}

		@Override
		public boolean isStopFree(int stopIndex) {
			double remainingLength = siding.trainCars * siding.spacing;
			for (int i = stopIndex; i >= 0 && remainingLength > 0; i--) {
				if (isRailOccupied(i)) {
					return false;
				}
				remainingLength -= path.get(i).getTrackLength();
			}
			return true;
		}

		@Override
		public void moveToStop(int stopIndex) {
			// Every turn back on the way swaps which end of the train leads
			reversed = false;
			for (int i = 0; i < stopIndex; i++) {
				if (path.get(i).isOppositeOf(path.get(i + 1))) {
					reversed = !reversed;
				}
			}
			railProgress = distances.get(stopIndex);
			speed = 0;
			manualNotch = -2;
			elapsedDwellTicks = 0;
			nextStoppingIndex = stopIndex;
			nextPlatformIndex = stopIndex;
			grantedRunEnd = -1;
			requestedRunEnd = -1;
		}

		@Override
		public boolean moveBetweenStops(int fromStop, int toStop, double newRailProgress, float newSpeed) {
			moveToStop(fromStop);
			// Turning back on the run swaps which end leads too
			for (int i = fromStop; i + 1 < path.size() && distances.get(i) < newRailProgress; i++) {
				if (path.get(i).isOppositeOf(path.get(i + 1))) {
					reversed = !reversed;
				}
			}
			railProgress = newRailProgress;
			speed = Math.max(0, newSpeed);
			onRoute = true;
			// Its run is worked out afresh from where it is, as for a train given back by the host
			runSegment = null;
			manualNotch = speed > 0 ? 0 : -2;
			nextStoppingIndex = toStop;
			nextPlatformIndex = toStop;
			return true;
		}

		@Override
		public void returnToDepot() {
			onRoute = false;
			manualNotch = -2;
			speed = 0;
		}

		private void deployDuty(int slot, long dutySidingId, long dayStart, TripPath<T> dutyPath) {
			canDeploy = true;
			service.deployDuty(slot, dutySidingId, dayStart, dutyPath);
		}

		/**
		 * Puts a train sent out late at the first stop of its duty it can still keep to; if none is free, it stays in the
		 * siding.
		 */
		private void joinDutyLate() {
			onRoute = true;
			canDeploy = false;
			service.joinDutyLate();
			if (onRoute) {
				writePositions();
			}
		}
	}
}
