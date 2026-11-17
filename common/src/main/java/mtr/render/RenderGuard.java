package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Draws one block's own drawing so that an error in it (bad data read from a save, say) skips that block instead of
 * crashing the game (0.100.0). The drawing gets its own copy of the matrices, so that it can't leave the caller's
 * unbalanced; the first error of each kind is logged, later ones only skipped.
 */
public final class RenderGuard {

	private static final Logger LOGGER = LogManager.getLogger("MTR");
	private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

	private RenderGuard() {
	}

	public static void draw(String what, BlockPos pos, PoseStack matrices, Consumer<PoseStack> draw) {
		final PoseStack copy = new PoseStack();
		copy.last().pose().set(matrices.last().pose());
		copy.last().normal().set(matrices.last().normal());
		try {
			draw.accept(copy);
		} catch (Exception e) {
			if (FAILED.add(what)) {
				LOGGER.error("{} at {} could not be drawn and is skipped (logged once for each kind)", what, pos, e);
			}
		}
	}
}
