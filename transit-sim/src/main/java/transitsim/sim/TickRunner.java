package transitsim.sim;

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
import transitsim.plan.PlanRoute;
import transitsim.plan.RouteTimetable;
import transitsim.plan.StopTimes;
import transitsim.plan.TripPath;
import transitsim.plan.TurnBacks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * Runs trains tick by tick the way the game's server does, without a world (S4a): each siding's trains in turn by
 * {@link TrainMotion}, {@link TrainRules} and {@link TrainService}, then the runs asked for, then the depots sending trains
 * out. Rails taken are kept in an {@link Occupancy} and runs of trains on duties in {@link RunReservations}.
 * <p>
 * It is what the event driven simulator is compared against, and runs the fixed whole-chain scenarios. Left out, as they
 * need a world or a player: signal blocks spanning several rails, manual driving, passengers, doors' platforms, and
 * depots sending trains out by frequency instead of a timetable or duties.
 */
public final class TickRunner<T extends RunTrack> {

	public final Occupancy occupancy = new Occupancy();
	public final RunReservations reservations = new RunReservations();
	private final Map<Long, SidingRun> sidings = new TreeMap<>();
	private final List<MotionEvent> events = new ArrayList<>();
	/**
	 * Rails taken by something the runner doesn't move, such as a driven train, until a time.
	 */
	private final Map<UUID, Long> externalUntil = new TreeMap<>();
	private final LongFunction<? extends PlanRoute> routes;
	private long millis;
	private long nextTrainId = 1;
	/**
	 * Whether ticks in which nothing can change are skipped (S4c2).
	 */
	private boolean skipping;
	private Runnable afterTick = () -> {
	};
	private long skippedTicks;

	/**
	 * The occupant of rails taken from outside.
	 */
	public static final long EXTERNAL_OCCUPANT = -1;

	/**
	 * @param startMillis the time of the first tick, as the timetable counts it
	 * @param routes      finds a route by ID
	 */
	public TickRunner(long startMillis, LongFunction<? extends PlanRoute> routes) {
		millis = startMillis;
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
		return sidingRun;
	}

	/**
	 * Tracks gone from the network, which no train runs onto (contracts document 5.1, rule 5).
	 */
	private final Set<UUID> removedTracks = new HashSet<>();

	public LongFunction<? extends PlanRoute> getRoutes() {
		return routes;
	}

	/**
	 * Takes tracks out of the network. Trains stop short of them; the caller deals with trains standing on them.
	 */
	public void removeTracks(Set<UUID> tracks) {
		removedTracks.addAll(tracks);
	}

	public boolean isRemoved(UUID track) {
		return removedTracks.contains(track);
	}

	/**
	 * Takes a rail from now until this time, as a train driven by hand or another train the host moves would.
	 */
	public void occupyUntil(UUID rail, long untilMillis) {
		externalUntil.put(rail, untilMillis);
	}

	/**
	 * Skips ticks in which nothing can change, which gives the same results as running every tick (S4c2). Off, the
	 * runner runs every tick, as the game does.
	 */
	public void setSkipping(boolean skipping) {
		this.skipping = skipping;
	}

	/**
	 * Called at the end of every tick that is run, not skipped.
	 */
	public void setAfterTick(Runnable afterTick) {
		this.afterTick = afterTick;
	}

	/**
	 * @return how many ticks were skipped since the start
	 */
	public long getSkippedTicks() {
		return skippedTicks;
	}

	/**
	 * Runs ticks until this time.
	 */
	public void runUntil(long endMillis) {
		while (millis + MotionConstants.MILLIS_PER_TICK <= endMillis) {
			if (skipping) {
				// The last tick before the end is always run, so the state there is what running every tick gives
				final long quiet = Math.min(getQuietTicks(), (endMillis - millis) / MotionConstants.MILLIS_PER_TICK - 1);
				if (quiet > 0) {
					skip(quiet);
				}
			}
			tick();
		}
	}

	/**
	 * @return how many of the next ticks are sure to change nothing but where trains are along their segments and how long
	 * they have stood, so they can be skipped, or 0
	 */
	private long getQuietTicks() {
		long quiet = Long.MAX_VALUE;
		for (final long untilMillis : externalUntil.values()) {
			// Something taken from outside is let go in the first tick after its time
			quiet = Math.min(quiet, (untilMillis - millis) / MotionConstants.MILLIS_PER_TICK);
		}
		for (final SidingRun sidingRun : sidings.values()) {
			quiet = Math.min(quiet, sidingRun.getQuietTicks());
			if (quiet <= 0) {
				return 0;
			}
		}
		return quiet;
	}

	/**
	 * Moves on this many ticks in which nothing changes but where trains are along their segments and how long they have
	 * stood. Nobody crosses from one rail to another meanwhile, so what is taken stays the same.
	 */
	private void skip(long ticks) {
		millis += ticks * MotionConstants.MILLIS_PER_TICK;
		skippedTicks += ticks;
		sidings.values().forEach(sidingRun -> sidingRun.trains.forEach(train -> train.skip(ticks)));
	}

	public void tick() {
		millis += MotionConstants.MILLIS_PER_TICK;
		occupancy.nextTick();
		externalUntil.values().removeIf(untilMillis -> untilMillis < millis);
		externalUntil.keySet().forEach(rail -> occupancy.occupy(rail, EXTERNAL_OCCUPANT));
		final Map<DepotBoard, List<SidingRun>> deployable = new HashMap<>();
		sidings.values().forEach(sidingRun -> sidingRun.tick(deployable));
		reservations.resolve(millis);
		// Each depot once, in the order of its first siding
		final List<DepotBoard> depots = new ArrayList<>();
		sidings.values().forEach(sidingRun -> {
			if (!depots.contains(sidingRun.depot)) {
				depots.add(sidingRun.depot);
			}
		});
		depots.forEach(depot -> deploy(depot, deployable.getOrDefault(depot, Collections.emptyList())));
		afterTick.run();
	}

	/**
	 * @return the events of the trains since the start, in the order they happened
	 */
	public List<MotionEvent> getEvents() {
		return Collections.unmodifiableList(events);
	}

	/**
	 * @return the train with this ID, or null
	 */
	public HeadlessTrain getTrain(long trainId) {
		for (final SidingRun sidingRun : sidings.values()) {
			for (final HeadlessTrain train : sidingRun.trains) {
				if (train.id == trainId) {
					return train;
				}
			}
		}
		return null;
	}

	/**
	 * Takes a train away, which gives up everything it holds. Its siding makes a new one when it needs it.
	 *
	 * @return false if there is no such train
	 */
	public boolean removeTrain(long trainId) {
		for (final SidingRun sidingRun : sidings.values()) {
			if (sidingRun.trains.removeIf(train -> train.id == trainId)) {
				reservations.release(trainId);
				return true;
			}
		}
		return false;
	}

	public List<HeadlessTrain> getTrains() {
		final List<HeadlessTrain> trains = new ArrayList<>();
		sidings.values().forEach(sidingRun -> trains.addAll(sidingRun.trains));
		return trains;
	}

	/**
	 * Sends out one train of the depot that is ready, for the first service of the timetable no train has taken, as the
	 * game's depot does. A depot running by duties sends the train of the duty due first.
	 */
	private void deploy(DepotBoard depot, List<SidingRun> ready) {
		depot.dispatcher.startTick();
		if (depot.duties.diagramMode) {
			final Map<Long, HeadlessTrain> readyTrains = new TreeMap<>();
			ready.forEach(sidingRun -> readyTrains.put(sidingRun.sidingId, sidingRun.waitingTrain));
			final DepotDispatcher.DutyDeploy<T> deploy = depot.dispatcher.findDutyDeploy(millis, readyTrains.keySet(), depot.duties, sidingId -> sidings.containsKey(sidingId) ? sidings.get(sidingId).siding.tripPaths : null, routes);
			if (deploy != null) {
				readyTrains.get(deploy.sidingId).deployDuty(deploy.slot, deploy.dayStart, deploy.path);
			}
			return;
		}
		final RouteTimetable timetable = depot.getTimetable("");
		if (timetable == null) {
			return;
		}
		final PlanRoute firstRoute = depot.getRoutes().get(0);
		for (final SidingRun sidingRun : ready) {
			final HeadlessTrain train = sidingRun.waitingTrain;
			// A siding whose path runs over a track gone from the network sends no more trains
			if (train.findRemovedRail() >= 0) {
				continue;
			}
			final long tripMillis = train.service.findDeployService(depot.dispatcher.lastDeployedTrips.getOrDefault(firstRoute.getRouteId(), 0L));
			if (tripMillis != 0) {
				depot.dispatcher.lastDeployedTrips.put(firstRoute.getRouteId(), tripMillis);
				depot.reserveTrip(firstRoute.getRouteId(), tripMillis);
				train.canDeploy = true;
				train.service.deploy(tripMillis, firstRoute.getRouteId());
				break;
			}
		}
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
		 * The highest speed limit of the path, for how far ahead a train may have to look.
		 */
		public final float maxSpeed;
		/**
		 * The lowest cruising speed of the kind of vehicle, in blocks per tick.
		 */
		public float minSpeed = 0.5F;

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
		private HeadlessTrain waitingTrain;

		private SidingRun(long sidingId, DepotBoard depot, Siding<T> siding) {
			this.sidingId = sidingId;
			this.depot = depot;
			this.siding = siding;
		}

		/**
		 * @return how many of the next ticks change nothing for this siding and its trains, or 0
		 */
		private long getQuietTicks() {
			// Trains by duties, a siding about to make or clear away a train, or two trains in the same place change at once
			if (depot.duties.diagramMode || trains.isEmpty()) {
				return 0;
			}
			int trainsAtDepot = 0;
			boolean spawnTrain = true;
			final Set<Long> railProgressSet = new HashSet<>();
			long quiet = Long.MAX_VALUE;
			for (final HeadlessTrain train : trains) {
				if (!train.onRoute || train.railProgress < siding.spacing * siding.trainCars + siding.railLength) {
					spawnTrain = false;
				}
				if (!train.onRoute && ++trainsAtDepot > 1 || !railProgressSet.add(Math.round(train.railProgress * 10))) {
					return 0;
				}
				quiet = Math.min(quiet, train.getQuietTicks());
				if (quiet <= 0) {
					return 0;
				}
			}
			return spawnTrain && trains.size() <= siding.maxTrains ? 0 : quiet;
		}

		private void tick(Map<DepotBoard, List<SidingRun>> deployable) {
			waitingTrain = null;
			int trainsAtDepot = 0;
			boolean spawnTrain = true;
			final List<HeadlessTrain> trainsToRemove = new ArrayList<>();
			for (final HeadlessTrain train : trains) {
				train.service.setStopTimes(siding.stopTimes, siding.tripPaths, siding.turnBacks);
				train.simulate();
				if (train.requestedDeploy) {
					waitingTrain = train;
					deployable.computeIfAbsent(depot, key -> new ArrayList<>()).add(this);
				}
				if (!train.onRoute || train.railProgress < siding.spacing * siding.trainCars + siding.railLength) {
					spawnTrain = false;
				}
				if (!train.onRoute && ++trainsAtDepot > 1) {
					trainsToRemove.add(train);
				}
				train.writePositions();
			}
			trains.removeAll(trainsToRemove);
			final int dutySlots = depot.duties.getSlots(sidingId).size();
			if (depot.duties.diagramMode ? dutySlots > 0 && (trains.isEmpty() || spawnTrain && trains.size() < dutySlots) : trains.isEmpty() || spawnTrain && trains.size() <= siding.maxTrains) {
				trains.add(new HeadlessTrain(nextTrainId++, this));
			}
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

		private HeadlessTrain(long id, SidingRun sidingRun) {
			this.id = id;
			this.sidingRun = sidingRun;
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

		private void simulate() {
			requestedDeploy = false;
			service.updateOvernight();
			TrainMotion.tick(this, this, this, this, this, 1);
			holdRun();
			service.update(onRoute);
		}

		private void writePositions() {
			if (path.isEmpty()) {
				return;
			}
			final int headIndex = MotionConstants.findIndex(this, railProgress, true);
			final int tailIndex = MotionConstants.findIndex(this, railProgress - siding.trainCars * siding.spacing, false);
			for (int i = tailIndex; i <= headIndex; i++) {
				final T track = path.get(i);
				if (i > 0 && track.getStopId() != sidingRun.sidingId && track.hasSignal()) {
					occupancy.occupy(track.getTrackKey(), id);
				}
			}
			if (!turnBackRails.isEmpty()) {
				if (!onRoute || tailIndex >= turnBackUntil) {
					turnBackRails = Collections.emptyList();
				} else {
					turnBackRails.forEach(rail -> occupancy.getTicks().get(1).putIfAbsent(rail, id));
				}
			}
		}

		/**
		 * @return how many of the next ticks change nothing for this train but where it is along its segment, or how long it
		 * has stood, or 0
		 */
		private long getQuietTicks() {
			// A new train gets its stop times in its first tick, and works out when to leave from them
			if (!siding.usesSpeedCurve || path.isEmpty() || service.isOnDuty() || service.getStopTimes() == null) {
				return 0;
			}
			final int trainLength = siding.trainCars * siding.spacing;
			if (!onRoute) {
				if (canDeploy) {
					return 0;
				}
				if (path.size() > 1 && !isRailOccupied(1)) {
					// Waiting to be sent out, which can only be once a service comes near enough
					final List<PlanRoute> depotRoutes = sidingRun.depot.getRoutes();
					if (depotRoutes.isEmpty()) {
						return Long.MAX_VALUE;
					}
					final long nextDeployMillis = service.getNextDeployMillis(sidingRun.depot.dispatcher.lastDeployedTrips.getOrDefault(depotRoutes.get(0).getRouteId(), 0L));
					return nextDeployMillis == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, (nextDeployMillis - millis) / MotionConstants.MILLIS_PER_TICK - 1);
				}
				return Long.MAX_VALUE;
			}
			if (doorValue != (doorTarget ? 1 : 0) || nextStoppingIndex >= path.size()) {
				return 0;
			}
			if (railProgress >= distances.get(distances.size() - 1) - (siding.railLength - trainLength) / 2 && !keepsOnLineAtEnd()) {
				return 0;
			}
			if (speed <= 0) {
				return getStandingQuietTicks();
			}
			return getMovingQuietTicks(trainLength);
		}

		private long getStandingQuietTicks() {
			final int headIndex = MotionConstants.findIndex(this, railProgress, true);
			final boolean isOppositeRail = headIndex + 1 < path.size() && headIndex >= 0 && isOppositeRail(headIndex, headIndex + 1);
			final boolean railBlocked = isRailOccupied(headIndex + (isOppositeRail ? 2 : 1));
			final int totalDwellTicks = getTotalDwellTicks();
			if (totalDwellTicks == 0) {
				// Stopped short of a taken rail, which stays taken while nobody moves from one rail to another
				return railBlocked && !doorTarget ? Long.MAX_VALUE : 0;
			}
			if (elapsedDwellTicks == 0 || isRepeat()) {
				return 0;
			}
			// A late train's stop time follows the clock, which moves differently from the time it has stood
			final int pathDwellTicks = path.get(nextStoppingIndex).getDwellTime() * 10;
			final RouteTimetable.StopPlan stopPlan = service.getPlanAt(nextStoppingIndex);
			if (stopPlan != null && elapsedDwellTicks + (service.getPlannedMillis(stopPlan.departureMillis) - millis) / MotionConstants.MILLIS_PER_TICK < pathDwellTicks) {
				return 0;
			}
			// Stays short of the next time the doors move or the train may be held or leave
			final float maxDoorMoveTime = Math.min(MotionConstants.DOOR_MOVE_TIME, totalDwellTicks / 2 - MotionConstants.DOOR_DELAY);
			long quiet = Long.MAX_VALUE;
			for (final double threshold : new double[]{MotionConstants.DOOR_DELAY, totalDwellTicks - MotionConstants.DOOR_DELAY - maxDoorMoveTime, totalDwellTicks - MotionConstants.DOOR_MOVE_TIME - MotionConstants.DOOR_DELAY - 1}) {
				if (threshold > elapsedDwellTicks) {
					quiet = Math.min(quiet, (long) Math.floor(threshold - elapsedDwellTicks) - 2);
				}
			}
			return quiet == Long.MAX_VALUE ? 0 : Math.max(0, quiet);
		}

		private long getMovingQuietTicks(int trainLength) {
			final RunSegment segment = runSegment;
			if (segment == null || segment.isFinished() || nextStoppingIndex != nextPlatformIndex || isRepeat()) {
				return 0;
			}
			final int headIndex = MotionConstants.findIndex(this, railProgress, false);
			final int tailIndex = MotionConstants.findIndex(this, railProgress - trainLength, false);
			// Head and tail stay on their rails, the train stays clear of the siding and of where it stops
			double bound = Math.min(distances.get(headIndex), distances.get(nextStoppingIndex));
			if (railProgress - trainLength >= 0) {
				bound = Math.min(bound, distances.get(tailIndex) + trainLength);
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
			for (int i = MotionConstants.findIndex(this, railProgress, true) + 1; i <= lastCheckIndex && distances.get(i - 1) <= reach; i++) {
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
					elapsedDwellTicks += ticks;
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

		private boolean isRailOccupied(int index) {
			return index < path.size() && (occupancy.isTakenByOther(path.get(index).getTrackKey(), id) || removedTracks.contains(path.get(index).getTrackKey()));
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
			reservations.hold(id, getRunMoves(Math.max(TrainRules.findRunEnd(this, nextPlatformIndex), grantedRunEnd)));
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
		public boolean usesLegacyMotion() {
			return false;
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
			if (requestedRunEnd == runEnd && reservations.takeGrant(id)) {
				grantedRunEnd = runEnd;
				requestedRunEnd = -1;
				return true;
			}
			requestedRunEnd = runEnd;
			reservations.request(id, getRunMoves(runEnd), TrainRules.getPlannedDepartureMillis(this));
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
			return false;
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
			return isRailOccupied(rail);
		}

		@Override
		public boolean openDoors() {
			return TrainRules.openDoors(this);
		}

		@Override
		public void emit(MotionEvent.Type type, int rail) {
			events.add(new MotionEvent(millis, type, id, ++eventSequence, rail, runSegment == null ? 0 : runSegment.version));
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
				if (occupancy.isTakenByOther(rail, id)) {
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
		public void returnToDepot() {
			onRoute = false;
			manualNotch = -2;
			speed = 0;
		}

		private void deployDuty(int slot, long dayStart, TripPath<T> dutyPath) {
			canDeploy = true;
			service.deployDuty(slot, dayStart, dutyPath);
		}
	}
}
