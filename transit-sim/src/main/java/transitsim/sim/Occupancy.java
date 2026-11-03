package transitsim.sim;

import java.util.*;

/**
 * Which rails are taken, and by what: the trains moved by the motion rules, trains driven by hand, signal blocks and
 * turn back rails a train has booked. The host puts them all in as it goes (decoupling document 3.7); nothing here reads
 * the world. Rails are the host's rail keys and occupants its train IDs.
 * <p>
 * Two ticks are kept, the one before and this one, so a train sees what was taken whether or not the other train has
 * moved yet this tick.
 */
public final class Occupancy {

	private final List<Map<UUID, Long>> ticks = new ArrayList<>(2);

	public Occupancy() {
		ticks.add(new HashMap<>());
		ticks.add(new HashMap<>());
	}

	/**
	 * Starts a new tick: this tick becomes the one before, and nothing is taken yet in the new one.
	 */
	public void nextTick() {
		ticks.remove(0);
		ticks.add(new HashMap<>());
	}

	/**
	 * Takes a rail this tick, unless something else has taken it already.
	 */
	public void occupy(UUID rail, long occupant) {
		ticks.get(1).putIfAbsent(rail, occupant);
	}

	/**
	 * Whether anything has taken the rail this tick.
	 */
	public boolean isOccupiedNow(UUID rail) {
		return ticks.get(1).containsKey(rail);
	}

	/**
	 * Whether something other than this occupant has the rail, this tick or the one before.
	 */
	public boolean isTakenByOther(UUID rail, long occupant) {
		return isTakenByOther(ticks, rail, occupant);
	}

	/**
	 * The two ticks as the host has always passed them around: the one before, then this one. Changes go straight into
	 * this occupancy.
	 */
	public List<Map<UUID, Long>> getTicks() {
		return ticks;
	}

	/**
	 * {@link #isTakenByOther(UUID, long)} for the host's code that only has the two ticks.
	 */
	public static boolean isTakenByOther(List<Map<UUID, Long>> ticks, UUID rail, long occupant) {
		for (final Map<UUID, Long> tick : ticks) {
			final Long other = tick.get(rail);
			if (other != null && other != occupant) {
				return true;
			}
		}
		return false;
	}
}
