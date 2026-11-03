package mtr.advanced;

import mtr.data.Depot;
import mtr.data.MotionConfig;
import mtr.data.RailwayData;
import mtr.data.Siding;
import mtr.data.TrainServer;
import transitsim.sim.EventSimulator;
import transitsim.sim.HostInput;
import transitsim.sim.RunRecord;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The component's simulator running the trains of the depots it can run (G1, decoupling document 5.2): depots with speed
 * curves, not moving continuously, that send their trains out by a timetable or by duties, with sidings not driven by
 * hand. The trains of the other depots are told to it as external trains ({@link SimInputs}).
 * <p>
 * Running the trains ({@link MotionConfig#COMPONENT_DRIVE_ON}), the simulator has them: each tick it moves on to now,
 * and the game's trains of those sidings only show its trains (A06), doing what needs the world, such as their cars,
 * riders, doors and the rails they take for the game's other trains and signals. It starts again from the game's trains,
 * which then are where it had them, when the network or the plans change.
 * <p>
 * Compared ({@link MotionConfig#COMPONENT_DRIVE_COMPARE}), it runs next to the game, which still moves every train. After
 * a minute it says in the log how far apart the two came, and starts again from the game's trains.
 */
public final class LiveSimulation {

	/**
	 * How long a comparison runs before it is reported and started again.
	 */
	private static final long COMPARE_MILLIS = 60000;
	/**
	 * How often, while it runs the trains, it looks whether the network or the plans changed.
	 */
	private static final long CHANGE_CHECK_MILLIS = 30000;
	/**
	 * How often it looks again for sidings it can run while it has none, as the depots' timetables are only known a moment
	 * after the world is loaded.
	 */
	private static final long RETRY_MILLIS = 1000;
	/**
	 * How far apart a train of the game and of the simulator can be and still count as alike.
	 */
	private static final double ALIKE_DISTANCE = 1;

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
	private long lastMillis;
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
	 * The setting the simulator failed with, or -1.
	 */
	private int failedDrive = -1;

	/**
	 * Runs the simulator on to now, before the game's sidings move their trains in this tick.
	 *
	 * @param trainPositions the game's rails taken, of the tick before and of this one
	 */
	public void tick(RailwayData railwayData, List<Map<UUID, Long>> trainPositions) {
		final int componentDrive = MotionConfig.useLegacyMotion(false) ? MotionConfig.COMPONENT_DRIVE_OFF : MotionConfig.getComponentDrive(false);
		if (componentDrive != failedDrive) {
			failedDrive = -1;
		}
		if (componentDrive == MotionConfig.COMPONENT_DRIVE_OFF || failedDrive >= 0 || simulator != null && driving != (componentDrive == MotionConfig.COMPONENT_DRIVE_ON)) {
			stop(railwayData);
			if (componentDrive == MotionConfig.COMPONENT_DRIVE_OFF || failedDrive >= 0) {
				return;
			}
		}
		try {
			run(railwayData, trainPositions, componentDrive == MotionConfig.COMPONENT_DRIVE_ON);
		} catch (Exception e) {
			// Nothing of the simulator may stop the game's tick; it stays off until the setting is changed, and the game moves
			// the trains on from where the simulator had them
			System.out.println("Minecraft Transit Railway: the component's simulator failed and is off until its setting is changed");
			e.printStackTrace();
			failedDrive = componentDrive;
			stop(railwayData);
		}
	}

	private void run(RailwayData railwayData, List<Map<UUID, Long>> trainPositions, boolean drive) {
		// Which depot each siding is in is only known a moment after the world is loaded
		if (!started && !railwayData.sidings.isEmpty() && railwayData.dataCache.sidingIdToDepot.isEmpty()) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (simulator != null && !drive && now >= startedMillis + COMPARE_MILLIS) {
			System.out.println("Minecraft Transit Railway: " + compare(railwayData));
			simulator = null;
		}
		if (simulator != null && drive) {
			// A stop of more than a minute is recovered from (contracts document 6.3), and a change starts it again
			final boolean stopped = now - lastMillis > EventSimulator.RECOVERY_MILLIS;
			if (stopped || hasChanged(railwayData, now)) {
				start(railwayData, now, true, stopped ? lastMillis : now);
			}
		} else if (simulator == null && (!started || now >= startedMillis + (sidingIds.isEmpty() ? RETRY_MILLIS : drive ? CHANGE_CHECK_MILLIS : COMPARE_MILLIS))) {
			// The first time after the world is loaded, the trains are put where the plan has them, as the game does
			start(railwayData, now, drive, started ? now : 0);
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
		simulator.advanceTo(now);
		lastMillis = now;
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
	 *                     has them now; 0 when that isn't known, as after the world is loaded
	 */
	private void start(RailwayData railwayData, long now, boolean drive, long recordMillis) {
		final Set<Long> newSidingIds = getSidingIds(railwayData);
		// Said when the sidings or what it does with them change; a world with none it can run says nothing
		if (!newSidingIds.equals(sidingIds) || driving != drive && !newSidingIds.isEmpty()) {
			System.out.println("Minecraft Transit Railway: the component's simulator " + (drive ? "runs" : "is compared on") + " " + newSidingIds.size() + " of " + railwayData.sidings.size() + " sidings, the ones of depots with speed curves sending trains out by a timetable or by duties, without driving by hand");
		}
		stop(railwayData);
		started = true;
		driving = drive;
		sidingIds = newSidingIds;
		startedMillis = now;
		checkedMillis = now;
		lastMillis = now;
		reportedMillis = now;
		departures = 0;
		arrivals = 0;
		takenOver.clear();
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
		recordedTrains = record.trains.size();
		simulator = EventSimulator.resume(startMillis -> {
			final TickRunner<SimDataset.DataTrack> runner = dataset.buildRunner(startMillis);
			runner.setTrainIds(random::nextLong);
			return runner;
		}, record, now);
		simulator.setLive();
		eventCursor = 0;
		simulator.forgetEvents(Long.MAX_VALUE);
		restoredTrains = simulator.getRunner().getTrains().size();
		if (drive) {
			show(railwayData);
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
				bySiding.computeIfAbsent(train.getSidingId(), key -> new ArrayList<>()).add(train);
			}
		});
		final Set<Long> copiedDepots = new HashSet<>();
		railwayData.sidings.forEach(siding -> {
			if (sidingIds.contains(siding.id)) {
				final List<TickRunner<SimDataset.DataTrack>.HeadlessTrain> trains = bySiding.getOrDefault(siding.id, Collections.emptyList());
				siding.showSimulatedTrains(trains);
				final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
				if (depot != null) {
					depot.runBySimulator = true;
					if (!trains.isEmpty() && copiedDepots.add(depot.id)) {
						depot.copyDispatcher(trains.get(0).getDispatcher());
					}
				}
			}
		});
	}

	/**
	 * @return whether the sidings the simulator can run, their paths or the plans changed since it started, looked at every
	 * half minute, or at once when a siding found its path again
	 */
	private boolean hasChanged(RailwayData railwayData, long now) {
		if (railwayData.sidings.stream().anyMatch(siding -> sidingIds.contains(siding.id) && siding.hasTrainsRelocated())) {
			return true;
		}
		if (now < checkedMillis + CHANGE_CHECK_MILLIS) {
			return false;
		}
		checkedMillis = now;
		return !getSidingIds(railwayData).equals(sidingIds) || !Arrays.equals(getDataset(railwayData).toBytes(), startedData);
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
	 * @return how the game's trains and the simulator's compare now
	 */
	private String compare(RailwayData railwayData) {
		final TickRunner<SimDataset.DataTrack> runner = simulator.getRunner();
		final Set<Long> inGame = new HashSet<>();
		final int[] counts = new int[4];
		final double[] largest = {0};
		final long[] largestTrain = {0};
		final String[] firstDifferent = {""};
		forEachTrain(railwayData, train -> {
			if (train.isCurrentlyManual()) {
				return;
			}
			inGame.add(train.id);
			counts[0]++;
			final TickRunner<SimDataset.DataTrack>.HeadlessTrain simulated = runner.getTrain(train.id);
			if (simulated == null) {
				counts[2]++;
				return;
			}
			final RunRecord.TrainRecord gameTrain = train.toRecord();
			final double distance = Math.abs(simulated.getRailProgress() - gameTrain.railProgress);
			if (distance > largest[0]) {
				largest[0] = distance;
				largestTrain[0] = train.id;
			}
			final boolean sameState = simulated.isOnRoute() == gameTrain.onRoute && simulated.getNextStoppingIndex() == gameTrain.nextStoppingIndex;
			if (sameState && distance <= ALIKE_DISTANCE) {
				counts[1]++;
			} else if (firstDifferent[0].isEmpty()) {
				firstDifferent[0] = String.format(", first different: train %d at %.1f (simulated %.1f), next stop %d (simulated %d), %s (simulated %s)", train.id, gameTrain.railProgress, simulated.getRailProgress(), gameTrain.nextStoppingIndex, simulated.getNextStoppingIndex(), gameTrain.onRoute ? "on the line" : "in the siding", simulated.isOnRoute() ? "on the line" : "in the siding");
			}
		});
		runner.getTrains().forEach(train -> {
			if (!inGame.contains(train.id) && !takenOver.contains(train.id)) {
				counts[3]++;
			}
		});
		return String.format("the simulator ran %d sidings for a minute next to the game (started with %d of %d trains): %d trains, %d alike within %.0f m, largest gap %.1f m (train %d), %d only in the game, %d only in the simulator%s", sidingIds.size(), restoredTrains, recordedTrains, counts[0], counts[1], ALIKE_DISTANCE, largest[0], largestTrain[0], counts[2], counts[3], firstDifferent[0]);
	}

	/**
	 * The sidings of depots with speed curves, not moving continuously, that send their trains out by a timetable or by
	 * duties, as the simulator doesn't send trains out by frequency; and not driven by hand, as such a siding's trains only
	 * leave when a player drives them.
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
			if (depot != null && depot.speedCurves && !depot.transportMode.continuousMovement && (depot.duties.diagramMode || sidings.stream().allMatch(siding -> depot.getTimetable(siding.getTrainId()) != null))) {
				sidings.forEach(siding -> {
					if (!siding.getIsManual()) {
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
}
