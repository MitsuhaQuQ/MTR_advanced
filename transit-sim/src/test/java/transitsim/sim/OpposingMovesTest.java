package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.plan.OpposingMoves;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The depot's hint of conflicting routes (item A of MTR-待验证.md), on the paths of {@link DeadEndTurnBackTest}: train 1
 * changes ends from W through the dead end T, turning twice, against train 2 coming out of T to W.
 */
class OpposingMovesTest {

	@Test
	void theDeadEndSwapIsFound() {
		final List<OpposingMoves.Move<ScenarioTrack>> moves = new ArrayList<>();
		moves.addAll(OpposingMoves.movesOf(DeadEndTurnBackTest.trainOnePath()));
		moves.addAll(OpposingMoves.movesOf(DeadEndTurnBackTest.trainTwoPath()));
		// The same paths again, as from another siding: each move is told once
		moves.addAll(OpposingMoves.movesOf(DeadEndTurnBackTest.trainOnePath()));
		final List<String> found = new ArrayList<>();
		OpposingMoves.find(moves).forEach(finding -> found.add(finding.toString()));
		assertEquals(Arrays.asList("turns 2 9 2", "against 2 9 2 1 2 1"), found);
	}

	@Test
	void oneLineTurningBackThroughTheDeadEndIsFine() {
		// Train 2's move alone, from two sidings: one turn, nothing against it
		final List<OpposingMoves.Move<ScenarioTrack>> moves = new ArrayList<>();
		moves.addAll(OpposingMoves.movesOf(DeadEndTurnBackTest.trainTwoPath()));
		moves.addAll(OpposingMoves.movesOf(DeadEndTurnBackTest.trainTwoPath()));
		assertEquals(1, moves.size() / 2);
		assertEquals(Collections.emptyList(), OpposingMoves.find(moves));
	}

	@Test
	void turningOnThePlatformAloneIsNoMoveAgainstAnything() {
		// A terminus turned back at on its platform, as ScenarioLine's: the turn is at the stop, nothing runs against it
		final ScenarioTrack terminal = ScenarioTrack.platform(6, 7, 3, 20, true);
		final List<ScenarioTrack> path = Arrays.asList(
				ScenarioTrack.siding(0, 1, 100, 50),
				ScenarioTrack.plain(1, 2, 60, false),
				ScenarioTrack.platform(2, 3, 1, 20, false),
				ScenarioTrack.plain(3, 6, 100, false),
				terminal,
				terminal.reversed(20),
				ScenarioTrack.plain(6, 15, 100, false),
				ScenarioTrack.platform(15, 14, 5, 20, false),
				ScenarioTrack.plain(14, 1, 60, false),
				ScenarioTrack.siding(0, 1, 100, 50).reversed(0)
		);
		assertEquals(Collections.emptyList(), OpposingMoves.find(OpposingMoves.movesOf(path)));
	}
}
