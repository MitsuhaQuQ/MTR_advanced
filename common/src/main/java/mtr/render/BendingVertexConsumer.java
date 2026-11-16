package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.minecraft.world.phys.Vec3;
import transitsim.geometry.PlatformEdges;

import java.util.function.BiConsumer;

/**
 * Bends what is drawn for a curved screen door's block onto its piece of the edge line (0.91.0, {@link
 * mtr.block.CurvedPSD}): the straight block's renderer draws as usual, and each vertex, which reaches the consumer already
 * turned to the view (the render matrices hold the camera's rotation in 1.20.1), is taken back to the block's own
 * coordinates with the inverse of the block's render matrix, moved as {@link PlatformEdges.Piece#point} says (onto the block's piece of the edge curve, or of its chord) and taken
 * forward again; its normal likewise. While a curved block is drawn, {@link #current}
 * is set, and {@link RenderTrains#scheduleRender} wraps what is queued then, so the queued drawing is bent too.
 */
public class BendingVertexConsumer implements VertexConsumer {

	/**
	 * The curved block being drawn, while its renderer runs on the render thread; null otherwise
	 */
	private static Context current;

	private final VertexConsumer inner;
	private final Context context;

	public BendingVertexConsumer(VertexConsumer inner, Context context) {
		this.inner = inner;
		this.context = context;
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
	 * @return the callback, bent when queued while a curved block is drawn. The queue is drawn later, maybe in another
	 * frame with the camera moved (0.91.2: text on the tops flickered as the camera moved), so the block's matrices are
	 * worked out again from the matrices the queue is drawn with, as the queued drawing itself places the block.
	 */
	public static BiConsumer<PoseStack, VertexConsumer> wrap(BiConsumer<PoseStack, VertexConsumer> callback) {
		final Context context = current;
		return context == null ? callback : (matrices, vertexConsumer) -> callback.accept(matrices, new BendingVertexConsumer(vertexConsumer, context.drawnWith(matrices)));
	}

	/**
	 * @return buffers whose every consumer is bent
	 */
	public static MultiBufferSource wrap(MultiBufferSource source, Context context) {
		return renderType -> new BendingVertexConsumer(source.getBuffer(renderType), context);
	}

	@Override
	public VertexConsumer vertex(double x, double y, double z) {
		// From the view to the block's own coordinates, bent, and back
		final Vector4f local = context.toBlock.transform(new Vector4f((float) x, (float) y, (float) z, 1));
		final double[] bent = context.bend.point(context.facingX, context.facingZ, local.x, local.z);
		final Vector4f view = context.toView.transform(new Vector4f((float) bent[0], local.y, (float) bent[1], 1));
		inner.vertex(view.x, view.y, view.z);
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
		final Vector3f local = context.normalToBlock.transform(new Vector3f(x, y, z));
		final double[] bent = context.bend.direction(context.facingX, context.facingZ, local.x, local.z);
		final Vector3f view = context.normalToView.transform(new Vector3f((float) bent[0], local.y, (float) bent[1]));
		if (view.lengthSquared() < 1E-12) {
			inner.normal(x, y, z);
		} else {
			view.normalize();
			inner.normal(view.x, view.y, view.z);
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
	 * A curved block being drawn: its bend, the facing of the straight block it is drawn as, and the render matrices at the
	 * block's corner this frame (to the view and back)
	 */
	public static final class Context {

		private final PlatformEdges.Piece bend;
		private final Direction facing;
		private final int facingX;
		private final int facingZ;
		private final BlockPos pos;
		private final Matrix4f toView;
		private final Matrix4f toBlock;
		private final Matrix3f normalToView;
		private final Matrix3f normalToBlock;

		/**
		 * @param blockPose the render matrices at the block's corner, as a block entity renderer gets them
		 */
		public Context(PlatformEdges.Piece bend, Direction facing, BlockPos pos, PoseStack.Pose blockPose) {
			this(bend, facing, pos, blockPose.pose(), blockPose.normal());
		}

		private Context(PlatformEdges.Piece bend, Direction facing, BlockPos pos, Matrix4f pose, Matrix3f normal) {
			this.bend = bend;
			this.facing = facing;
			facingX = facing.getStepX();
			facingZ = facing.getStepZ();
			this.pos = pos;
			toView = new Matrix4f(pose);
			toBlock = new Matrix4f(pose).invert();
			normalToView = new Matrix3f(normal);
			normalToBlock = new Matrix3f(normal).invert();
		}

		/**
		 * @return the same block with its matrices worked out from matrices originating at the camera, now
		 */
		private Context drawnWith(PoseStack matrices) {
			final Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
			final Matrix4f pose = new Matrix4f(matrices.last().pose()).translate((float) (pos.getX() - camera.x), (float) (pos.getY() - camera.y), (float) (pos.getZ() - camera.z));
			return new Context(bend, facing, pos, pose, matrices.last().normal());
		}
	}
}
