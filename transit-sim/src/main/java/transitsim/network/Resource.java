package transitsim.network;

/**
 * Something trains need and can't all have at once (contracts document 4.1). The network only says which resources there
 * are and which sections use them; granting them is up to the simulation.
 */
public final class Resource {

	public final long id;
	public final Kind kind;
	/**
	 * The sections using it, in order of ID.
	 */
	private final long[] sections;
	/**
	 * For a berth, how many trains it takes; 1 for the others, which are taken by one train, or by one direction.
	 */
	public final int capacity;

	Resource(long id, Kind kind, long[] sections, int capacity) {
		this.id = id;
		this.kind = kind;
		this.sections = sections;
		this.capacity = capacity;
	}

	public long[] getSections() {
		return sections.clone();
	}

	public enum Kind {
		/**
		 * A section, which one train is on at a time.
		 */
		TRACK,
		/**
		 * The sections meeting at a node where rails branch, which only one route through it can use at a time. Only rails
		 * joined at a node count; rails crossing without one aren't known.
		 */
		TURNOUT,
		/**
		 * Two way sections joined end to end with nowhere to pass, which trains use one direction at a time.
		 */
		SINGLE_TRACK,
		/**
		 * A platform, siding or turn back rail, which takes as many trains as its capacity.
		 */
		BERTH
	}
}
