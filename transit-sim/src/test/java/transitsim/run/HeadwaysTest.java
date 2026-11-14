package transitsim.run;

import org.junit.jupiter.api.Test;
import transitsim.motion.SpeedCurves;
import transitsim.plan.PathTimes;
import transitsim.plan.StopTimes;
import transitsim.plan.TimeSegment;
import transitsim.sim.RunTrack;
import transitsim.sim.SimDataset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The least time between two trains through each block section (B, 2026-11-14). Line 7 of the test lines, run offline
 * (simulateDay --headways against the arrivals of --events), worked out 40.5 s at most where trains ran 39.8 s apart.
 */
class HeadwaysTest {

	private static final double LENGTH = 75;
	private static final double GAP = 25;
	// 4 m/s² as the test lines' trains, in blocks per tick²
	private static final double DECELERATION = 4.0 / 400;

	private static SimDataset.DataTrack platform(long from, long to, long stopId) {
		return new SimDataset.DataTrack(from, to, 60, true, false, stopId, 40, 0, 1, false, true, false);
	}

	private static SimDataset.DataTrack line(long from, long to, double length) {
		return new SimDataset.DataTrack(from, to, length, false, false, 0, 0, 0, 1.11F, true, true, false);
	}

	private static Headways.Train train(List<SimDataset.DataTrack> path) {
		final List<TimeSegment> times = new ArrayList<>();
		final SpeedCurves curves = SpeedCurves.of(RunTrack.toTrackView(path, 0, 0, LENGTH), LENGTH, LENGTH, DECELERATION, DECELERATION);
		PathTimes.curveTimes(path, curves, times, new HashMap<>(), new StopTimes());
		return new Headways.Train(times, LENGTH, GAP, DECELERATION);
	}

	private static long most(List<Headways.Gap> gaps) {
		return gaps.stream().mapToLong(gap -> gap.millis).max().orElse(0);
	}

	@Test
	void aLongerSectionNeedsTrainsFurtherApart() {
		final List<SimDataset.DataTrack> shortLine = Arrays.asList(line(1, 2, 200), platform(2, 3, 1), line(3, 4, 300), platform(4, 5, 2), line(5, 6, 200));
		final List<SimDataset.DataTrack> longLine = Arrays.asList(line(1, 2, 200), platform(2, 3, 1), line(3, 4, 1900), platform(4, 5, 2), line(5, 6, 200));
		final long shortGap = most(Headways.find(shortLine, null, train(shortLine), train(shortLine)));
		final long longGap = most(Headways.find(longLine, null, train(longLine), train(longLine)));
		assertTrue(shortGap > 20000, "a stop of 20 s and the run out of the platform: " + shortGap);
		// A long run is cut into block sections, so it needs more time between trains, but not the time to run all of it
		assertTrue(longGap >= shortGap, shortGap + " " + longGap);
		assertTrue(longGap < shortGap + 30000, shortGap + " " + longGap);
	}

	@Test
	void aTrainStoppingBeforeASectionNeedsItOnlyToLeave() {
		final List<SimDataset.DataTrack> path = Arrays.asList(line(1, 2, 300), platform(2, 3, 1), line(3, 4, 300), platform(4, 5, 2), line(5, 6, 300));
		final Headways.Train train = train(path);
		for (final Headways.Gap gap : Headways.find(path, null, train, train)) {
			// No section needs trains as far apart as the one ahead's whole stop and run beyond the next
			assertTrue(gap.millis < 60000, gap.start + " " + gap.millis);
		}
	}

	@Test
	void aPathAlongTheSameRailsAgainIsCutTheSameEachTime() {
		final SimDataset.DataTrack out = line(1, 2, 1000);
		final SimDataset.DataTrack back = line(2, 1, 1000);
		final List<SimDataset.DataTrack> path = Arrays.asList(platform(10, 1, 1), out, platform(2, 20, 2), platform(20, 2, 2), back, platform(1, 10, 1), platform(10, 1, 1), out, platform(2, 20, 2));
		final Headways.Train train = train(path);
		final List<Headways.Gap> gaps = Headways.find(path, null, train, train);
		// Every section of the long rail is a block section, the second time along it as the first
		for (final Headways.Gap gap : gaps) {
			assertTrue(gap.end - gap.start <= 400 + 1E-6 || gap.end - gap.start <= 60 + 1E-6, gap.start + ".." + gap.end);
		}
	}
}
