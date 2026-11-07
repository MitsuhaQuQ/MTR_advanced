package transitsim.sim;

import transitsim.network.ThroatRoutes;

import java.util.*;
import java.util.function.IntToDoubleFunction;
import java.util.function.LongToIntFunction;
import java.util.function.ToLongFunction;

/**
 * A train on duty locks its way through station throats while running (decoupling document 5.4, L2), as a home signal
 * would: the turnouts of a stop's exit route with the run it asks for to leave the stop, and the turnouts of the next
 * stop's entry route once it comes within braking distance of them, stopping in front of the throat until they are
 * given. Each turnout is held until the train's tail is past it. Turnouts are resources of the same arbiter as single
 * track ({@link RunReservations}), so trains using a turnout by different ways get it one after the other and trains the
 * same way share it.
 * <p>
 * One of these is kept for each train and path; both hosts, the game's server trains and the component's runner, use it
 * the same way.
 */
public final class ThroatLocks {

	/**
	 * Off, nothing is locked and trains run as before; the server's settings turn it off, and the day tool with
	 * {@code -Dmtr.throatLocks=false}.
	 */
	public static volatile boolean enabled = !"false".equals(System.getProperty("mtr.throatLocks"));
	/**
	 * How far before a throat, on top of the braking distance, a train asks for its way through it, in blocks.
	 */
	public static final double APPROACH_MARGIN = 32;

	private final Object pathKey;
	private final int pathSize;
	private final List<Stop> stops = new ArrayList<>();
	/**
	 * The turnouts and zones given to the train and not passed yet, by {@link #grantKey}.
	 */
	private final Map<Long, ThroatRoutes.Item> granted = new TreeMap<>();
	/**
	 * What the train's outstanding request asks for, given to it once the request is granted.
	 */
	private List<ThroatRoutes.Item> requested = Collections.emptyList();

	/**
	 * @param pathKey   the host's path, which a new one replaces this for
	 * @param nodes     the path as the nodes it passes, rail {@code i} from node {@code i} to {@code i + 1}
	 * @param stopRails the rails of the path the train stops on, in order: platforms, and its siding at either end
	 * @param rails     how many different rails meet at a node
	 */
	public ThroatLocks(Object pathKey, long[] nodes, int[] stopRails, LongToIntFunction rails) {
		this(pathKey, nodes, stopRails, rails, rail -> 0);
	}

	/**
	 * @param zones the interlocking zone of each rail, by its key, or 0; see {@link ThroatRoutes#of}
	 */
	public ThroatLocks(Object pathKey, long[] nodes, int[] stopRails, LongToIntFunction rails, ToLongFunction<UUID> zones) {
		this.pathKey = pathKey;
		pathSize = nodes.length;
		final List<ThroatRoutes.StopThroat> throats = ThroatRoutes.of(nodes, ThroatLocks::position, stopRails, rails, zones);
		for (int i = 0; i < stopRails.length; i++) {
			stops.add(new Stop(stopRails[i], throats.get(i)));
		}
	}

	/**
	 * @return whether this was made for the host's path as it is
	 */
	public boolean isFor(Object pathKey, int nodeCount) {
		return this.pathKey == pathKey && pathSize == nodeCount;
	}

	/**
	 * @return a node's x and z, from the game's packing of a block position
	 */
	public static double[] position(long packed) {
		return new double[]{(int) (packed >> 38), (int) (packed << 26 >> 38)};
	}

	/**
	 * @return the turnouts given to the train that its tail, on this rail, hasn't passed, as moves to hold
	 */
	public List<RunReservations.Move> getHeld(int tailRail) {
		// A turnout once the tail is past its node, a zone once the tail has left its last rail
		granted.values().removeIf(item -> item.endIndex <= tailRail);
		return toMoves(granted.values());
	}

	/**
	 * @return every turnout and zone of the stops' ways in and out along the path, each once, for the run layer (5.5),
	 * which claims each with the section the train needs to pass its node
	 */
	public List<ThroatRoutes.Item> getItems() {
		final Map<Long, ThroatRoutes.Item> items = new TreeMap<>();
		stops.forEach(stop -> {
			stop.throat.entry.forEach(item -> items.putIfAbsent(grantKey(item), item));
			stop.throat.exit.forEach(item -> items.putIfAbsent(grantKey(item), item));
		});
		return new ArrayList<>(items.values());
	}

	/**
	 * @return the turnouts of the exit route of the stop on this rail, to ask for with the run leaving it
	 */
	public List<ThroatRoutes.Item> getExit(int platformRail) {
		for (final Stop stop : stops) {
			if (stop.rail == platformRail) {
				return stop.throat.exit;
			}
		}
		return Collections.emptyList();
	}

	/**
	 * @return the turnouts of the next stop's entry route ahead of the head, on this rail, not given yet, or empty
	 */
	public List<ThroatRoutes.Item> getNextEntry(int headRail) {
		for (final Stop stop : stops) {
			if (stop.rail > headRail) {
				final List<ThroatRoutes.Item> missing = new ArrayList<>();
				stop.throat.entry.forEach(item -> {
					if (item.endIndex > headRail && !granted.containsKey(grantKey(item))) {
						missing.add(item);
					}
				});
				return missing;
			}
		}
		return Collections.emptyList();
	}

	/**
	 * @return the first of these turnouts along the path, as its node's index, or -1
	 */
	public static int getFirstIndex(List<ThroatRoutes.Item> items) {
		return items.stream().mapToInt(item -> item.index).min().orElse(-1);
	}

	/**
	 * Remembers what the train's request asks for, given to it when {@link #onGranted} is called.
	 */
	public void setRequested(List<ThroatRoutes.Item> items) {
		requested = new ArrayList<>(items);
	}

	public void onGranted() {
		requested.forEach(item -> granted.put(grantKey(item), item));
		requested = Collections.emptyList();
	}

	/**
	 * Every tick for a running train on duty: once it is within braking distance of the next throat it hasn't been given,
	 * asks for its way through, with what it holds already, as a grant gives a train only what its request asks for.
	 *
	 * @param held           the single track the train holds now
	 * @param distanceToNode how far the head is from a node of the path, by index; 0 or less once past it
	 * @param braking        how far the train takes to stop from its speed now
	 * @return whether the train has asked
	 */
	public boolean approach(RunReservations reservations, long trainId, List<RunReservations.Move> held, int headRail, int tailRail, IntToDoubleFunction distanceToNode, double braking, long plannedMillis) {
		if (!requested.isEmpty() && reservations.takeGrant(trainId)) {
			onGranted();
		}
		final List<ThroatRoutes.Item> entry = getNextEntry(headRail);
		if (entry.isEmpty()) {
			return false;
		}
		// Measured to where it would stop, the start of the rail onto the throat, so a train stopped there still asks
		final int first = getFirstIndex(entry);
		if (distanceToNode.applyAsDouble(first - 1) > braking + APPROACH_MARGIN) {
			return false;
		}
		final List<RunReservations.Move> moves = new ArrayList<>(held);
		moves.addAll(getHeld(tailRail));
		moves.addAll(toMoves(entry));
		setRequested(entry);
		reservations.request(trainId, moves, plannedMillis);
		return true;
	}

	/**
	 * @return whether the rail leading onto the next throat is closed to the train, as its way through isn't given; the
	 * train stops before it, so nothing of it stands on a rail the other ways through the first turnout use
	 */
	public boolean isBlocked(int rail, int headRail) {
		final List<ThroatRoutes.Item> entry = getNextEntry(headRail);
		return !entry.isEmpty() && rail == getFirstIndex(entry) - 1;
	}

	public static List<RunReservations.Move> toMoves(Collection<ThroatRoutes.Item> items) {
		final List<RunReservations.Move> moves = new ArrayList<>();
		items.forEach(item -> moves.add(item.isZone() ? RunReservations.Move.zone(item.node) : RunReservations.Move.turnout(item.node, item.side1, item.side2)));
		return moves;
	}

	/**
	 * A turnout and a zone can start at the same node of the path, so each has a key of its own.
	 */
	private static long grantKey(ThroatRoutes.Item item) {
		return item.index * 2L + (item.isZone() ? 1 : 0);
	}

	private static final class Stop {

		private final int rail;
		private final ThroatRoutes.StopThroat throat;

		private Stop(int rail, ThroatRoutes.StopThroat throat) {
			this.rail = rail;
			this.throat = throat;
		}
	}
}
