package transitsim.sim;

import java.util.*;
import java.util.function.LongConsumer;

/**
 * The part of a world's data a simulated day of some routes needs (S5): the depots running them, with every depot running
 * a route in common with those, as they share services; and of the other depots, the sidings whose trains pass a node
 * those depots' trains pass, so they can stand in each other's way. Trains that only meet those trains aren't simulated,
 * as they would only delay the routes reported on through others. A world-sized day takes far too long for the editor;
 * this one only as long as the routes' own part of it.
 * <p>
 * A depot of which only some sidings are kept sends the services and departures of the others from those, so the routes
 * may meet more of its trains than they would: the report errs on the side of more delay.
 */
public final class SimScope {

	public final SimDataset dataset;
	/**
	 * How many depots are simulated, and how many the world has.
	 */
	public final int depots, allDepots;
	/**
	 * How many of those depots run the routes or a route in common with them, the rest being there only for some of
	 * their trains.
	 */
	public final int ownDepots;

	private SimScope(SimDataset dataset, int depots, int allDepots, int ownDepots) {
		this.dataset = dataset;
		this.depots = depots;
		this.allDepots = allDepots;
		this.ownDepots = ownDepots;
	}

	/**
	 * @param reportRoutes the routes reported on, or empty for all of them, which keeps the whole world
	 * @return the data to simulate, which shares its routes, depots, sidings and trains with the data given
	 */
	public static SimScope of(SimDataset full, Set<Long> reportRoutes) {
		if (reportRoutes.isEmpty()) {
			return new SimScope(full, full.depots.size(), full.depots.size(), full.depots.size());
		}

		final Set<Long> own = new HashSet<>();
		full.depots.forEach(depot -> {
			if (Arrays.stream(depot.routeIds).anyMatch(reportRoutes::contains)) {
				own.add(depot.id);
			}
		});
		addSharingRoutes(full, own);

		// Where the routes' own trains go, along their usual paths and those of their services
		final Set<Long> footprint = new HashSet<>();
		full.sidings.forEach(siding -> {
			if (own.contains(siding.depotId)) {
				forEachNode(siding, footprint::add);
			}
		});
		final Set<Long> keptDepots = new HashSet<>(own);
		final Set<Long> neighbourSidings = new HashSet<>();
		full.sidings.forEach(siding -> {
			if (!own.contains(siding.depotId)) {
				final boolean[] touches = {false};
				forEachNode(siding, node -> touches[0] |= footprint.contains(node));
				if (touches[0]) {
					keptDepots.add(siding.depotId);
					neighbourSidings.add(siding.id);
				}
			}
		});

		final SimDataset scoped = new SimDataset();
		scoped.routes.addAll(full.routes);
		full.depots.forEach(depot -> {
			if (keptDepots.contains(depot.id)) {
				scoped.depots.add(depot);
			}
		});
		full.sidings.forEach(siding -> {
			if (own.contains(siding.depotId) || neighbourSidings.contains(siding.id)) {
				scoped.sidings.add(siding);
			}
		});
		// Trains of the sidings left out find no siding and are left out as well
		scoped.trains = full.trains;
		scoped.signalBlocks.addAll(full.signalBlocks);
		// Everything else of the network the part runs by: interlocking zones (L2), where trains stop at platforms (round 4,
		// step 1) and the early threshold (round 4, step 5); left out, the part ran without them
		scoped.interlockBlocks.addAll(full.interlockBlocks);
		scoped.stopPositions.putAll(full.stopPositions);
		scoped.earlyThresholdMillis = full.earlyThresholdMillis;
		return new SimScope(scoped, scoped.depots.size(), full.depots.size(), own.size());
	}

	/**
	 * Adds the depots running a route in common with one kept, until there are no more.
	 */
	private static void addSharingRoutes(SimDataset full, Set<Long> kept) {
		final Set<Long> routes = new HashSet<>();
		boolean added = true;
		while (added) {
			added = false;
			for (final SimDataset.DepotData depot : full.depots) {
				if (kept.contains(depot.id)) {
					for (final long routeId : depot.routeIds) {
						routes.add(routeId);
					}
				}
			}
			for (final SimDataset.DepotData depot : full.depots) {
				if (!kept.contains(depot.id) && Arrays.stream(depot.routeIds).anyMatch(routes::contains)) {
					kept.add(depot.id);
					added = true;
				}
			}
		}
	}

	private static void forEachNode(SimDataset.SidingData siding, LongConsumer consumer) {
		siding.path.forEach(track -> {
			consumer.accept(track.getStartNode());
			consumer.accept(track.getEndNode());
		});
		siding.tripPaths.forEach(tripPath -> tripPath.path.forEach(track -> {
			consumer.accept(track.getStartNode());
			consumer.accept(track.getEndNode());
		}));
	}
}
