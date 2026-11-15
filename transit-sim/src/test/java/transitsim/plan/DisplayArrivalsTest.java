package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DisplayArrivalsTest {

	private static final long MINUTE = 60000;
	private static final long BASE = 1000 * MINUTE;
	private static final long UP = 1;
	private static final long DOWN = 2;
	private static final Object POOL = "pool";

	/**
	 * A shuttle: up from platform 1 to 2 in 4 minutes, turning back to platform 3 in 2, standing there until down at 10
	 * minutes to platform 4, and back to platform 1 in 6 for up again at 20. Platform 1 at 0, 20, 40... from the duty's start.
	 */
	private static List<List<DisplayArrivals.Stop>> shuttle(long start, int laps) {
		final List<List<DisplayArrivals.Stop>> segments = new ArrayList<>();
		for (int lap = 0; lap < laps; lap++) {
			final long t = start + lap * 20 * MINUTE;
			segments.add(Arrays.asList(
					new DisplayArrivals.Stop(1, UP, 0, t, t, false, 30000),
					new DisplayArrivals.Stop(2, UP, 1, t + 4 * MINUTE, t + 4 * MINUTE, false, 30000)));
			segments.add(Arrays.asList(
					new DisplayArrivals.Stop(3, DOWN, 0, t + 6 * MINUTE, t + 10 * MINUTE, false, 30000),
					new DisplayArrivals.Stop(4, DOWN, 1, t + 14 * MINUTE, t + 14 * MINUTE, false, 30000)));
		}
		return segments;
	}

	private static DisplayArrivals.Duty duty(String key, long start, int laps) {
		return duty(key, start, laps, false, null);
	}

	private static DisplayArrivals.Duty duty(String key, long start, int laps, boolean overnightStart, String nextKey) {
		final List<List<DisplayArrivals.Stop>> segments = shuttle(start, laps);
		return new DisplayArrivals.Duty(key, POOL, 6, overnightStart, nextKey, DisplayArrivals.leaveMillis(segments, 3 * MINUTE), DisplayArrivals.backMillis(segments, 3 * MINUTE), segments);
	}

	private static List<Long> times(DisplayArrivals.Result result, long platformId) {
		return result.get(platformId).stream().map(arrival -> arrival.estimateMillis).collect(Collectors.toList());
	}

	private static Map<Object, Integer> idle(int count) {
		final Map<Object, Integer> idle = new HashMap<>();
		idle.put(POOL, count);
		return idle;
	}

	@Test
	void laterServicesComeAtTheTimetablesIntervals() {
		final DisplayArrivals.Duty first = duty("a", BASE, 4);
		final DisplayArrivals.Duty second = duty("b", BASE + 10 * MINUTE, 4);
		// Both trains on their first service, on time, standing or running: the plan tells their later ones
		final List<DisplayArrivals.Running> running = Arrays.asList(new DisplayArrivals.Running("a", 1, 0, 1, 0), new DisplayArrivals.Running("b", 2, 0, 1, 0));
		final DisplayArrivals.Result result = DisplayArrivals.compute(Arrays.asList(first, second), running, idle(0), duty -> false, BASE + MINUTE, BASE + 1000 * MINUTE);
		final List<Long> up = times(result, 1);
		assertEquals(Arrays.asList(BASE + 20 * MINUTE, BASE + 30 * MINUTE, BASE + 40 * MINUTE, BASE + 50 * MINUTE, BASE + 60 * MINUTE, BASE + 70 * MINUTE), up);
		final List<Long> down = times(result, 3);
		for (int i = 1; i < down.size(); i++) {
			assertEquals(10 * MINUTE, down.get(i) - down.get(i - 1));
		}
		// Each told with the train running it
		assertEquals(1, result.get(1).get(0).trainId);
		assertEquals(2, result.get(1).get(1).trainId);
	}

	@Test
	void aLaterServiceOfARunningDutyIsLateOnlyByItsTrainsLatenessCarriedOn() {
		final DisplayArrivals.Duty duty = duty("a", BASE, 3);
		// Five minutes late leaving platform 1 on its first service: the layover at platform 3 takes up three and a half
		final long now = BASE + 5 * MINUTE;
		final DisplayArrivals.Result result = DisplayArrivals.compute(Collections.singletonList(duty), Collections.singletonList(new DisplayArrivals.Running("a", 1, 0, 1, BASE)), idle(0), d -> false, now, now + 1000 * MINUTE);
		assertEquals(Arrays.asList(BASE + 11 * MINUTE, BASE + 27 * MINUTE + 30000, BASE + 46 * MINUTE), times(result, 3));
		// Carried on round the loop, until the next layover takes the rest up: none is earlier than planned
		assertEquals(Arrays.asList(BASE + 21 * MINUTE + 30000, BASE + 40 * MINUTE), times(result, 1));
		for (final List<DisplayArrivals.Arrival> arrivals : result.arrivals.values()) {
			for (final DisplayArrivals.Arrival arrival : arrivals) {
				assertTrue(arrival.estimateMillis >= arrival.stop.arrivalMillis);
			}
		}
		// Its current service is the train's own to tell
		assertTrue(result.get(2).stream().noneMatch(arrival -> arrival.stop.arrivalMillis == BASE + 4 * MINUTE));
	}

	@Test
	void dutiesNoTrainWillRunGiveNothing() {
		final long now = BASE - 30 * MINUTE;
		// Started from where a train stays overnight, with no train there; and one without a path
		final DisplayArrivals.Duty overnight = duty("night", BASE, 2, true, null);
		final DisplayArrivals.Duty noPath = new DisplayArrivals.Duty("none", POOL, 6, false, null, BASE, BASE + 60 * MINUTE, Collections.emptyList());
		DisplayArrivals.Result result = DisplayArrivals.compute(Arrays.asList(overnight, noPath), Collections.emptyList(), idle(3), d -> false, now, now + 1000 * MINUTE);
		assertTrue(result.arrivals.isEmpty());
		assertEquals(1, result.noPath);
		assertEquals(1, result.noOvernightTrain);

		// With the train of the day before on the line, staying there for it, it runs
		final DisplayArrivals.Duty dayBefore = duty("evening", BASE - 1000 * MINUTE, 2, false, "night");
		result = DisplayArrivals.compute(Arrays.asList(dayBefore, overnight, noPath), Collections.singletonList(new DisplayArrivals.Running("evening", 7, 3, 4, 0)), idle(0), d -> false, now, now + 1000 * MINUTE);
		assertEquals(Arrays.asList(BASE, BASE + 20 * MINUTE), times(result, 1));
	}

	@Test
	void aDutyRunsOnlyWhenItsPoolHasATrainInTime() {
		final long now = BASE - 30 * MINUTE;
		// The second leaves before the first is back
		final DisplayArrivals.Duty first = duty("a", BASE, 2);
		final DisplayArrivals.Duty second = duty("b", BASE + 10 * MINUTE, 2);
		final List<DisplayArrivals.Duty> duties = Arrays.asList(first, second);
		assertTrue(DisplayArrivals.compute(duties, Collections.emptyList(), idle(0), d -> false, now, now + 1000 * MINUTE).arrivals.isEmpty());
		DisplayArrivals.Result result = DisplayArrivals.compute(duties, Collections.emptyList(), idle(1), d -> false, now, now + 1000 * MINUTE);
		assertEquals(Arrays.asList(BASE, BASE + 20 * MINUTE), times(result, 1));
		assertEquals(1, result.noTrain);
		result = DisplayArrivals.compute(duties, Collections.emptyList(), idle(2), d -> false, now, now + 1000 * MINUTE);
		assertEquals(Arrays.asList(BASE, BASE + 10 * MINUTE, BASE + 20 * MINUTE, BASE + 30 * MINUTE), times(result, 1));
		assertEquals(0, result.noTrain);

		// One back from the first in time runs a third leaving after it
		final DisplayArrivals.Duty third = duty("c", BASE + 200 * MINUTE, 1);
		result = DisplayArrivals.compute(Arrays.asList(first, third), Collections.emptyList(), idle(1), d -> false, now, now + 1000 * MINUTE);
		assertEquals(Arrays.asList(BASE, BASE + 20 * MINUTE, BASE + 200 * MINUTE), times(result, 1));

		// Sent for already and finished, it isn't run again
		final Set<String> run = new HashSet<>(Collections.singleton("a"));
		result = DisplayArrivals.compute(duties, Collections.emptyList(), idle(2), d -> run.contains(d.key), now, now + 1000 * MINUTE);
		assertEquals(Arrays.asList(BASE + 10 * MINUTE, BASE + 30 * MINUTE), times(result, 1));
	}

	@Test
	void aDutyWaitingForATrainIsLateByThatWait() {
		final DisplayArrivals.Duty first = duty("a", BASE, 1);
		// One train: back from the first at 17 minutes, two minutes after the second's train should have left at 15
		final DisplayArrivals.Duty late = duty("c", BASE + 18 * MINUTE, 1);
		final DisplayArrivals.Result result = DisplayArrivals.compute(Arrays.asList(first, late), Collections.emptyList(), idle(1), d -> false, BASE - 30 * MINUTE, BASE + 1000 * MINUTE);
		assertEquals(Arrays.asList(BASE, BASE + 20 * MINUTE), times(result, 1));
	}

	@Test
	void theSameFromOneCallToTheNextWhileTrainsStandOrRun() {
		final List<DisplayArrivals.Duty> duties = Arrays.asList(duty("a", BASE, 4), duty("b", BASE + 10 * MINUTE, 4), duty("c", BASE + 100 * MINUTE, 2));
		final List<DisplayArrivals.Running> running = Arrays.asList(new DisplayArrivals.Running("a", 1, 0, 1, 0), new DisplayArrivals.Running("b", 2, 0, 1, 0));
		final DisplayArrivals.Result before = DisplayArrivals.compute(duties, running, idle(1), d -> false, BASE + MINUTE, BASE + 1000 * MINUTE);
		// Three seconds later, the trains having moved on or stood on time, and once more much later
		final DisplayArrivals.Result after = DisplayArrivals.compute(duties, running, idle(1), d -> false, BASE + MINUTE + 3000, BASE + 1000 * MINUTE);
		for (final long platformId : new long[]{1, 2, 3, 4}) {
			assertEquals(times(before, platformId), times(after, platformId));
		}
		assertTrue(times(before, 1).contains(BASE + 100 * MINUTE));
	}
}
