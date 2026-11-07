package transitsim.run;

import transitsim.network.BlockSections;
import transitsim.network.Resource;
import transitsim.sim.Arbiter;
import transitsim.sim.RunTrack;

import java.util.*;

/**
 * What a train asks the arbiter for to run along part of its path (run layer, decoupling document 5.5): the resources in
 * the order it reaches them, in groups that are granted whole or not at all.
 * <ul>
 * <li>Each block section of the line is a group of its own, so a train behind another is let on section by section.</li>
 * <li>A stretch of track run both ways (single track) goes as one group with its direction, so trains never meet head on
 * part of the way along it.</li>
 * <li>A platform the train turns back at goes as one group with both its directions (掉头车优先), so no train behind can
 * come in after it and block its way out.</li>
 * </ul>
 * A painted signal block is one section however many rails it has. The siding's own track isn't a section: trains in
 * their siding don't take the line. A track's direction is a resource of its own, apart from its sections, as a holder has
 * one claim of each resource.
 */
public final class PathClaims {

	private static final long DIRECTION_MARK = 0x6469726563746E21L;

	private PathClaims() {
	}

	/**
	 * A group of resources granted together, ending where a train holding it may run to.
	 */
	public static final class Group {

		public final List<Arbiter.Claim<UUID>> claims;
		/**
		 * Where along the path the group starts, which a train may run up to without it.
		 */
		public final double start;
		/**
		 * How far along the path the group lets the train run to.
		 */
		public final double end;

		Group(List<Arbiter.Claim<UUID>> claims, double end) {
			this(claims, end, end);
		}

		Group(List<Arbiter.Claim<UUID>> claims, double start, double end) {
			this.claims = Collections.unmodifiableList(claims);
			this.start = Math.min(start, end);
			this.end = end;
		}

		@Override
		public String toString() {
			return claims.size() + " to " + Math.round(end * 10) / 10D;
		}
	}

	/**
	 * Something claimed with the section a train needs to pass a place on its path, as a turnout of a station's throat or
	 * an interlocking zone: from where along the path the train needs it to where its tail has left it.
	 */
	public static final class Attached {

		public final double start;
		public final double end;
		public final List<Arbiter.Claim<UUID>> claims;

		public Attached(double start, double end, List<Arbiter.Claim<UUID>> claims) {
			this.start = start;
			this.end = Math.max(start, end);
			this.claims = claims;
		}
	}

	/**
	 * @return the groups, each with what is attached to a place it takes the train past: after the end of the group before,
	 * up to and including its own end
	 */
	public static List<Group> attach(List<Group> groups, Collection<Attached> attached) {
		if (attached.isEmpty()) {
			return groups;
		}
		final List<Group> result = new ArrayList<>(groups.size());
		double previousEnd = Double.NEGATIVE_INFINITY;
		for (final Group group : groups) {
			final List<Arbiter.Claim<UUID>> claims = new ArrayList<>(group.claims);
			for (final Attached each : attached) {
				if (each.start > previousEnd + 1E-6 && each.start <= group.end + 1E-6) {
					claims.addAll(each.claims);
				}
			}
			result.add(claims.size() == group.claims.size() ? group : new Group(claims, group.start, group.end));
			previousEnd = group.end;
		}
		return result;
	}

	/**
	 * Where a train is along its path, read by the claims.
	 */
	public interface Path<T extends RunTrack> {

		List<T> getTracks();

		/**
		 * @return how far along the path the track ends
		 */
		double getEnd(int track);

		/**
		 * @return whether the path turns back between these two tracks: the second runs along the first the other way
		 */
		boolean turnsBack(int track, int nextTrack);
	}

	/**
	 * @return the resource of the direction of a track run both ways
	 */
	public static UUID directionOf(UUID trackKey) {
		return new UUID(trackKey.getMostSignificantBits() ^ DIRECTION_MARK, trackKey.getLeastSignificantBits() ^ DIRECTION_MARK);
	}

	/**
	 * The groups from {@code from} to {@code to} along the path, in order: of the tracks the part runs along, and when it
	 * has no length, of the track it is at.
	 *
	 * @param sections the painted signal blocks, or null for none
	 */
	public static <T extends RunTrack> List<Group> between(Path<T> path, double from, double to, BlockSections sections) {
		final List<T> tracks = path.getTracks();
		final List<Group> groups = new ArrayList<>();
		final Set<UUID> taken = new HashSet<>();
		List<Arbiter.Claim<UUID>> singleTrack = null;
		double singleTrackStart = from;
		double singleTrackEnd = from;
		for (int i = 0; i < tracks.size(); i++) {
			final double start = i == 0 ? 0 : path.getEnd(i - 1);
			final double end = path.getEnd(i);
			final boolean along = end > from + 1E-6 && start < to - 1E-6;
			final boolean at = to - from <= 1E-6 && from > start - 1E-6 && from <= end + 1E-6;
			if (!along && !at) {
				if (start >= to - 1E-6) {
					break;
				}
				continue;
			}
			final T track = tracks.get(i);
			if (track.isSidingTrack() || !track.hasSignal()) {
				// Not a section: a single track stretch before it ends there
				if (singleTrack != null) {
					groups.add(new Group(singleTrack, singleTrackStart, singleTrackEnd));
					singleTrack = null;
				}
				continue;
			}
			final boolean forwards = track.getStartNode() <= track.getEndNode();
			final double partFrom = Math.max(from, start) - start;
			final double partTo = Math.max(partFrom, Math.min(to, end) - start);
			// The sections of the part, each with how far along the path it lets the train run
			final float pieceSpeed = track.canAccelerate() ? track.getMaxSpeed() : -1;
			final int count = BlockSections.getPieceCount(track.getTrackLength(), pieceSpeed);
			final double pieceLength = track.getTrackLength() / count;
			final List<List<Arbiter.Claim<UUID>>> pieceClaims = new ArrayList<>();
			final List<Double> pieceStarts = new ArrayList<>();
			final List<Double> pieceEnds = new ArrayList<>();
			for (final UUID piece : BlockSections.getPieces(track.getTrackKey(), track.getTrackLength(), pieceSpeed, forwards, partFrom, partTo)) {
				final int index = indexOf(track.getTrackKey(), piece, count);
				final int pieceAlong = forwards ? index : count - 1 - index;
				final List<Arbiter.Claim<UUID>> claims = new ArrayList<>();
				final List<UUID> block = sections == null ? null : sections.getSection(piece);
				for (final UUID unit : block == null ? Collections.singletonList(piece) : block) {
					if (taken.add(unit)) {
						claims.add(Arbiter.Claim.track(unit));
					}
				}
				pieceClaims.add(claims);
				pieceStarts.add(Math.max(from, start + pieceAlong * pieceLength));
				pieceEnds.add(Math.min(start + (pieceAlong + 1) * pieceLength, Math.max(to, start)));
			}
			final boolean turnsBack = i + 1 < tracks.size() && path.turnsBack(i, i + 1);
			if (track.isTwoWay() || turnsBack) {
				// Single track, run one way, or both ways where the train turns back on it
				if (singleTrack == null) {
					singleTrack = new ArrayList<>();
					singleTrackStart = Math.max(from, start);
				}
				pieceClaims.forEach(singleTrack::addAll);
				final UUID direction = directionOf(track.getTrackKey());
				if (turnsBack) {
					singleTrack.add(Arbiter.Claim.singleTrackBothWays(direction));
					groups.add(new Group(singleTrack, singleTrackStart, end));
					singleTrack = null;
				} else {
					singleTrack.add(Arbiter.Claim.singleTrack(direction, forwards));
					singleTrackEnd = Math.min(end, Math.max(to, start));
				}
			} else {
				if (singleTrack != null) {
					groups.add(new Group(singleTrack, singleTrackStart, singleTrackEnd));
					singleTrack = null;
				}
				for (int k = 0; k < pieceClaims.size(); k++) {
					if (!pieceClaims.get(k).isEmpty()) {
						groups.add(new Group(pieceClaims.get(k), pieceStarts.get(k), pieceEnds.get(k)));
					} else if (!groups.isEmpty()) {
						// Taken already by the group before, as a signal block over several rails: that group reaches further
						final Group last = groups.remove(groups.size() - 1);
						groups.add(new Group(new ArrayList<>(last.claims), last.start, pieceEnds.get(k)));
					}
				}
			}
		}
		if (singleTrack != null) {
			groups.add(new Group(singleTrack, singleTrackStart, singleTrackEnd));
		}
		return groups;
	}

	private static int indexOf(UUID rail, UUID piece, int count) {
		for (int k = 0; k < count; k++) {
			if (BlockSections.getPieceKey(rail, k).equals(piece)) {
				return k;
			}
		}
		return 0;
	}

	/**
	 * All the claims of some groups, in order, each resource once: a direction claimed both ways becomes one claim both
	 * ways.
	 */
	public static List<Arbiter.Claim<UUID>> claimsOf(Collection<Group> groups) {
		final List<Arbiter.Claim<UUID>> claims = new ArrayList<>();
		final Map<UUID, Integer> indices = new HashMap<>();
		for (final Group group : groups) {
			for (final Arbiter.Claim<UUID> claim : group.claims) {
				final Integer index = indices.get(claim.resource);
				if (index == null) {
					indices.put(claim.resource, claims.size());
					claims.add(claim);
				} else if (claim.kind == Resource.Kind.SINGLE_TRACK && claims.get(index).way != claim.way) {
					claims.set(index, Arbiter.Claim.singleTrackBothWays(claim.resource));
				}
			}
		}
		return claims;
	}
}
