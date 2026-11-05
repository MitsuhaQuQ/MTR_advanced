package transitsim.network;

import java.util.*;

/**
 * The tracks of each station (decoupling document 5.4, L2): which way each platform's trains run, up or down, whether it
 * is the main line or a passing track, and the order the tracks lie in across the station. Worked out from the routes
 * using the platforms, so it needs nothing saved.
 * <p>
 * Up and down are by where a route's first and last stations lie (the user's rule): a route heading mostly east, or
 * mostly north, is up; west or south is down. A route ending where it starts, as a loop does, takes the way it runs along
 * each platform instead. A platform the passing flag is set on is a passing track; otherwise, of two or more platforms of
 * a station running the same way, those a service passes without stopping are the main line and the rest passing tracks.
 * <p>
 * Coordinates are the game's: x grows east, z grows south.
 */
public final class StationTracks {

	public static final String UP = "up";
	public static final String DOWN = "down";
	public static final String BOTH = "both";
	public static final String MAIN = "main";
	public static final String PASSING = "passing";
	/**
	 * A route whose ends are closer than this, in blocks, is taken as a loop.
	 */
	public static final double LOOP_DISTANCE = 16;

	private StationTracks() {
	}

	/**
	 * @return up or down for a run from one point to another, by its larger part
	 */
	public static String directionOf(double fromX, double fromZ, double toX, double toZ) {
		final double dx = toX - fromX;
		final double dz = toZ - fromZ;
		if (Math.abs(dx) >= Math.abs(dz)) {
			return dx > 0 ? UP : DOWN;
		}
		return dz < 0 ? UP : DOWN;
	}

	/**
	 * @param platforms every platform, with its station
	 * @param routes    every route, with its stops in order
	 * @return each platform's track, by platform ID
	 */
	public static Map<Long, Track> compute(List<PlatformInput> platforms, List<RouteInput> routes) {
		final Map<Long, PlatformInput> platformMap = new HashMap<>();
		platforms.forEach(platform -> platformMap.put(platform.id, platform));
		final Map<Long, Set<String>> directions = new HashMap<>();
		final Set<Long> passedThrough = new HashSet<>();

		for (final RouteInput route : routes) {
			if (route.stops.isEmpty()) {
				continue;
			}
			final PlatformInput first = platformMap.get(route.stops.get(0).platformId);
			final PlatformInput last = platformMap.get(route.stops.get(route.stops.size() - 1).platformId);
			final boolean loop = first == null || last == null || Math.hypot(last.middleX() - first.middleX(), last.middleZ() - first.middleZ()) < LOOP_DISTANCE;
			final String routeDirection = loop ? null : directionOf(first.middleX(), first.middleZ(), last.middleX(), last.middleZ());
			for (final StopInput stop : route.stops) {
				final PlatformInput platform = platformMap.get(stop.platformId);
				if (platform == null) {
					continue;
				}
				final String direction = routeDirection != null ? routeDirection : stop.way == 0 ? directionOf(platform.x1, platform.z1, platform.x2, platform.z2) : stop.way == 1 ? directionOf(platform.x2, platform.z2, platform.x1, platform.z1) : null;
				if (direction != null) {
					directions.computeIfAbsent(platform.id, key -> new TreeSet<>()).add(direction);
				}
				if (stop.passes) {
					passedThrough.add(platform.id);
				}
			}
		}

		final Map<Long, Track> tracks = new HashMap<>();
		platforms.forEach(platform -> {
			final Set<String> found = directions.getOrDefault(platform.id, Collections.emptySet());
			final String direction = found.size() > 1 ? BOTH : found.isEmpty() ? "" : found.iterator().next();
			tracks.put(platform.id, new Track(direction, platform.passingTrack ? PASSING : ""));
		});

		// Main and passing tracks among the platforms of a station running the same way
		final Map<String, List<PlatformInput>> groups = new HashMap<>();
		platforms.forEach(platform -> {
			final String direction = tracks.get(platform.id).direction;
			if (platform.stationId != 0 && (UP.equals(direction) || DOWN.equals(direction))) {
				groups.computeIfAbsent(platform.stationId + "|" + platform.network + "|" + direction, key -> new ArrayList<>()).add(platform);
			}
		});
		groups.values().forEach(group -> {
			if (group.size() < 2) {
				return;
			}
			final boolean anyMain = group.stream().anyMatch(platform -> !platform.passingTrack && passedThrough.contains(platform.id));
			if (!anyMain) {
				return;
			}
			group.forEach(platform -> {
				final Track track = tracks.get(platform.id);
				if (!platform.passingTrack) {
					tracks.put(platform.id, new Track(track.direction, passedThrough.contains(platform.id) ? MAIN : PASSING));
				}
			});
		});

		// The order across each station: along the station's own axis, left to right looking up the line
		final Map<String, List<PlatformInput>> stations = new HashMap<>();
		platforms.forEach(platform -> {
			if (platform.stationId != 0) {
				stations.computeIfAbsent(platform.stationId + "|" + platform.network, key -> new ArrayList<>()).add(platform);
			}
		});
		stations.values().forEach(station -> {
			double axisX = 0;
			double axisZ = 0;
			for (final PlatformInput platform : station) {
				double dx = platform.x2 - platform.x1;
				double dz = platform.z2 - platform.z1;
				// Each platform pointing up the line, so opposite platforms add up rather than cancel out
				if (DOWN.equals(directionOf(0, 0, dx, dz))) {
					dx = -dx;
					dz = -dz;
				}
				axisX += dx;
				axisZ += dz;
			}
			final double centreX = station.stream().mapToDouble(PlatformInput::middleX).average().orElse(0);
			final double centreZ = station.stream().mapToDouble(PlatformInput::middleZ).average().orElse(0);
			final double finalAxisX = axisX;
			final double finalAxisZ = axisZ;
			// With x east and z south, a point left of the axis has a negative cross product
			final List<PlatformInput> ordered = new ArrayList<>(station);
			ordered.sort(Comparator.comparingDouble((PlatformInput platform) -> finalAxisX * (platform.middleZ() - centreZ) - finalAxisZ * (platform.middleX() - centreX)).thenComparing(platform -> platform.name));
			for (int i = 0; i < ordered.size(); i++) {
				tracks.get(ordered.get(i).id).order = i;
			}
		});
		return tracks;
	}

	public static final class Track {

		/**
		 * {@link #UP}, {@link #DOWN}, {@link #BOTH}, or empty when no route uses the platform.
		 */
		public final String direction;
		/**
		 * {@link #MAIN}, {@link #PASSING}, or empty when it can't be told.
		 */
		public final String kind;
		/**
		 * Its place across its station, from the left looking up the line.
		 */
		public int order;

		private Track(String direction, String kind) {
			this.direction = direction;
			this.kind = kind;
		}
	}

	public static final class PlatformInput {

		public final long id;
		public final long stationId;
		public final long network;
		public final String name;
		public final boolean passingTrack;
		public final double x1;
		public final double z1;
		public final double x2;
		public final double z2;

		/**
		 * @param stationId 0 for a platform of no station
		 * @param x1        its first end, as the platform graph's way 0 runs from
		 */
		public PlatformInput(long id, long stationId, long network, String name, boolean passingTrack, double x1, double z1, double x2, double z2) {
			this.id = id;
			this.stationId = stationId;
			this.network = network;
			this.name = name;
			this.passingTrack = passingTrack;
			this.x1 = x1;
			this.z1 = z1;
			this.x2 = x2;
			this.z2 = z2;
		}

		private double middleX() {
			return (x1 + x2) / 2;
		}

		private double middleZ() {
			return (z1 + z2) / 2;
		}
	}

	public static final class RouteInput {

		public final List<StopInput> stops = new ArrayList<>();
	}

	public static final class StopInput {

		public final long platformId;
		/**
		 * 0 from the platform's first end to its second, 1 the other way, or -1 if not known.
		 */
		public final int way;
		public final boolean passes;

		public StopInput(long platformId, int way, boolean passes) {
			this.platformId = platformId;
			this.way = way;
			this.passes = passes;
		}
	}
}
