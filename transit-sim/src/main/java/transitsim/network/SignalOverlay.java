package transitsim.network;

import java.io.*;
import java.util.*;

/**
 * The signal layer laid over the track sections (stages document round 1): for each track section with rails in signal
 * blocks, how many block sections each of its rails is cut into and which signal blocks each is in, by the rules of
 * {@link BlockSections}. It is kept by the track section's hash, so a change of signals changes only this layer and a
 * change of rails only the entries of the sections that changed.
 */
public final class SignalOverlay {

	private static final int FORMAT = 1;

	private SignalOverlay() {
	}

	/**
	 * One track section's signals.
	 */
	public static final class Entry {

		private final int[] pieces;
		private final long[][] blocks;

		private Entry(int[] pieces, long[][] blocks) {
			this.pieces = pieces;
			this.blocks = blocks;
		}

		/**
		 * @return how many block sections the rail is cut into, 1 for a rail in no signal block or not cut
		 */
		public int getPieces(int rail) {
			return pieces[rail];
		}

		/**
		 * @return the signal blocks the rail is in, in order
		 */
		public long[] getBlocks(int rail) {
			return blocks[rail].clone();
		}

		public int getRailCount() {
			return pieces.length;
		}

		public byte[] toBytes() {
			try {
				final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				final DataOutputStream output = new DataOutputStream(bytes);
				output.writeInt(FORMAT);
				output.writeInt(pieces.length);
				for (int i = 0; i < pieces.length; i++) {
					output.writeInt(pieces[i]);
					output.writeInt(blocks[i].length);
					for (final long block : blocks[i]) {
						output.writeLong(block);
					}
				}
				return bytes.toByteArray();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		public static Entry fromBytes(byte[] data) throws IOException {
			final DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
			if (input.readInt() != FORMAT) {
				throw new IOException("Unknown signal overlay format");
			}
			final int count = input.readInt();
			if (count < 0 || count > 1 << 20) {
				throw new IOException("Bad rail count " + count);
			}
			final int[] pieces = new int[count];
			final long[][] blocks = new long[count][];
			for (int i = 0; i < count; i++) {
				pieces[i] = input.readInt();
				final int blockCount = input.readInt();
				if (blockCount < 0 || blockCount > 1 << 16) {
					throw new IOException("Bad block count " + blockCount);
				}
				blocks[i] = new long[blockCount];
				for (int j = 0; j < blockCount; j++) {
					blocks[i][j] = input.readLong();
				}
			}
			return new Entry(pieces, blocks);
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Entry && Arrays.equals(pieces, ((Entry) object).pieces) && Arrays.deepEquals(blocks, ((Entry) object).blocks);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(pieces) * 31 + Arrays.deepHashCode(blocks);
		}
	}

	/**
	 * @param blocks the rails of each signal block by the block's ID
	 * @return the entry of each track section with rails in a signal block, by the section's hash
	 */
	public static Map<Long, Entry> build(Map<Long, TrackSections.Section> sections, Map<Long, ? extends Collection<UUID>> blocks) {
		final Map<UUID, TreeSet<Long>> railBlocks = new HashMap<>();
		blocks.forEach((blockId, rails) -> rails.forEach(rail -> railBlocks.computeIfAbsent(rail, key -> new TreeSet<>()).add(blockId)));
		final Map<Long, Entry> entries = new TreeMap<>();
		sections.forEach((hash, section) -> {
			final int count = section.getRailCount();
			final int[] pieces = new int[count];
			final long[][] sectionBlocks = new long[count][];
			boolean any = false;
			for (int i = 0; i < count; i++) {
				final TrackSections.Rail rail = section.getRail(i);
				final TreeSet<Long> inBlocks = railBlocks.get(rail.getKey());
				if (inBlocks == null) {
					pieces[i] = 1;
					sectionBlocks[i] = new long[0];
				} else {
					any = true;
					pieces[i] = BlockSections.getPieceCount(rail.length, getCutSpeed(rail));
					sectionBlocks[i] = inBlocks.stream().mapToLong(Long::longValue).toArray();
				}
			}
			if (any) {
				entries.put(hash, new Entry(pieces, sectionBlocks));
			}
		});
		return entries;
	}

	/**
	 * @return the speed a rail is cut for, in blocks per tick, or -1 for a rail never cut
	 */
	public static float getCutSpeed(TrackSections.Rail rail) {
		return rail.canAccelerate ? SpeedLimits.toBlocksPerTick(rail.speedKmh) : -1;
	}
}
