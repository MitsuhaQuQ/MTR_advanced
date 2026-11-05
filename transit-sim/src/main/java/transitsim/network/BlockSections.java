package transitsim.network;

import java.util.*;

/**
 * Long track cut into block sections (decoupling document 5.4, 2b): a train takes, and is kept out of, only the sections it
 * is on, rather than a whole long rail, so the one behind can follow it along a long line.
 * <p>
 * A rail's section length is the service braking distance at its own speed limit, {@code v² / (2a)} for the deceleration
 * {@link #deceleration}, and at least {@link #minLength}. A longer rail is cut into equal pieces, numbered from the end
 * at its smaller node, so both directions see the same pieces; the first piece keeps the rail's own key.
 * <p>
 * Signal blocks the players paint stay whole, every piece of their rails one section, however long: cut, two trains could
 * each take a part of one and hold each other up for good, which a day on a big world showed (the user's decision,
 * 2026-11-05).
 * <p>
 * Nothing here depends on anything but the rails, so both hosts cut the same way.
 */
public final class BlockSections {

	/**
	 * The deceleration the braking distance is worked out for, in metres per second per second; the server's settings set it.
	 */
	public static volatile double deceleration = 1.0;
	/**
	 * The shortest section, in blocks, longer than most trains, so a train stands in one or two.
	 */
	public static volatile double minLength = parseLength(System.getProperty("mtr.blockMinLength"));

	private static final long PIECE_MIX = 0x9E3779B97F4A7C15L;

	private static double parseLength(String value) {
		try {
			return value == null ? 400 : Math.max(1, Double.parseDouble(value));
		} catch (NumberFormatException e) {
			return 400;
		}
	}
	private static final int TICKS_PER_SECOND = 20;

	/**
	 * The units of each block, by each of its units, for blocks with more than one section: the section a unit is in.
	 */
	private final Map<UUID, List<UUID>> sectionOf = new HashMap<>();
	/**
	 * The rail and the number of the piece of each unit of a block.
	 */
	private final Map<UUID, Map.Entry<UUID, Integer>> pieceOf = new HashMap<>();

	/**
	 * What the hosts know of a rail.
	 */
	public interface RailInfo {

		/**
		 * @return the rail's length in blocks, or 0 if it isn't known
		 */
		double getLength(UUID rail);

		/**
		 * @return the rail's speed limit in blocks per tick, 0 if it isn't known, or less than 0 for a rail never cut
		 */
		float getMaxSpeed(UUID rail);
	}

	/**
	 * @param blocks the signal blocks, each with the keys of its rails
	 */
	public BlockSections(List<? extends Collection<UUID>> blocks, RailInfo railInfo) {
		for (int i = 0; i < blocks.size(); i++) {
			blocks.get(i).forEach(rail -> {
				final int count = getPieceCount(railInfo.getLength(rail), railInfo.getMaxSpeed(rail));
				for (int k = 0; k < count; k++) {
					pieceOf.putIfAbsent(getPieceKey(rail, k), new AbstractMap.SimpleImmutableEntry<>(rail, k));
				}
			});
			final List<List<UUID>> sections = Collections.singletonList(allUnits(blocks.get(i), railInfo));
			// A rail in blocks of more than one colour takes the sections of all of them
			for (final List<UUID> section : sections) {
				section.forEach(unit -> {
					final List<UUID> existing = sectionOf.get(unit);
					if (existing == null) {
						sectionOf.put(unit, Collections.unmodifiableList(section));
					} else {
						final Set<UUID> merged = new LinkedHashSet<>(existing);
						merged.addAll(section);
						sectionOf.put(unit, Collections.unmodifiableList(new ArrayList<>(merged)));
					}
				});
			}
		}
	}

	/**
	 * @return the units of the signal block sections a unit is in, those of every block it is in, or null if it isn't in one
	 */
	public List<UUID> getSection(UUID unit) {
		return sectionOf.get(unit);
	}

	/**
	 * @return the rail a unit of a block is on and which piece of it, or null if it isn't in a block
	 */
	public Map.Entry<UUID, Integer> getPiece(UUID unit) {
		return pieceOf.get(unit);
	}

	/**
	 * @return how long a section is on a rail with this speed limit, in blocks
	 */
	public static double getSectionLength(float maxSpeed) {
		final double speed = Math.max(maxSpeed, 0) * TICKS_PER_SECOND;
		return Math.max(minLength, speed * speed / (2 * Math.max(deceleration, 0.01)));
	}

	/**
	 * @param maxSpeed the rail's speed limit in blocks per tick, or less than 0 for a rail never cut, as one a train can't
	 *                 speed up on (a platform, a siding, a turn back rail), where two trains must never be at once
	 * @return how many pieces a rail is cut into, 1 for one no longer than its section length
	 */
	public static int getPieceCount(double length, float maxSpeed) {
		if (maxSpeed < 0) {
			return 1;
		}
		final double sectionLength = getSectionLength(maxSpeed);
		return length <= sectionLength + 1E-6 ? 1 : (int) Math.ceil(length / sectionLength - 1E-9);
	}

	/**
	 * @return the key of a piece of a rail; the first piece's is the rail's own
	 */
	public static UUID getPieceKey(UUID rail, int piece) {
		return piece == 0 ? rail : new UUID(rail.getMostSignificantBits() ^ PIECE_MIX * piece, ~rail.getLeastSignificantBits() ^ piece);
	}

	/**
	 * @param forwards whether the path runs along the rail from its smaller node to its larger
	 * @param from     how far along the rail the part starts, in the path's direction
	 * @param to       how far along the rail it ends
	 * @return the pieces a part of a rail covers, in the path's direction; a part of no length at a piece's end covers none
	 */
	public static List<UUID> getPieces(UUID rail, double length, float maxSpeed, boolean forwards, double from, double to) {
		final int count = getPieceCount(length, maxSpeed);
		if (count == 1) {
			return Collections.singletonList(rail);
		}
		final List<UUID> pieces = new ArrayList<>();
		final double pieceLength = length / count;
		for (int k = 0; k < count; k++) {
			// Where the piece is in the path's direction
			final int piece = forwards ? k : count - 1 - k;
			final double start = k * pieceLength;
			final double end = start + pieceLength;
			if (start < to - 1E-6 && end > from + 1E-6 || from == to && k == Math.min(count - 1, (int) Math.floor(from / pieceLength))) {
				pieces.add(getPieceKey(rail, piece));
			}
		}
		return pieces;
	}

	/**
	 * @return where each piece starts along the rail in the path's direction, with its key, for every piece of a cut rail,
	 * or empty for a rail in one piece
	 */
	public static List<Map.Entry<Double, UUID>> getPieceStarts(UUID rail, double length, float maxSpeed, boolean forwards) {
		final int count = getPieceCount(length, maxSpeed);
		if (count == 1) {
			return Collections.emptyList();
		}
		final List<Map.Entry<Double, UUID>> starts = new ArrayList<>();
		final double pieceLength = length / count;
		for (int k = 0; k < count; k++) {
			starts.add(new AbstractMap.SimpleImmutableEntry<>(k * pieceLength, getPieceKey(rail, forwards ? k : count - 1 - k)));
		}
		return starts;
	}

	private static List<UUID> allUnits(Collection<UUID> rails, RailInfo railInfo) {
		final List<UUID> units = new ArrayList<>();
		new TreeSet<>(rails).forEach(rail -> {
			final int count = getPieceCount(railInfo.getLength(rail), railInfo.getMaxSpeed(rail));
			for (int k = 0; k < count; k++) {
				units.add(getPieceKey(rail, k));
			}
		});
		return units;
	}
}
