package mtr.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How often each section of the world has changed on the client (0.101.1), so what a block's drawing found by looking at
 * its neighbours (how long its run of screen door tops is, where its signal's node is) can be kept until a block there
 * changes. A block changing marks its section and those of the blocks next to it ({@code LevelRenderer.setSectionDirty}),
 * as does a chunk arriving; kept things are also looked at again after {@link #LONGEST_MILLIS}, for data in block entities
 * that changes without the block.
 */
public final class SectionStamps {

	public static final long LONGEST_MILLIS = 2000;
	/** Marked from the render thread and from chunk loading */
	private static final Map<Long, Integer> STAMPS = new ConcurrentHashMap<>();
	private static volatile int epoch;

	private SectionStamps() {
	}

	public static void onSectionDirty(int sectionX, int sectionY, int sectionZ) {
		STAMPS.merge(SectionPos.asLong(sectionX, sectionY, sectionZ), 1, Integer::sum);
	}

	public static void onChunkLoad(LevelChunk chunk) {
		final int minSection = chunk.getMinSection();
		for (int i = 0; i < chunk.getSectionsCount(); i++) {
			onSectionDirty(chunk.getPos().x, minSection + i, chunk.getPos().z);
		}
	}

	private static Object lastWorld;

	/** Each frame: another world clears what was kept */
	public static void onFrame(Object world) {
		if (world != lastWorld) {
			lastWorld = world;
			clear();
		}
	}

	/** Another world: everything kept is made again */
	public static void clear() {
		STAMPS.clear();
		epoch++;
	}

	private static int stampOf(long section) {
		return STAMPS.getOrDefault(section, 0);
	}

	/**
	 * The sections of some blocks, and how often they had changed when they were looked at
	 */
	public static final class Watch {

		private final long[] sections;
		private final long sum;
		private final int madeEpoch;
		private final long madeMillis;

		public Watch(Iterable<BlockPos> positions) {
			final java.util.Set<Long> distinct = new java.util.HashSet<>();
			for (final BlockPos pos : positions) {
				distinct.add(SectionPos.asLong(pos));
			}
			sections = new long[distinct.size()];
			int i = 0;
			long total = 0;
			for (final long section : distinct) {
				sections[i++] = section;
				total += stampOf(section);
			}
			sum = total;
			madeEpoch = epoch;
			madeMillis = System.currentTimeMillis();
		}

		/** No block of the sections changed since, and not too long ago */
		public boolean isCurrent() {
			if (madeEpoch != epoch || System.currentTimeMillis() - madeMillis > LONGEST_MILLIS) {
				return false;
			}
			long total = 0;
			for (final long section : sections) {
				total += stampOf(section);
			}
			return total == sum;
		}
	}
}
