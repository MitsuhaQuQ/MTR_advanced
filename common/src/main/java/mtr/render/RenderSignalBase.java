package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.block.BlockNode;
import mtr.block.BlockSignalLightBase;
import mtr.block.BlockSignalSemaphoreBase;
import mtr.block.IBlock;
import mtr.block.SignalBinding;
import mtr.block.TileEntitySignalBase;
import mtr.client.ClientData;
import mtr.data.IGui;
import mtr.data.Rail;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.mappings.UtilitiesClient;
import mtr.path.PathData;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public abstract class RenderSignalBase<T extends BlockEntityMapper> extends BlockEntityRendererMapper<T> implements IBlock, IGui {

	protected final boolean isSingleSided;
	protected final int aspects;

	public RenderSignalBase(BlockEntityRenderDispatcher dispatcher, boolean isSingleSided, int aspects) {
		super(dispatcher);
		this.isSingleSided = isSingleSided;
		this.aspects = aspects;
	}

	// TODO backwards compatibility
	@Deprecated
	public RenderSignalBase(BlockEntityRenderDispatcher dispatcher, boolean isSingleSided) {
		this(dispatcher, isSingleSided, 2);
	}

	@Override
	public final void render(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final BlockGetter world = entity.getLevel();
		if (world == null) {
			return;
		}

		final BlockPos pos = entity.getBlockPos();
		final BlockState state = world.getBlockState(pos);
		if (!(state.getBlock() instanceof BlockSignalLightBase || state.getBlock() instanceof BlockSignalSemaphoreBase)) {
			return;
		}
		final Direction facing = IBlock.getStatePropertySafe(state, HorizontalDirectionalBlock.FACING);
		if (RenderTrains.shouldNotRender(pos, RenderTrains.maxTrainRenderDistance, null)) {
			return;
		}

		// The sections the signal is bound to (0.99.0); a signal from before has none and takes the nearest node, as before
		final TileEntitySignalBase binding = entity instanceof TileEntitySignalBase && ((TileEntitySignalBase) entity).hasBinding() ? (TileEntitySignalBase) entity : null;
		final BlockPos nearestPos = binding == null ? keptNodePos(entity, world, pos, facing) : null;
		if (binding == null && nearestPos == null) {
			return;
		}

		matrices.pushPose();
		matrices.translate(0.5, 0, 0.5);

		for (int i = 0; i < 2; i++) {
			final Direction newFacing = (i == 1 ? facing.getOpposite() : facing);
			final int occupiedAspect;
			if (binding == null) {
				occupiedAspect = getOccupiedAspect(nearestPos, newFacing.toYRot() + 90);
			} else {
				final BlockPos node = binding.getNode(i);
				// Bound to nothing, or to a node no rail leaves the bound way any more: stop, flashing
				if (node == null || !SignalBinding.leadsOn(ClientData.RAILS, node, binding.getAngle(i))) {
					occupiedAspect = System.currentTimeMillis() / FLASH_MILLIS % 2 == 0 ? 1 : -1;
				} else {
					occupiedAspect = getOccupiedAspect(node, binding.getAngle(i));
				}
			}

			if (occupiedAspect >= 0) {
				matrices.pushPose();
				UtilitiesClient.rotateYDegrees(matrices, -newFacing.toYRot());
				final VertexConsumer vertexConsumer = vertexConsumers.getBuffer(MoreRenderLayers.getLight(new ResourceLocation("mtr:textures/block/white.png"), false));
				render(matrices, vertexConsumers, vertexConsumer, entity, tickDelta, newFacing, occupiedAspect, i == 1);
				// TODO temporary code
				render(matrices, vertexConsumers, vertexConsumer, entity, tickDelta, newFacing, occupiedAspect == 1, i == 1);
				// TODO temporary code end
				matrices.popPose();
			}

			if (isSingleSided) {
				break;
			}
		}

		matrices.popPose();
	}

	// TODO make abstract later
	protected void render(PoseStack matrices, MultiBufferSource vertexConsumers, VertexConsumer vertexConsumer, T entity, float tickDelta, Direction facing, int occupiedAspect, boolean isBackSide) {
	}

	// TODO temporary code
	protected void render(PoseStack matrices, MultiBufferSource vertexConsumers, VertexConsumer vertexConsumer, T entity, float tickDelta, Direction facing, boolean isOccupied, boolean isBackSide) {
	}
	// TODO temporary code end

	/**
	 * Counted in block sections ahead, as {@link mtr.client.SignalAspects} has it.
	 */
	private int getOccupiedAspect(BlockPos startPos, float facing) {
		return mtr.client.SignalAspects.getAspect(startPos, facing, aspects);
	}

	private static final long FLASH_MILLIS = 500;

	/**
	 * The node found once (0.101.1) rather than by looking through the blocks round the signal each frame, and looked for
	 * again when a block there changes ({@link SectionStamps})
	 */
	private final java.util.Map<T, Object[]> nodes = new java.util.WeakHashMap<>();

	private BlockPos keptNodePos(T entity, BlockGetter world, BlockPos pos, Direction facing) {
		final Object[] kept = nodes.get(entity);
		if (kept != null && kept[0] == facing && ((SectionStamps.Watch) kept[1]).isCurrent()) {
			return (BlockPos) kept[2];
		}
		final BlockPos nodePos = getNodePos(world, pos, facing);
		// The corners of the blocks looked through, which are in every section they reach into
		final java.util.List<BlockPos> corners = new java.util.ArrayList<>();
		for (final int x : new int[]{-4, 4}) {
			for (final int z : new int[]{-4, 4}) {
				for (final int y : new int[]{-5, 0}) {
					corners.add(pos.offset(x, y, z));
				}
			}
		}
		nodes.put(entity, new Object[]{facing, new SectionStamps.Watch(corners), nodePos});
		return nodePos;
	}

	private static BlockPos getNodePos(BlockGetter world, BlockPos pos, Direction facing) {
		final int[] checkDistance = {0, 1, -1, 2, -2, 3, -3, 4, -4};
		for (final int z : checkDistance) {
			for (final int x : checkDistance) {
				for (int y = -5; y <= 0; y++) {
					final BlockPos checkPos = pos.above(y).relative(facing.getClockWise(), x).relative(facing, z);
					final BlockState checkState = world.getBlockState(checkPos);
					if (checkState.getBlock() instanceof BlockNode) {
						return checkPos;
					}
				}
			}
		}
		return null;
	}
}
