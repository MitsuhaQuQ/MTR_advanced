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
import transitsim.sim.TickRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The component's simulator running the trains of the depots it can run (G1, decoupling document 5.2): depots with speed
 * curves that send their trains out by a timetable or by duties. The trains of the other depots, and trains driven by
 * hand, are told to it as external trains ({@link SimInputs}).
 * <p>
 * Compared ({@link MotionConfig#COMPONENT_DRIVE_COMPARE}), it runs next to the game, which still moves every train. It
 * starts from the game's trains as they are, and after a minute says in the log how far apart the two came, then starts
 * again from the game's trains, so a change to the network or the plans is picked up within a minute.
 */
public final class LiveSimulation {

	/**
	 * How long a comparison runs before it is reported and started again.
	 */
	private static final long COMPARE_MILLIS = 60000;
	/**
	 * How far apart a train of the game and of the simulator can be and still count as alike.
	 */
	private static final double ALIKE_DISTANCE = 1;

	private final SimInputs inputs = new SimInputs();
	private final Random random = new Random();
	private EventSimulator<SimDataset.DataTrack> simulator;
	/**
	 * The sidings the simulator runs, and when it was started.
	 */
	private Set<Long> sidingIds = Collections.emptySet();
	private long startedMillis;
	private long lastMillis;
	private int restoredTrains;
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
		final int componentDrive = MotionConfig.getComponentDrive(false);
		if (componentDrive != failedDrive) {
			failedDrive = -1;
		}
		if (componentDrive == MotionConfig.COMPONENT_DRIVE_OFF || MotionConfig.useLegacyMotion(false) || failedDrive >= 0) {
			simulator = null;
			return;
		}
		try {
			run(railwayData, trainPositions);
		} catch (Exception e) {
			// Nothing of the simulator may stop the game's tick; it stays off until the setting is changed
			System.out.println("Minecraft Transit Railway: the component's simulator failed and is off until its setting is changed");
			e.printStackTrace();
			failedDrive = componentDrive;
			simulator = null;
		}
	}

	private void run(RailwayData railwayData, List<Map<UUID, Long>> trainPositions) {
		final long now = System.currentTimeMillis();
		if (simulator != null && now >= startedMillis + COMPARE_MILLIS) {
			System.out.println("Minecraft Transit Railway: " + compare(railwayData));
			simulator = null;
		}
		if (simulator == null && now >= startedMillis + COMPARE_MILLIS || simulator == null && startedMillis == 0) {
			start(railwayData, now);
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
		if (trainPositions.size() > 0) {
			inputs.update(now, trainPositions.get(0), trainId -> !run.contains(trainId)).forEach(simulator::submit);
		}
		simulator.advanceTo(now);
		lastMillis = now;
	}

	/**
	 * Starts the simulator from the game's trains of the depots it can run, carrying on as they are.
	 */
	private void start(RailwayData railwayData, long now) {
		lastMillis = now;
		takenOver.clear();
		final Set<Long> newSidingIds = getSidingIds(railwayData);
		if ((!newSidingIds.equals(sidingIds) || startedMillis == 0) && !railwayData.sidings.isEmpty()) {
			System.out.println("Minecraft Transit Railway: the component's simulator is compared on " + newSidingIds.size() + " of " + railwayData.sidings.size() + " sidings, the ones of depots with speed curves sending trains out by a timetable or by duties");
		}
		sidingIds = newSidingIds;
		startedMillis = now;
		if (sidingIds.isEmpty()) {
			return;
		}

		final SimDataset dataset = SimExport.toDataset(railwayData);
		dataset.trains = null;
		dataset.sidings.removeIf(siding -> !sidingIds.contains(siding.id));
		final Set<Long> depotIds = new HashSet<>();
		dataset.sidings.forEach(siding -> depotIds.add(siding.depotId));
		dataset.depots.removeIf(depot -> !depotIds.contains(depot.id));

		final RunRecord record = new RunRecord(now);
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
		restoredTrains = simulator.getRunner().getTrains().size();
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
	 * The sidings of depots with speed curves that send their trains out by a timetable or by duties, as the simulator
	 * doesn't send trains out by frequency.
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
				sidings.forEach(siding -> sidingIds.add(siding.id));
			}
		});
		return sidingIds;
	}

	private void forEachTrain(RailwayData railwayData, java.util.function.Consumer<TrainServer> consumer) {
		railwayData.sidings.stream().filter(siding -> sidingIds.contains(siding.id)).sorted().forEach(siding -> siding.forEachTrain(consumer));
	}
}
