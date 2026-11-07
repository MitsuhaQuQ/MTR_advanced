package transitsim.network;

import transitsim.network.RailGraph.Kind;
import transitsim.network.RailRouter.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * Generates rail networks laid as a new map should be under the layered network rules (decoupling document 5.4): lines
 * kept apart from each other, each a double track with platforms on both tracks, scissors crossovers beyond both ends and
 * crossovers here and there between stations, some lines laid one way; pairs of lines sharing a corridor of four tracks
 * paired by direction; a connecting line, single track, joining the end of one line to the start of the next. Lines run
 * east-west or north-south.
 */
final class LegalNetworks {

	static final int PLUS = 0;
	static final int MINUS = 1;
	private static final int PLATFORM_LENGTH = 20;

	final List<Line> lines = new ArrayList<>();
	final List<GeneratedRoute> routes = new ArrayList<>();
	private final RailGraph.Builder<String> builder = new RailGraph.Builder<>();
	private RailGraph<String> graph;

	private LegalNetworks() {
	}

	RailGraph<String> graph() {
		return graph;
	}

	static LegalNetworks generate(long seed) {
		final Random random = new Random(seed);
		final LegalNetworks network = new LegalNetworks();
		final int lineCount = 2 + random.nextInt(3);
		int offset = 0;
		Line previous = null;
		for (int i = 0; i < lineCount; i++) {
			final boolean alongX = random.nextBoolean();
			final int gap = 4 + random.nextInt(3);
			final int stations = 3 + random.nextInt(4);
			final int spacing = 120 + random.nextInt(9) * 20;
			final boolean oneWay = random.nextInt(4) == 0;
			final int sign = 1;
			final boolean corridor = !oneWay && random.nextInt(3) == 0;
			// Lines along z far from those along x, so no two lines meet at a node
			final int originX = alongX ? 0 : 20000 + offset;
			final int originZ = alongX ? offset : 20000;
			if (corridor) {
				// Paired by direction: minus of both lines on one side, plus of both on the other
				final Line first = network.new Line(network.lines.size(), alongX, originX, originZ, 0, sign * 2 * gap, stations, spacing, false, random);
				final Line second = network.new Line(network.lines.size() + 1, alongX, originX, originZ, sign * gap, sign * 3 * gap, stations, spacing, false, random);
				second.crossovers.clear();
				second.crossovers.addAll(first.crossovers);
				network.addLine(first);
				network.addLine(second);
				network.connect(previous, first);
				previous = second;
			} else {
				final Line line = network.new Line(network.lines.size(), alongX, originX, originZ, 0, sign * gap, stations, spacing, oneWay, random);
				network.addLine(line);
				network.connect(previous, line);
				previous = line;
			}
			offset += 200;
		}
		network.graph = network.builder.build();
		return network;
	}

	private void addLine(Line line) {
		lines.add(line);
		final TreeSet<Integer> breaks = new TreeSet<>();
		breaks.add(line.start() - 60);
		breaks.add(line.start() - 40);
		breaks.add(line.start() - 20);
		breaks.add(line.end() + 20);
		breaks.add(line.end() + 40);
		breaks.add(line.end() + 60);
		for (final int station : line.stations) {
			breaks.add(station);
			breaks.add(station + PLATFORM_LENGTH);
		}
		for (final int middle : line.crossovers) {
			breaks.add(middle - 10);
			breaks.add(middle + 10);
		}
		for (final int v : new int[]{line.minusV, line.plusV}) {
			final boolean plusTrack = v == line.plusV;
			Integer last = null;
			for (final int u : breaks) {
				if (last != null) {
					final boolean platform = line.stations.contains(last) && u == last + PLATFORM_LENGTH;
					final Kind kind = platform ? Kind.PLATFORM : Kind.NORMAL;
					// One way lines run each track only its own way
					if (!line.oneWay || plusTrack) {
						addConnection(line, last, v, u, v, kind, PLUS);
					}
					if (!line.oneWay || !plusTrack) {
						addConnection(line, u, v, last, v, kind, MINUS);
					}
				}
				last = u;
			}
		}
		// Scissors beyond both ends, and the crossovers between stations, both ways round
		final List<Integer> middles = new ArrayList<>(line.crossovers);
		middles.add(line.start() - 30);
		middles.add(line.end() + 30);
		for (final int middle : middles) {
			addBoth(line, middle - 10, line.minusV, middle + 10, line.plusV, "crossover");
			addBoth(line, middle - 10, line.plusV, middle + 10, line.minusV, "crossover");
		}
	}

	/**
	 * A connecting line: one track from the end stub of a line to the start stub of the next, and a route along it.
	 */
	private void connect(Line from, Line to) {
		if (from == null) {
			return;
		}
		final long fromPos = from.pos(from.end() + 60, from.plusV);
		final long toPos = to.pos(to.start() - 60, to.plusV);
		final int[] fromXZ = from.xz(from.end() + 60, from.plusV);
		final int[] toXZ = to.xz(to.start() - 60, to.plusV);
		final double cost = Math.hypot(toXZ[0] - fromXZ[0], toXZ[1] - fromXZ[1]);
		final String name = "connecting " + from.index + ">" + to.index;
		builder.add(fromPos, fromXZ[0], fromXZ[1], from.facing(PLUS), toPos, toXZ[0], 0, toXZ[1], to.facing(MINUS), Kind.NORMAL, cost, name);
		builder.add(toPos, toXZ[0], toXZ[1], to.facing(MINUS), fromPos, fromXZ[0], 0, fromXZ[1], from.facing(PLUS), Kind.NORMAL, cost, name + "<");
		routes.add(new GeneratedRoute(name, false, from.platform(from.stations.size() - 1, true), to.platform(0, true)));
	}

	private void addBoth(Line line, int u1, int v1, int u2, int v2, String name) {
		addConnection(line, u1, v1, u2, v2, Kind.NORMAL, PLUS, name);
		addConnection(line, u2, v2, u1, v1, Kind.NORMAL, MINUS, name);
	}

	private void addConnection(Line line, int u1, int v1, int u2, int v2, Kind kind, int direction) {
		addConnection(line, u1, v1, u2, v2, kind, direction, "track");
	}

	private void addConnection(Line line, int u1, int v1, int u2, int v2, Kind kind, int direction, String what) {
		final int[] from = line.xz(u1, v1);
		final int[] to = line.xz(u2, v2);
		final double cost = Math.hypot(to[0] - from[0], to[1] - from[1]);
		// A rail running +u leaves facing +u and ends facing back, -u
		builder.add(line.pos(u1, v1), from[0], from[1], line.facing(direction), line.pos(u2, v2), to[0], 0, to[1], line.facing(1 - direction), kind, cost, name(line, what, u1, v1, u2, v2));
	}

	static String name(Line line, String what, int u1, int v1, int u2, int v2) {
		return line.index + " " + what + " " + u1 + "," + v1 + ">" + u2 + "," + v2;
	}

	final class Line {

		final int index;
		final boolean alongX;
		final int originX;
		final int originZ;
		final int minusV;
		final int plusV;
		final List<Integer> stations = new ArrayList<>();
		final List<Integer> crossovers = new ArrayList<>();
		final boolean oneWay;

		private Line(int index, boolean alongX, int originX, int originZ, int minusV, int plusV, int stationCount, int spacing, boolean oneWay, Random random) {
			this.index = index;
			this.alongX = alongX;
			this.originX = originX;
			this.originZ = originZ;
			this.minusV = minusV;
			this.plusV = plusV;
			this.oneWay = oneWay;
			for (int i = 0; i < stationCount; i++) {
				stations.add(i * spacing);
				// Crossovers here and there between stations
				if (i > 0 && random.nextBoolean()) {
					crossovers.add(i * spacing - spacing / 2 + 10);
				}
			}
			// A terminal turns back with a crossover in front of it
			crossovers.add(PLATFORM_LENGTH + 20);
			crossovers.add((stationCount - 1) * spacing - 20);
			final Line line = this;
			// The routes of the line: each way stopping everywhere, express, all the way round, and out and back to the
			// same platform, which can only turn back on a track that runs both ways
			final List<Target> up = new ArrayList<>();
			final List<Target> down = new ArrayList<>();
			for (int i = 0; i < stationCount; i++) {
				up.add(platformTarget(i, true));
				down.add(0, platformTarget(i, false));
			}
			pendingRoutes.add(() -> {
				routes.add(new GeneratedRoute(index + " up", false, up));
				routes.add(new GeneratedRoute(index + " down", false, down));
				routes.add(new GeneratedRoute(index + " express", false, up.get(0), up.get(up.size() - 1)));
				// All the way round, turning back on the platform at the far end: it stops there only once
				final List<Target> round = new ArrayList<>(up);
				round.addAll(down.subList(1, down.size()));
				if (!line.oneWay) {
					routes.add(new GeneratedRoute(index + " round", true, round));
				}
				if (!line.oneWay) {
					routes.add(new GeneratedRoute(index + " out and back", true, up.get(0), up.get(up.size() - 1), up.get(0)));
				}
			});
			flushRoutes();
		}

		int start() {
			return stations.get(0);
		}

		int end() {
			return stations.get(stations.size() - 1) + PLATFORM_LENGTH;
		}

		Target platform(int station, boolean plus) {
			return platformTarget(station, plus);
		}

		private Target platformTarget(int station, boolean plus) {
			final int u = stations.get(station);
			final int v = plus ? plusV : minusV;
			final int[] xz1 = xz(u, v);
			final int[] xz2 = xz(u + PLATFORM_LENGTH, v);
			return Target.rail(pos(u, v), xz1[0], xz1[1], pos(u + PLATFORM_LENGTH, v), xz2[0], xz2[1]);
		}

		/**
		 * Along x, u is x and v is z; along z, u is z and v is -x, so in both v grows to the right of +u.
		 */
		int[] xz(int u, int v) {
			return alongX ? new int[]{originX + u, originZ + v} : new int[]{originX - v, originZ + u};
		}

		long pos(int u, int v) {
			final int[] xz = xz(u, v);
			return (xz[0] + 100000L) * 1000000L + xz[1] + 100000L;
		}

		/**
		 * Facings as the graph compares them: along x 0 is east and 1 west, along z 2 south and 3 north.
		 */
		int facing(int direction) {
			return (alongX ? 0 : 2) + direction;
		}
	}

	private final List<Runnable> pendingRoutes = new ArrayList<>();

	private void flushRoutes() {
		final List<Runnable> pending = new ArrayList<>(pendingRoutes);
		pendingRoutes.clear();
		pending.forEach(Runnable::run);
	}

	static final class GeneratedRoute {

		final String name;
		/**
		 * The route has to run some way on the wrong side: back from the far platform to the crossover beyond it.
		 */
		final boolean turnsBackOnTrack;
		final List<Target> targets;

		GeneratedRoute(String name, boolean turnsBackOnTrack, Target... targets) {
			this(name, turnsBackOnTrack, java.util.Arrays.asList(targets));
		}

		GeneratedRoute(String name, boolean turnsBackOnTrack, List<Target> targets) {
			this.name = name;
			this.turnsBackOnTrack = turnsBackOnTrack;
			this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
		}

		@Override
		public String toString() {
			return name;
		}
	}
}
