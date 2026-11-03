package transitsim.network;

import java.util.UUID;

/**
 * Names a rail between two nodes the same way whichever end it is seen from, so both directions of a rail share one key.
 */
public final class RailKeys {

	private RailKeys() {
	}

	public static UUID of(long node1, long node2) {
		return new UUID(Math.min(node1, node2), Math.max(node1, node2));
	}
}
