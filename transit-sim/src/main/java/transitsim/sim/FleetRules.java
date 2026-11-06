package transitsim.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * The rules of a depot's pools of sidings and their trains (P2-C, R3), the one copy both hosts go by (S7): the game's
 * {@code Siding} and {@code Depot}, and the component's {@link TickRunner}. Each takes what the host knows and says what to
 * do, so the two can't drift apart.
 */
public final class FleetRules {

	/**
	 * What a siding does about a train standing in it in a tick.
	 */
	public enum Make {
		/**
		 * Nothing: one stands there already, or the pool has its trains.
		 */
		NONE,
		/**
		 * The one kept longest in its shed stands there.
		 */
		FROM_SHED,
		/**
		 * A new train of the pool stands there.
		 */
		NEW
	}

	private FleetRules() {
	}

	/**
	 * Running by duties, a siding with none standing and none just out takes the train kept longest in its shed; with none
	 * kept, a new train is made only while the pool has fewer than it has in all, and the siding has room, or, with as many
	 * as needed, while the pool has fewer than its duties, each siding with one standing at least.
	 *
	 * @param free       whether no train stands in the siding or is still close to it
	 * @param shedEmpty  whether its shed keeps none
	 * @param poolFleet  the trains the pool has in all, or -1 for as many as its duties need
	 * @param poolTrains the trains of the pool now, on the line, standing and kept
	 * @param poolSlots  the duties of the pool
	 * @param held       the trains standing in the siding and kept in its shed
	 * @param capacity   how many the siding holds, or -1 for any number
	 * @param trains     the trains of the siding, on the line and standing
	 */
	public static Make whatToMake(boolean free, boolean noTrains, boolean shedEmpty, int poolFleet, int poolTrains, int poolSlots, int held, int capacity, int trains) {
		if (!noTrains && !free) {
			return Make.NONE;
		}
		if (!shedEmpty) {
			return Make.FROM_SHED;
		}
		if (poolFleet >= 0) {
			return poolTrains < poolFleet && hasRoom(held, 0, capacity) ? Make.NEW : Make.NONE;
		}
		return noTrains || poolTrains < poolSlots && (capacity < 0 || trains < capacity) ? Make.NEW : Make.NONE;
	}

	/**
	 * @param held     the trains standing in the siding and kept in its shed
	 * @param coming   the trains on their way back to it
	 * @param capacity how many it holds, or -1 for any number
	 * @return whether it has room for one more
	 */
	public static boolean hasRoom(int held, int coming, int capacity) {
		return capacity < 0 || held + coming < capacity;
	}

	/**
	 * @return the trains of a pool in all, what each depot gives it added up, or -1 if one of them has as many as needed
	 */
	public static int sumFleets(Iterable<Integer> fleets) {
		int total = 0;
		for (final int fleet : fleets) {
			if (fleet < 0) {
				return -1;
			}
			total += fleet;
		}
		return total;
	}

	/**
	 * Running by duties, a second train standing in a siding is kept in its shed rather than taken away; otherwise, as the
	 * game always did, it is taken away.
	 */
	public static boolean keepsSecondStanding(boolean byDuties) {
		return byDuties;
	}

	/**
	 * @param own        the siding
	 * @param candidates the sidings that could be in its pool, in a fixed order, such as by ID
	 * @param depotOf    a siding's depot
	 * @param poolKeyOf  what the sidings of a depot whose trains run each other's duties have in common, never null
	 * @param linePoolOf the pool across the depots of its operating line, or empty
	 * @return its pool across its line: the sidings of its own depot with its pool key, then those of other depots of its
	 * operating line in the same pool, each once, in the order given
	 */
	public static <S> List<S> linePool(S own, Iterable<S> candidates, ToLongFunction<S> depotOf, Function<S, Object> poolKeyOf, Function<S, String> linePoolOf) {
		final long depot = depotOf.applyAsLong(own);
		final Object poolKey = poolKeyOf.apply(own);
		final String linePool = linePoolOf.apply(own);
		final List<S> pool = new ArrayList<>();
		final List<S> others = new ArrayList<>();
		for (final S candidate : candidates) {
			if (pool.contains(candidate) || others.contains(candidate)) {
				continue;
			}
			if (depotOf.applyAsLong(candidate) == depot) {
				if (candidate == own || Objects.equals(poolKeyOf.apply(candidate), poolKey)) {
					pool.add(candidate);
				}
			} else if (linePool != null && !linePool.isEmpty() && linePool.equals(linePoolOf.apply(candidate))) {
				others.add(candidate);
			}
		}
		if (!pool.contains(own)) {
			pool.add(0, own);
		}
		pool.addAll(others);
		return pool;
	}
}
