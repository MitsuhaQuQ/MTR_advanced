package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import transitsim.geometry.PlatformEdges;

import java.util.function.BiConsumer;

/**
 * Bends what is drawn for a curved screen door's block onto its piece of the edge line (0.91.0, {@link
 * mtr.block.CurvedPSD}): the straight block's renderer draws as usual, in coordinates from the camera, and each vertex is
 * moved as {@link PlatformEdges.Bend#point} says, its normal turned with it. While a curved block is drawn, {@link #current}
 * is set, and {@link RenderTrains#scheduleRender} wraps what is queued then, so the queued drawing is bent too.
 */
public class BendingVertexConsumer implements VertexConsumer {

	/**
	 * The curved block being drawn, while its renderer runs on the render thread; null otherwise
	 */
	private static Context current;

	private final VertexConsumer inner;
	private final Context context;
	private final Vec3 camera;

	public BendingVertexConsumer(VertexConsumer inner, Context context) {
		this.inner = inner;
		this.context = context;
		camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
	}

	/**
	 * Draws with the bend of a curved block: what the callback draws now or queues is bent
	 */
	public static void drawBent(Context context, Runnable draw) {
		final Context previous = current;
		current = context;
		try {
			draw.run();
		} finally {
			current = previous;
		}
	}

	/**
	 * @return the callback, bent when queued while a curved block is drawn
	 */
	public static BiConsumer<PoseStack, VertexConsumer> wrap(BiConsumer<PoseStack, VertexConsumer> callback) {
		final Context context = current;
		return context == null ? callback : (matrices, vertexConsumer) -> callback.accept(matrices, new BendingVertexConsumer(vertexConsumer, context));
	}

	/**
	 * @return buffers whose every consumer is bent
	 */
	public static MultiBufferSource wrap(MultiBufferSource source, Context context) {
		return renderType -> new BendingVertexConsumer(source.getBuffer(renderType), context);
	}

	@Override
	public VertexConsumer vertex(double x, double y, double z) {
		// From the camera to the block's own coordinates and back
		final double localX = x + camera.x - context.pos.getX();
		final double localZ = z + camera.z - context.pos.getZ();
		final double[] bent = context.bend.point(context.facingX, context.facingZ, localX, localZ);
		inner.vertex(bent[0] + context.pos.getX() - camera.x, y, bent[1] + context.pos.getZ() - camera.z);
		return this;
	}

	@Override
	public VertexConsumer color(int red, int green, int blue, int alpha) {
		inner.color(red, green, blue, alpha);
		return this;
	}

	@Override
	public VertexConsumer uv(float u, float v) {
		inner.uv(u, v);
		return this;
	}

	@Override
	public VertexConsumer overlayCoords(int u, int v) {
		inner.overlayCoords(u, v);
		return this;
	}

	@Override
	public VertexConsumer uv2(int u, int v) {
		inner.uv2(u, v);
		return this;
	}

	@Override
	public VertexConsumer normal(float x, float y, float z) {
		final double[] bent = context.bend.direction(context.facingX, context.facingZ, x, z);
		final double length = Math.sqrt(bent[0] * bent[0] + y * y + bent[1] * bent[1]);
		if (length < 1E-6) {
			inner.normal(x, y, z);
		} else {
			inner.normal((float) (bent[0] / length), (float) (y / length), (float) (bent[1] / length));
		}
		return this;
	}

	@Override
	public void endVertex() {
		inner.endVertex();
	}

	@Override
	public void defaultColor(int red, int green, int blue, int alpha) {
		inner.defaultColor(red, green, blue, alpha);
	}

	@Override
	public void unsetDefaultColor() {
		inner.unsetDefaultColor();
	}

	/**
	 * A curved block being drawn: its position, its bend and the facing of the straight block it is drawn as
	 */
	public static final class Context {

		private final BlockPos pos;
		private final PlatformEdges.Bend bend;
		private final int facingX;
		private final int facingZ;

		public Context(BlockPos pos, PlatformEdges.Bend bend, Direction facing) {
			this.pos = pos;
			this.bend = bend;
			facingX = facing.getStepX();
			facingZ = facing.getStepZ();
		}
	}
}
