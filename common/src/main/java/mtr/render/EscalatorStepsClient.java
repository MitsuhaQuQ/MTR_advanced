package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.block.BlockEscalatorStep;
import mtr.client.IDrawing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the original escalator's steps are, on the client, so that they can be drawn moving without a block entity on
 * each (0.100.0: nothing added to saves). Kept per chunk section: a section is looked through when its chunk arrives and
 * again whenever it is marked to be drawn again (a block in it changed, or its light); sections with no step are passed
 * over by their palette. Each frame the steps within {@link #DISTANCE} of the camera are drawn by
 * {@link RenderEscalatorStep}. Sections whose chunk is gone are forgotten, and everything when the world changes.
 */
public final class EscalatorStepsClient {

	/** Steps further than this from the camera are not drawn */
	public static final int DISTANCE = 64;

	/** Section key to the steps in it (packed block positions); only sections with steps */
	private static final Map<Long, long[]> STEPS = new HashMap<>();
	/** Sections to look through again, before the next frame; may be added to from other threads */
	private static final Set<Long> DIRTY = ConcurrentHashMap.newKeySet();
	private static Level level;

	private EscalatorStepsClient() {
	}

	/** A chunk arrived on the client: its sections are looked through before the next frame */
	public static void onChunkLoad(LevelChunk chunk) {
		final int minSection = chunk.getMinSection();
		for (int i = 0; i < chunk.getSectionsCount(); i++) {
			DIRTY.add(SectionPos.asLong(chunk.getPos().x, minSection + i, chunk.getPos().z));
		}
	}

	/** A section is to be drawn again: it may have gained or lost steps */
	public static void onSectionDirty(int sectionX, int sectionY, int sectionZ) {
		DIRTY.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
	}

	public static void render(PoseStack matrices, MultiBufferSource vertexConsumers, Level world, float tickDelta) {
		if (world == null) {
			return;
		}
		if (world != level) {
			level = world;
			STEPS.clear();
		}
		update(world);
		if (STEPS.isEmpty()) {
			return;
		}

		final Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		final int cameraSectionX = SectionPos.blockToSectionCoord(camera.x);
		final int cameraSectionY = SectionPos.blockToSectionCoord(camera.y);
		final int cameraSectionZ = SectionPos.blockToSectionCoord(camera.z);
		final int sections = DISTANCE / 16 + 1;
		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		final Iterator<Map.Entry<Long, long[]>> iterator = STEPS.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<Long, long[]> entry = iterator.next();
			final long key = entry.getKey();
			final int sectionX = SectionPos.x(key);
			final int sectionZ = SectionPos.z(key);
			if (!world.hasChunk(sectionX, sectionZ)) {
				iterator.remove();
				continue;
			}
			if (Math.abs(sectionX - cameraSectionX) > sections || Math.abs(SectionPos.y(key) - cameraSectionY) > sections || Math.abs(sectionZ - cameraSectionZ) > sections) {
				continue;
			}
			for (final long packed : entry.getValue()) {
				pos.set(packed);
				if (pos.distToCenterSqr(camera) > DISTANCE * DISTANCE) {
					continue;
				}
				final BlockState state = world.getBlockState(pos);
				if (!(state.getBlock() instanceof BlockEscalatorStep)) {
					continue;
				}
				final BlockPos stepPos = pos.immutable();
				RenderGuard.draw("Escalator step", stepPos, matrices, guarded -> {
					IDrawing.translateWorldOrigin(guarded, stepPos.getX(), stepPos.getY(), stepPos.getZ());
					RenderEscalatorStep.render(world, stepPos, state, tickDelta, guarded, vertexConsumers);
				});
			}
		}
	}

	/** Looks through the sections marked since the last frame */
	private static void update(Level world) {
		if (DIRTY.isEmpty()) {
			return;
		}
		final Iterator<Long> iterator = DIRTY.iterator();
		while (iterator.hasNext()) {
			final long key = iterator.next();
			iterator.remove();
			final int sectionX = SectionPos.x(key);
			final int sectionY = SectionPos.y(key);
			final int sectionZ = SectionPos.z(key);
			final ChunkAccess chunk = world.hasChunk(sectionX, sectionZ) ? world.getChunk(sectionX, sectionZ) : null;
			final int index = chunk == null ? -1 : chunk.getSectionIndexFromSectionY(sectionY);
			if (chunk == null || index < 0 || index >= chunk.getSectionsCount()) {
				STEPS.remove(key);
				continue;
			}
			final long[] found = find(chunk.getSection(index), SectionPos.sectionToBlockCoord(sectionX), SectionPos.sectionToBlockCoord(sectionY), SectionPos.sectionToBlockCoord(sectionZ));
			if (found == null) {
				STEPS.remove(key);
			} else {
				STEPS.put(key, found);
			}
		}
	}

	/** The steps in a section, or null if none */
	private static long[] find(LevelChunkSection section, int baseX, int baseY, int baseZ) {
		if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() instanceof BlockEscalatorStep)) {
			return null;
		}
		long[] found = new long[16];
		int count = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (section.getBlockState(x, y, z).getBlock() instanceof BlockEscalatorStep) {
						if (count == found.length) {
							found = java.util.Arrays.copyOf(found, count * 2);
						}
						found[count++] = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
					}
				}
			}
		}
		return count == 0 ? null : java.util.Arrays.copyOf(found, count);
	}
}
