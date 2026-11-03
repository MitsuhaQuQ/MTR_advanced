package mtr.advanced;

import mtr.data.RailwayDataStore;
import mtr.path.PathData;
import transitsim.network.Network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * What the server writes into its own SQLite tables about the physical network: the compressed graph whenever the network
 * changes, and the sections each found path runs through. Paths can be found on any thread, so they wait in a queue, and
 * everything is written by the save loop on the server thread, with the rest of the data.
 */
public final class GraphRecords {

	private volatile Network graph;
	private volatile boolean graphChanged;
	private final ConcurrentLinkedQueue<PendingPath> pendingPaths = new ConcurrentLinkedQueue<>();
	private final Map<Long, Network.Section> loadedSections = new TreeMap<>();
	private long loadedFingerprint;

	private static final int MAX_PENDING_PATHS = 65536;

	public void setGraph(Network newGraph) {
		final Network oldGraph = graph;
		if (oldGraph == null || oldGraph.getFingerprint() != newGraph.getFingerprint()) {
			graph = newGraph;
			graphChanged = true;
		}
	}

	public Network getGraph() {
		return graph;
	}

	/**
	 * @param owner what the path belongs to, such as {@code route:<id>} or {@code siding:<id>}
	 */
	public void addPath(String owner, List<long[]> moves) {
		if (pendingPaths.size() < MAX_PENDING_PATHS && !moves.isEmpty()) {
			pendingPaths.add(new PendingPath(owner, moves));
		}
	}

	/**
	 * Writes what changed. Paths found before the first graph wait for it.
	 */
	public void flush(RailwayDataStore store) throws Exception {
		final Network currentGraph = graph;
		if (currentGraph == null) {
			return;
		}
		final long fingerprint = currentGraph.getFingerprint();
		if (graphChanged) {
			graphChanged = false;
			final Map<Long, byte[]> sections = new TreeMap<>();
			currentGraph.sections.forEach((id, section) -> sections.put(id, section.toBytes()));
			store.replaceGraphSections(sections, fingerprint);
		}
		PendingPath pendingPath;
		while ((pendingPath = pendingPaths.poll()) != null) {
			store.writeGraphPath(pendingPath.owner, fingerprint, currentGraph.toSectionPath(pendingPath.moves).toBytes());
		}
	}

	/**
	 * Reads back what was saved, for the track diagram and the section cache to come.
	 */
	public void load(RailwayDataStore store) {
		loadedSections.clear();
		try {
			loadedFingerprint = store.getGraphFingerprint();
			store.forEachGraphSection((id, data) -> {
				try {
					loadedSections.put(id, Network.Section.fromBytes(id, data));
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public Map<Long, Network.Section> getLoadedSections() {
		return loadedSections;
	}

	public long getLoadedFingerprint() {
		return loadedFingerprint;
	}

	/**
	 * @return each rail of a path as {from node, to node, stop time, platform or 0}
	 */
	public static List<long[]> getMoves(List<PathData> path) {
		final List<long[]> moves = new ArrayList<>();
		path.forEach(pathData -> moves.add(new long[]{pathData.startingPos.asLong(), pathData.getLastPos().asLong(), pathData.dwellTime, pathData.savedRailBaseId}));
		return moves;
	}

	/**
	 * @return the rails between nodes passed in order, without stops
	 */
	public static List<long[]> getMoves(long[] nodes) {
		final List<long[]> moves = new ArrayList<>();
		for (int i = 0; i + 1 < nodes.length; i++) {
			moves.add(new long[]{nodes[i], nodes[i + 1], 0, 0});
		}
		return moves;
	}

	private static final class PendingPath {

		private final String owner;
		private final List<long[]> moves;

		private PendingPath(String owner, List<long[]> moves) {
			this.owner = owner;
			this.moves = moves;
		}
	}
}
