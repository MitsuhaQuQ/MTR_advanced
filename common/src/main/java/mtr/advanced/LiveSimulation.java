package mtr.advanced;

import mtr.data.Depot;
import mtr.data.MotionConfig;
import mtr.data.RailwayData;
import mtr.data.Siding;
import mtr.data.TrainServer;
import mtr.mappings.Text;
import net.minecraft.server.level.ServerPlayer;
import transitsim.plan.DutyShapes;
import transitsim.sim.EventSimulator;
import transitsim.sim.HostInput;
import transitsim.sim.RunRecord;
import transitsim.sim.RunReservations;
import transitsim.sim.SimDataset;
import transitsim.sim.SimEvent;
import transitsim.sim.TickRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The component's simulator running the trains of the depots it can run (G1, decoupling document 5.2): depots with speed
 * curves, not moving continuously, whether they send their trains out by a timetable, by duties or by frequency. The
 * trains of the other depots are told to it as external trains ({@link SimInputs}). Running the trains, its trains hold
 * and ask for their runs in the game's run reservations, dealt with once a tick together with the game's own trains, so
 * the two kinds of train wait for each other on single track as any two of the game's do. Compared, it has reservations of
 * its own and is told the runs the game's trains of other depots hold.
 * <p>
 * A train driven by hand, which a train of a siding that allows it is whenever it is in the siding, is taken over: the
 * game moves it itself until it is given back on the line.
 * <p>
 * Running the trains, the simulator has them: each tick it moves on to now,
 * and the game's trains of those sidings only show its trains (A06), doing what needs the world, such as their cars,
 * riders, doors and the rails they take for the game's other trains and signals. It starts again from the game's trains,
 * which then are where it had them, when the network or the plans change.
 * <p>
 * It always runs the trains (R4); comparing it with the game is left to the tests running both by the same rules.
 */
public final class LiveSimulation {

	/**
	 * How long a comparison runs before it is reported and started again.
	 */
	private static final long COMPARE_MILLIS = 60000;
	/**
	 * How often, while it runs the trains, it looks whether the network or the plans changed, when nothing said they did.
	 */
	private static final long CHANGE_CHECK_MILLIS = 300000;
	/**
	 * How long it waits to start again after it was stopped while running the trains.
	 */
	private static final long RESTART_MILLIS = 30000;
	/**
	 * How long it waits after the game's data was changed before it looks, so a run of edits is looked at once.
	 */
	private static final long CHANGE_SETTLE_MILLIS = 2000;
	/**
	 * How often it looks again for sidings it can run while it has none, as the depots' timetables are only known a moment
	 * after the world is loaded.
	 */
	private static final long RETRY_MILLIS = 1000;

	private final SimInputs inputs = new SimInputs();
	private final Random random = new Random();
	private EventSimulator<SimDataset.DataTrack> simulator;
	/**
	 * Whether the simulator runs the trains rather than being compared.
	 */
	private boolean driving;
	private Set<Long> sidingIds = Collections.emptySet();
	private byte[] startedData = new byte[0];
	private long startedMillis;
	private long checkedMillis;
	/**
	 * The versions of the game's data and signal blocks when it last looked, and when it saw them change since.
	 */
	private long checkedVersion;
	private long versionChangedMillis;
	private long lastMillis;
	/**
	 * Trains to be put where their depots' timetables have them now when the simulator starts again at the next tick (see
	 * {@link #placeByTimetable(RailwayData, ServerPlayer, long)}).
	 */
	private Placement placement;
	private boolean started;
	private int restoredTrains;
	/**
	 * The last event looked at, and what happened since the last report while it runs the trains.
	 */
	private long eventCursor;
	private long reportedMillis;
	private int departures;
	private int arrivals;
	private int recordedTrains;
	/**
	 * The trains driven by hand in the sidings the simulator runs, which it has as taken over.
	 */
	private final Set<Long> takenOver = new HashSet<>();
	/**
	 * Sidings left to the game, as some of their trains couldn't be carried on in the simulator (their paths changed, say);
	 * running them would take those trains away. Tried again when the network or the plans change.
	 */
	private final Set<Long> excludedSidings = new TreeSet<>();
	/**
	 * When the simulator last failed, or 0.
	 */
	private long failedMillis;
	/**
	 * How long it stays off after failing before it starts again.
	 */
	private static final long FAILED_RETRY_MILLIS = 5 * 60 * 1000;

	/**
	 * Tells one of its trains which platforms to pass without stopping (D3), applied in its next tick.
	 *
	 * @return false if it doesn't run that train, which the game then tells itself
	 */
	public boolean submitSkip(long trainId, java.util.Set<Long> platformIds) {
		if (simulator == null || !driving || takenOver.contains(trainId) || !simulator.getRunner().hasTrain(trainId)) {
			return false;
		}
		simulator.submit(new transitsim.sim.HostInput.Skip(Math.max(lastMillis, System.currentTimeMillis()), inputs.nextSequence(), trainId, platformIds.stream().mapToLong(Long::longValue).toArray()));
		return true;
	}

	/**
	 * Runs the simulator on to now, before the game's sidings move their trains in this tick.
	 *
	 * @param trainPositions the game's rails taken, of the tick before and of this one
	 */
	public void tick(RailwayData railwayData, List<Map<UUID, Long>> trainPositions) {
		// It always runs the trains (R4); having failed, it waits a while, the game moving the trains meanwhile
		if (failedMillis != 0 && System.currentTimeMillis() < failedMillis + FAILED_RETRY_MILLIS) {
			return;
		}
		failedMillis = 0;
		try {
			run(railwayData, trainPositions, true);
		} catch (Exception e) {
			// Nothing of the simulator may stop the game's tick; the game moves the trains on from where the simulator had them
			System.out.println("Minecraft Transit Railway: the component's simulator failed and is off for five minutes");
			e.printStackTrace();
			failedMillis = System.currentTimeMillis();
			stop(railwayData);
		}
	}

	private void run(RailwayData railwayData, List<Map<UUID, Long>> trainPositions, boolean drive) {
		// Which depot each siding is in is only known a moment after the world is loaded, and the game puts the trains where
		// the plan has them a tick later; the simulator carries on from there, so it starts from what the game would have
		if (!started && (!railwayData.sidings.isEmpty() && railwayData.dataCache.sidingIdToDepot.isEmpty() || railwayData.sidings.stream().anyMatch(siding -> railwayData.dataCache.sidingIdToDepot.containsKey(siding.id) && !siding.hasSettled()))) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (simulator != null && drive) {
			// A stop of more than a minute is recovered from (contracts document 6.3), and a change starts it again
			final boolean stopped = now - lastMillis > EventSimulator.RECOVERY_MILLIS;
			if (stopped || placement != null || hasChanged(railwayData, now)) {
				start(railwayData, now, true, stopped ? lastMillis : now);
			}
		} else if (simulator == null && (!started || now >= startedMillis + (sidingIds.isEmpty() ? RETRY_MILLIS : RESTART_MILLIS))) {
			// The first time after the world is loaded, the game has already put the trains where the plan has them
			start(railwayData, now, drive, now);
		}
		// Time going back leaves the simulator where it is (contracts document A02)
		if (simulator == null || now <= lastMillis) {
			return;
		}

		final Set<Long> run = new HashSet<>();
		final List<HostInput> handInputs = new ArrayList<>();
		forEachTrain(railwayData, train -> {
			if (train.isCurrentlyManual()) {
				if (takenOver.add(train.id)) {
					handInputs.add(new HostInput.TakeOver(now, inputs.nextSequence(), train.id));
				}
			} else {
				run.add(train.id);
				if (takenOver.remove(train.id)) {
					handInputs.add(new HostInput.Release(now, inputs.nextSequence(), train.id, train.getRailProgress(), train.getSpeed()));
				}
			}
		});
		handInputs.forEach(simulator::submit);
		if (!trainPositions.isEmpty()) {
			inputs.update(now, trainPositions.get(0), trainId -> !run.contains(trainId)).forEach(simulator::submit);
		}
		final TickRunner<SimDataset.DataTrack> runner = simulator.getRunner();
		simulator.advanceTo(now);
		lastMillis = now;
		if (drive) {
			// Running the trains, it shares the game's reservations, which the game deals with at the end of its tick together
			// with its own trains' requests; what its trains hold and ask for is renewed in every tick of the game
			runner.renewReservations();
		}
		final List<SimEvent> events = simulator.events(eventCursor);
		if (!events.isEmpty()) {
			eventCursor = events.get(events.size() - 1).cursor;
			events.forEach(event -> {
				if (event.kind == SimEvent.Kind.DEPART) {
					departures++;
				} else if (event.kind == SimEvent.Kind.ARRIVE) {
					arrivals++;
				}
			});
			simulator.forgetEvents(eventCursor);
		}

		if (drive) {
			show(railwayData);
			if (now >= reportedMillis + COMPARE_MILLIS) {
				final long onLine = simulator.getRunner().getTrains().stream().filter(train -> train.isOnRoute()).count();
				System.out.println("Minecraft Transit Railway: in the last minute the simulator's trains left " + departures + " times and arrived " + arrivals + " times; " + onLine + " of " + simulator.getRunner().getTrains().size() + " trains are on the line");
				reportedMillis = now;
				departures = 0;
				arrivals = 0;
			}
		}
	}

	/**
	 * Starts the simulator from the game's trains of the depots it can run.
	 *
	 * @param recordMillis when the game's trains were as they are, so that more than a minute ago puts them where the plan
	 *                     has them now
	 */
	private void start(RailwayData railwayData, long now, boolean drive, long recordMillis) {
		final Set<Long> newSidingIds = getRunSidingIds(railwayData);
		// Said when the sidings or what it does with them change; a world with none it can run says nothing
		if (!newSidingIds.equals(sidingIds) || driving != drive && !newSidingIds.isEmpty()) {
			System.out.println("Minecraft Transit Railway: the component's simulator " + (drive ? "runs" : "is compared on") + " " + newSidingIds.size() + " of " + railwayData.sidings.size() + " sidings, the ones of depots with speed curves");
		}
		stop(railwayData);
		started = true;
		driving = drive;
		sidingIds = newSidingIds;
		startedMillis = now;
		checkedMillis = now;
		checkedVersion = getVersion(railwayData);
		versionChangedMillis = 0;
		lastMillis = now;
		reportedMillis = now;
		departures = 0;
		arrivals = 0;
		takenOver.clear();
		// The new simulator knows nothing of the trains it leaves alone yet
		inputs.reset();
		if (sidingIds.isEmpty()) {
			return;
		}

		final SimDataset dataset = getDataset(railwayData);
		startedData = dataset.toBytes();
		final RunRecord record = new RunRecord(recordMillis);
		final Set<Long> recordedDepots = new HashSet<>();
		railwayData.sidings.stream().filter(siding -> sidingIds.contains(siding.id)).sorted().forEach(siding -> {
			final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
			if (depot != null && recordedDepots.add(depot.id)) {
				depot.recordDispatcher(record, siding.id);
			}
			siding.forEachTrain(train -> record.trains.add(train.toRecord()));
		});
		final Placement placed = placement;
		final Map<Long, Integer> placedTrains = placed == null ? Collections.emptyMap() : placed.takeInto(railwayData, record, now, random::nextLong);
		if (placed != null) {
			placement = null;
		}
		recordedTrains = record.trains.size();
		simulator = EventSimulator.resume(startMillis -> {
			final TickRunner<SimDataset.DataTrack> runner = dataset.buildRunner(startMillis);
			runner.setTrainIds(random::nextLong);
			if (drive) {
				runner.shareReservations(railwayData.runReservations);
			}
			return runner;
		}, record, now);
		simulator.setLive();
		eventCursor = 0;
		simulator.forgetEvents(Long.MAX_VALUE);
		restoredTrains = simulator.getRunner().getTrains().size();
		// The game's trains the simulator couldn't carry on would be taken away by showing its trains, so their sidings are
		// left to the game
		final Set<Long> lostSidings = new TreeSet<>();
		final List<Long> lostTrains = new ArrayList<>();
		record.trains.forEach(train -> {
			if (!simulator.getRunner().hasTrain(train.trainId)) {
				lostSidings.add(train.sidingId);
				lostTrains.add(train.trainId);
			}
		});
		if (!lostSidings.isEmpty()) {
			System.out.println("Minecraft Transit Railway: the component's simulator leaves sidings " + lostSidings + " to the game, as it couldn't carry on their trains " + lostTrains);
			simulator = null;
			excludedSidings.addAll(lostSidings);
			start(railwayData, now, drive, recordMillis);
			return;
		}
		if (drive) {
			show(railwayData);
		}
		if (placed != null && placedTrains != null) {
			final List<String> counts = new ArrayList<>();
			placed.depotIds.forEach(depotId -> counts.add(getDepotName(railwayData, depotId) + " " + placedTrains.getOrDefault(depotId, 0)));
			placed.player.displayClientMessage(Text.translatable("gui.mtr.place_trains_done", String.join(", ", counts)), false);
		}
	}

	private static String getDepotName(RailwayData railwayData, long depotId) {
		final Depot depot = railwayData.dataCache.depotIdMap.get(depotId);
		return depot == null ? "?" : depot.name.split("\\|\\|")[0].replace('|', ' ');
	}

	/**
	 * Asks for a depot's trains to be put where its timetable has them now. The depots sharing a route with it, one after
	 * the other, are put too, as their trains run on the same line and take services of the same timetables: worked out
	 * apart, their trains would be put on top of each other's. Where they would all be had they run from the start of the
	 * operating day is worked out away from the server thread, and the simulator starts from it at the next tick after.
	 */
	public void placeByTimetable(RailwayData railwayData, ServerPlayer player, long depotId) {
		final boolean runs = simulator != null && driving && railwayData.sidings.stream().anyMatch(siding -> sidingIds.contains(siding.id) && railwayData.dataCache.sidingIdToDepot.get(siding.id) != null && railwayData.dataCache.sidingIdToDepot.get(siding.id).id == depotId);
		if (!runs) {
			player.displayClientMessage(Text.translatable("gui.mtr.place_trains_not_run"), false);
			return;
		}
		if (placement != null) {
			player.displayClientMessage(Text.translatable("gui.mtr.place_trains_busy"), false);
			return;
		}

		final SimDataset dataset = getDataset(railwayData);
		// The depot and every depot sharing a route with it, and every depot sharing one with those
		final Set<Long> depotIds = new TreeSet<>();
		depotIds.add(depotId);
		final Set<Long> routeIds = new HashSet<>();
		boolean grew = true;
		while (grew) {
			grew = false;
			for (final SimDataset.DepotData depot : dataset.depots) {
				final boolean shares = depotIds.contains(depot.id) || Arrays.stream(depot.routeIds).anyMatch(routeIds::contains);
				if (shares && (depotIds.add(depot.id) | Arrays.stream(depot.routeIds).mapToObj(Long::valueOf).map(routeIds::add).reduce(false, Boolean::logicalOr))) {
					grew = true;
				}
			}
		}
		dataset.trains = null;
		dataset.sidings.removeIf(siding -> !depotIds.contains(siding.depotId));
		dataset.depots.removeIf(depot -> !depotIds.contains(depot.id));
		final Map<Long, Long> sidingDepots = new HashMap<>();
		dataset.sidings.forEach(siding -> sidingDepots.put(siding.id, siding.depotId));
		final byte[] bytes = dataset.toBytes();
		final long now = System.currentTimeMillis();

		final List<String> names = new ArrayList<>();
		depotIds.forEach(id -> names.add(getDepotName(railwayData, id)));
		player.displayClientMessage(Text.translatable("gui.mtr.place_trains_working", String.join(", ", names)), false);
		// Depots running by duties have their trains put straight where the duties have them; the others are worked out
		// from the start of the day
		final boolean byDuties = dataset.depots.stream().allMatch(depot -> depot.duties.length > 0);
		placement = new Placement(player, depotIds, sidingDepots, bytes, byDuties);
	}

	/**
	 * Trains of depots put where their timetables have them now, to start the simulator from in their place.
	 */
	private static final class Placement {

		private final ServerPlayer player;
		private final Set<Long> depotIds;
		private final Map<Long, Long> sidingDepots;
		private final byte[] dataset;
		private final boolean byDuties;

		private Placement(ServerPlayer player, Set<Long> depotIds, Map<Long, Long> sidingDepots, byte[] dataset, boolean byDuties) {
			this.player = player;
			this.depotIds = depotIds;
			this.sidingDepots = sidingDepots;
			this.dataset = dataset;
			this.byDuties = byDuties;
		}

		/**
		 * Puts the depots' trains where their timetables have them now in the record the simulator starts from, instead of
		 * their trains in the game. Depots running by duties get a train on each duty out now, put straight where the duty
		 * has it (decoupling document, step D); others are run from the start of the operating day. The depots' services
		 * sent out and duties run go with them, so nothing already out is sent again. Trains a player drives stay as they
		 * are.
		 *
		 * @return how many trains each depot has now, or null if it couldn't be worked out
		 */
		private Map<Long, Integer> takeInto(RailwayData railwayData, RunRecord record, long now, java.util.function.LongSupplier trainIds) {
			final long startNanos = System.nanoTime();
			final RunRecord dayRecord;
			try {
				if (byDuties) {
					final TickRunner<SimDataset.DataTrack> runner = SimDataset.start(dataset, now).get();
					runner.setTrainIds(trainIds);
					runner.placeDutyTrains();
					dayRecord = runner.record();
				} else {
					final java.util.function.Supplier<TickRunner<SimDataset.DataTrack>> start = SimDataset.start(dataset, DutyShapes.getDayStart(now));
					final EventSimulator<SimDataset.DataTrack> day = new EventSimulator<>(() -> {
						final TickRunner<SimDataset.DataTrack> runner = start.get();
						runner.setTrainIds(trainIds);
						return runner;
					});
					day.advanceTo(now);
					dayRecord = day.getRunner().record();
				}
			} catch (Exception e) {
				e.printStackTrace();
				player.displayClientMessage(Text.translatable("gui.mtr.place_trains_failed"), false);
				return null;
			}
			System.out.println("Minecraft Transit Railway: worked out where the trains of depots " + depotIds + " are now by their timetables" + (byDuties ? ", from their duties," : "") + " in " + (System.nanoTime() - startNanos) / 1000000 + " ms");
			final Set<Long> placedSidings = sidingDepots.keySet();
			final Set<Long> driven = new HashSet<>();
			railwayData.sidings.forEach(siding -> siding.forEachTrain(train -> {
				if (train.isCurrentlyManual()) {
					driven.add(train.id);
				}
			}));
			record.trains.removeIf(train -> placedSidings.contains(train.sidingId) && !driven.contains(train.trainId));
			record.trains.addAll(dayRecord.trains);
			record.lastDeployedTrips.keySet().removeAll(placedSidings);
			record.lastDeployedTrips.putAll(dayRecord.lastDeployedTrips);
			record.dutyRunDays.keySet().removeAll(placedSidings);
			record.dutyRunDays.putAll(dayRecord.dutyRunDays);
			record.lastDeployedMillis.keySet().removeAll(placedSidings);
			record.lastDeployedMillis.putAll(dayRecord.lastDeployedMillis);
			record.deployIndices.keySet().removeAll(placedSidings);
			record.deployIndices.putAll(dayRecord.deployIndices);

			final Map<Long, Integer> counts = new HashMap<>();
			dayRecord.trains.forEach(train -> counts.merge(sidingDepots.getOrDefault(train.sidingId, 0L), 1, Integer::sum));
			System.out.println("Minecraft Transit Railway: put the trains of depots " + depotIds + " where their timetables have them now, " + dayRecord.trains.size() + " trains");
			return counts;
		}
	}

	/**
	 * The game moves the trains again, from where the simulator had them.
	 */
	private void stop(RailwayData railwayData) {
		if (simulator != null && driving) {
			railwayData.sidings.forEach(siding -> {
				if (siding.isRunBySimulator()) {
					siding.stopSimulated();
				}
			});
			railwayData.depots.forEach(depot -> depot.runBySimulator = false);
		}
		simulator = null;
	}

	/**
	 * Has the game's trains of the sidings the simulator runs show its trains, and its depots' records copied to the game's
	 * depots so they are saved.
	 */
	private void show(RailwayData railwayData) {
		final TickRunner<SimDataset.DataTrack> runner = simulator.getRunner();
		final Map<Long, List<TickRunner<SimDataset.DataTrack>.HeadlessTrain>> bySiding = new HashMap<>();
		runner.getTrains().forEach(train -> {
			if (!takenOver.contains(train.id)) {
				// A train the game can't show, as from a mistake in the simulator, turns it off instead of being saved
				if (!Float.isFinite(train.getSpeed()) || !Double.isFinite(train.getRailProgress()) || train.getNextStoppingIndex() < 0 || train.getNextStoppingIndex() >= train.getPath().size() && !train.getPath().isEmpty()) {
					throw new IllegalStateException("The simulator has train " + train.id + " at " + train.getRailProgress() + ", speed " + train.getSpeed() + ", next stop " + train.getNextStoppingIndex() + " of " + train.getPath().size());
				}
				bySiding.computeIfAbsent(train.getSidingId(), key -> new ArrayList<>()).add(train);
			}
		});
		final Set<Long> copiedDepots = new HashSet<>();
		// The game's trains by ID before any siding shows the simulator's, so a train it moved to another siding of its pool
		// is the same train there (P2-C)
		final Map<Long, TrainServer> known = new HashMap<>();
		railwayData.sidings.forEach(siding -> {
			if (sidingIds.contains(siding.id)) {
				siding.indexTrains(known);
			}
		});
		railwayData.sidings.forEach(siding -> {
			if (sidingIds.contains(siding.id)) {
				final List<TickRunner<SimDataset.DataTrack>.HeadlessTrain> trains = bySiding.getOrDefault(siding.id, Collections.emptyList());
				siding.showSimulatedTrains(trains, takenOver, known);
				siding.showSimulatedShed(runner.getShed(siding.id), known);
				final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
				if (depot != null) {
					depot.runBySimulator = true;
					if (!trains.isEmpty() && copiedDepots.add(depot.id)) {
						depot.copyDispatcher(trains.get(0).getDepot());
					}
				}
			}
		});
	}

	/**
	 * @return whether the sidings the simulator can run, their paths or the plans changed since it started, looked at a
	 * moment after the game's data or signal blocks changed and every few minutes, or at once when a siding found its path
	 * again
	 */
	private boolean hasChanged(RailwayData railwayData, long now) {
		if (railwayData.sidings.stream().anyMatch(siding -> sidingIds.contains(siding.id) && siding.hasTrainsRelocated())) {
			return true;
		}
		// Looked at once a change to the game's data has settled, and every few minutes in case something changed unannounced
		final long version = getVersion(railwayData);
		if (version != checkedVersion && versionChangedMillis == 0) {
			versionChangedMillis = now;
		}
		final boolean settled = versionChangedMillis > 0 && now >= versionChangedMillis + CHANGE_SETTLE_MILLIS;
		if (!settled && now < checkedMillis + CHANGE_CHECK_MILLIS) {
			return false;
		}
		checkedMillis = now;
		checkedVersion = version;
		versionChangedMillis = 0;
		if (!getRunSidingIds(railwayData).equals(sidingIds)) {
			return true;
		}
		final SimDataset dataset = getDataset(railwayData);
		if (Arrays.equals(dataset.toBytes(), startedData)) {
			return false;
		}
		System.out.println("Minecraft Transit Railway: the component's simulator starts again from the game's trains, as these changed: " + describeChange(SimDataset.fromBytes(startedData), dataset));
		// What changed may let it carry on the trains of the sidings it left to the game
		excludedSidings.clear();
		return true;
	}

	/**
	 * @return the sidings it can run, without the ones it left to the game
	 */
	private Set<Long> getRunSidingIds(RailwayData railwayData) {
		final Set<Long> ids = getSidingIds(railwayData);
		ids.removeAll(excludedSidings);
		return ids;
	}

	private static long getVersion(RailwayData railwayData) {
		return (long) railwayData.dataCache.getVersion() << 32 | railwayData.getSignalBlocks().getVersion() & 0xFFFFFFFFL;
	}

	/**
	 * @return which parts of the world as the simulator has it differ, for the log
	 */
	private static String describeChange(SimDataset before, SimDataset after) {
		final List<String> changed = new ArrayList<>();
		if (!Arrays.equals(part(before, 0), part(after, 0))) {
			changed.add("routes");
		}
		for (int i = 0; i < Math.max(before.depots.size(), after.depots.size()); i++) {
			final SimDataset.DepotData depot1 = i < before.depots.size() ? before.depots.get(i) : null;
			final SimDataset.DepotData depot2 = i < after.depots.size() ? after.depots.get(i) : null;
			if (depot1 == null || depot2 == null || depot1.id != depot2.id) {
				changed.add("depots");
				break;
			}
			if (!Arrays.equals(depot1.departures, depot2.departures)) {
				changed.add("departures of depot " + depot1.id);
			}
			if (!Arrays.equals(depot1.sidingOrder, depot2.sidingOrder)) {
				changed.add("sidings of depot " + depot1.id);
			}
			// Only a depot sending trains out by frequency runs its routes in turn; for the others they're a set
			final boolean sameRoutes = depot1.duties.length > 0 || depot2.duties.length > 0 ? sortedCopy(depot1.routeIds).equals(sortedCopy(depot2.routeIds)) : Arrays.equals(depot1.routeIds, depot2.routeIds);
			if (!sameRoutes || depot1.cycleRoutes != depot2.cycleRoutes || !Arrays.equals(depot1.duties, depot2.duties)) {
				changed.add("depot " + depot1.id);
			}
		}
		if (!Arrays.equals(part(before, 2), part(after, 2))) {
			if (before.sidings.size() != after.sidings.size()) {
				changed.add("sidings");
			} else {
				for (int i = 0; i < before.sidings.size(); i++) {
					final SimDataset.SidingData siding1 = before.sidings.get(i);
					final SimDataset.SidingData siding2 = after.sidings.get(i);
					if (siding1.tripPaths.size() != after.sidings.get(i).tripPaths.size()) {
						changed.add("service paths of siding " + siding1.id);
					} else if (siding1.path.size() != siding2.path.size()) {
						changed.add("path of siding " + siding1.id);
					} else if (!Arrays.equals(siding1.turnBacks, siding2.turnBacks)) {
						changed.add("turn backs of siding " + siding1.id);
					} else if (siding1.manual != siding2.manual || siding1.maxTrains != siding2.maxTrains || siding1.trainCars != siding2.trainCars || siding1.acceleration != siding2.acceleration || siding1.deceleration != siding2.deceleration || siding1.usesSpeedCurve != siding2.usesSpeedCurve) {
						changed.add("settings of siding " + siding1.id);
					} else if (!Arrays.equals(part(single(siding1), 2), part(single(siding2), 2))) {
						changed.add("siding " + siding1.id);
					}
				}
			}
		}
		if (!before.signalBlocks.equals(after.signalBlocks)) {
			changed.add("signal blocks");
		}
		return changed.isEmpty() ? "something" : String.join(", ", changed);
	}

	private static SimDataset single(SimDataset.SidingData siding) {
		final SimDataset dataset = new SimDataset();
		dataset.sidings.add(siding);
		return dataset;
	}

	/**
	 * @return the routes (0) or sidings (2) alone, written out
	 */
	private static byte[] part(SimDataset dataset, int which) {
		final SimDataset part = new SimDataset();
		if (which == 0) {
			part.routes.addAll(dataset.routes);
		} else {
			part.sidings.addAll(dataset.sidings);
		}
		return part.toBytes();
	}

	/**
	 * @return the world as the simulator needs it, with only the sidings and depots it runs, and without trains
	 */
	private SimDataset getDataset(RailwayData railwayData) {
		final SimDataset dataset = SimExport.toDataset(railwayData);
		dataset.trains = null;
		dataset.sidings.removeIf(siding -> !sidingIds.contains(siding.id));
		final Set<Long> depotIds = new HashSet<>();
		dataset.sidings.forEach(siding -> depotIds.add(siding.depotId));
		dataset.depots.removeIf(depot -> !depotIds.contains(depot.id));
		return dataset;
	}

	/**
	 * The sidings of depots with speed curves, not moving continuously, that have a path and a vehicle.
	 */
	private static Set<Long> getSidingIds(RailwayData railwayData) {
		final Map<Long, List<Siding>> depotSidings = new HashMap<>();
		railwayData.sidings.forEach(siding -> {
			final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
			if (depot != null) {
				depotSidings.computeIfAbsent(depot.id, key -> new ArrayList<>()).add(siding);
			}
		});
		final Set<Long> sidingIds = new TreeSet<>();
		depotSidings.forEach((depotId, sidings) -> {
			final Depot depot = railwayData.dataCache.depotIdMap.get(depotId);
			if (depot != null && depot.speedCurves && !depot.transportMode.continuousMovement) {
				sidings.forEach(siding -> {
					if (siding.canBeSimulated()) {
						sidingIds.add(siding.id);
					}
				});
			}
		});
		return sidingIds;
	}

	private void forEachTrain(RailwayData railwayData, Consumer<TrainServer> consumer) {
		railwayData.sidings.stream().filter(siding -> sidingIds.contains(siding.id)).sorted().forEach(siding -> siding.forEachTrain(consumer));
	}

	private static List<Long> sortedCopy(long[] ids) {
		final List<Long> list = new ArrayList<>();
		for (final long id : ids) {
			list.add(id);
		}
		Collections.sort(list);
		return list;
	}
}
