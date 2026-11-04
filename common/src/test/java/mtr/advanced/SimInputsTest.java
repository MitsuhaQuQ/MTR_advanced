package mtr.advanced;

import org.junit.jupiter.api.Test;
import transitsim.sim.HostInput;
import transitsim.sim.RunReservations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trains the simulator doesn't move, reported from the rails the game has them on (G0), and the runs they hold (G1).
 */
class SimInputsTest {

	private static final UUID A = new UUID(0, 1);
	private static final UUID B = new UUID(0, 2);
	private static final UUID C = new UUID(0, 3);

	@Test
	void onlyExternalTrainsAreReportedAndOnlyWhenTheirRailsChange() {
		final SimInputs inputs = new SimInputs();
		// Train 1 is driven by hand, train 2 is run by the simulator
		final List<HostInput.ExternalTrain> first = inputs.update(100, occupancy(A, 1, B, 1, C, 2), id -> id == 1);
		assertEquals(1, first.size());
		assertEquals(1, first.get(0).trainId);
		assertEquals(new HashSet<>(Arrays.asList(A, B)), first.get(0).rails);
		assertEquals(100, first.get(0).time);

		// Nothing changed
		assertTrue(inputs.update(150, occupancy(A, 1, B, 1, C, 2), id -> id == 1).isEmpty());

		// It moves on from A to C
		final List<HostInput.ExternalTrain> moved = inputs.update(200, occupancy(B, 1, C, 1), id -> id == 1);
		assertEquals(1, moved.size());
		assertEquals(new HashSet<>(Arrays.asList(B, C)), moved.get(0).rails);
		assertTrue(moved.get(0).sequence > first.get(0).sequence);
	}

	@Test
	void aTrainThatHasGoneIsReportedWithNoRailsOnce() {
		final SimInputs inputs = new SimInputs();
		inputs.update(100, occupancy(A, 1, B, 3), id -> true);
		final List<HostInput.ExternalTrain> gone = inputs.update(200, occupancy(B, 3), id -> true);
		assertEquals(1, gone.size());
		assertEquals(1, gone.get(0).trainId);
		assertTrue(gone.get(0).rails.isEmpty());
		assertTrue(inputs.update(300, occupancy(B, 3), id -> true).isEmpty());
		// And reported again when it comes back
		assertEquals(1, inputs.update(400, occupancy(A, 1, B, 3), id -> true).size());
	}

	@Test
	void aTrainTheSimulatorTakesBackIsReportedAsGone() {
		final SimInputs inputs = new SimInputs();
		inputs.update(100, occupancy(A, 5), id -> true);
		// The player stops driving it, so the simulator runs it again
		final List<HostInput.ExternalTrain> released = inputs.update(200, occupancy(A, 5), id -> false);
		assertEquals(Collections.singletonList(5L), released.stream().map(input -> input.trainId).collect(Collectors.toList()));
		assertTrue(released.get(0).rails.isEmpty());
	}

	@Test
	void trainsComeInTheOrderOfTheirIds() {
		final SimInputs inputs = new SimInputs();
		final List<HostInput.ExternalTrain> reported = inputs.update(100, occupancy(A, 9, B, 4, C, 7), id -> true);
		assertEquals(Arrays.asList(4L, 7L, 9L), reported.stream().map(input -> input.trainId).collect(Collectors.toList()));
		assertEquals(Arrays.asList(0L, 1L, 2L), reported.stream().map(input -> input.sequence).collect(Collectors.toList()));
	}

	@Test
	void afterAResetEveryTrainIsReportedAgain() {
		final SimInputs inputs = new SimInputs();
		inputs.update(100, occupancy(A, 1), id -> true);
		inputs.updateRuns(100, runs(2, A));
		assertTrue(inputs.update(150, occupancy(A, 1), id -> true).isEmpty());
		// A new simulator knows nothing of them
		inputs.reset();
		assertEquals(1, inputs.update(200, occupancy(A, 1), id -> true).size());
		assertEquals(1, inputs.updateRuns(200, runs(2, A)).size());
	}

	@Test
	void runsAreReportedWhenTheyChangeAndOnceWhenLetGo() {
		final SimInputs inputs = new SimInputs();
		final List<HostInput.ExternalRun> first = inputs.updateRuns(100, runs(2, A));
		assertEquals(1, first.size());
		assertEquals(2, first.get(0).trainId);
		assertEquals(Collections.singletonList(new RunReservations.Move(A, true)), first.get(0).moves);
		assertTrue(inputs.updateRuns(150, runs(2, A)).isEmpty());
		assertEquals(Arrays.asList(new RunReservations.Move(A, true), new RunReservations.Move(B, true)), inputs.updateRuns(200, runs(2, A, B)).get(0).moves);
		final List<HostInput.ExternalRun> letGo = inputs.updateRuns(250, new TreeMap<>());
		assertEquals(1, letGo.size());
		assertTrue(letGo.get(0).moves.isEmpty());
		assertTrue(inputs.updateRuns(300, new TreeMap<>()).isEmpty());
	}

	private static Map<Long, List<RunReservations.Move>> runs(long trainId, UUID... rails) {
		final Map<Long, List<RunReservations.Move>> runs = new TreeMap<>();
		runs.put(trainId, Arrays.stream(rails).map(rail -> new RunReservations.Move(rail, true)).collect(Collectors.toList()));
		return runs;
	}

	private static Map<UUID, Long> occupancy(Object... railsAndTrains) {
		final Map<UUID, Long> occupancy = new HashMap<>();
		for (int i = 0; i < railsAndTrains.length; i += 2) {
			occupancy.put((UUID) railsAndTrains[i], ((Integer) railsAndTrains[i + 1]).longValue());
		}
		return occupancy;
	}
}
