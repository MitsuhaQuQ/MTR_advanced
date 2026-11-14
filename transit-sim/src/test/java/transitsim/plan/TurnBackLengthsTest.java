package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Turn-back tracks too short for the train (2026-11-14).
 */
class TurnBackLengthsTest {

	private static final class Track implements PlanTrack {

		final String from, to;
		final double length;
		final boolean turnBack;
		private int stopIndex;

		Track(String from, String to, double length, boolean turnBack) {
			this.from = from;
			this.to = to;
			this.length = length;
			this.turnBack = turnBack;
		}

		@Override
		public double getTrackLength() {
			return length;
		}

		@Override
		public int getDwellTime() {
			return 0;
		}

		@Override
		public boolean isPlatformTrack() {
			return false;
		}

		@Override
		public boolean isSidingTrack() {
			return false;
		}

		@Override
		public long getStopId() {
			return 0;
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
		public UUID getTrackKey() {
			return UUID.nameUUIDFromBytes((from + to).getBytes());
		}

		@Override
		public boolean isOppositeOf(PlanTrack track) {
			return track instanceof Track && ((Track) track).from.equals(to) && ((Track) track).to.equals(from);
		}
	}

	private static Track main(String from, String to, double length) {
		return new Track(from, to, length, false);
	}

	private static Track turnBack(String from, String to, double length) {
		return new Track(from, to, length, true);
	}

	@Test
	void aDeadEndTrackShorterThanTheTrainIsFound() {
		// Line 6 of the test lines: 75-block trains on a 50-block dead end
		final List<Track> path = Arrays.asList(main("a", "b", 60), main("b", "c", 40), turnBack("c", "d", 50), turnBack("d", "c", 50), main("c", "e", 40));
		final List<TurnBackLengths.Short> found = TurnBackLengths.find(path, track -> track.turnBack, 75);
		assertEquals(1, found.size());
		assertEquals(2, found.get(0).rail);
		assertEquals(50, found.get(0).length, 1E-9);
	}

	@Test
	void turnBackTrackOfSeveralRailsLongEnoughIsFine() {
		final List<Track> path = Arrays.asList(main("a", "b", 60), turnBack("b", "c", 40), turnBack("c", "d", 40), turnBack("d", "c", 40), main("c", "x", 10));
		assertTrue(TurnBackLengths.find(path, track -> track.turnBack, 75).isEmpty(), "80 blocks hold a 75-block train");
		// Within half a block is the train's length rounded, not short
		assertTrue(TurnBackLengths.find(path, track -> track.turnBack, 80.3).isEmpty());
		assertEquals(1, TurnBackLengths.find(path, track -> track.turnBack, 81).size());
	}

	@Test
	void turningBackOnAPlatformOrMainTrackIsNotCounted() {
		final List<Track> path = Arrays.asList(main("a", "b", 60), main("b", "a", 60));
		assertTrue(TurnBackLengths.find(path, track -> track.turnBack, 200).isEmpty());
		// Running through a turn-back track without turning back on it
		final List<Track> through = Arrays.asList(turnBack("a", "b", 20), main("b", "c", 60));
		assertTrue(TurnBackLengths.find(through, track -> track.turnBack, 200).isEmpty());
	}
}
