package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.block.BlockPSDTop;
import mtr.block.IBlock;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public abstract class RenderRouteBase<T extends BlockPSDTop.TileEntityRouteBase> extends BlockEntityRendererMapper<T> implements IGui, IBlock {

	protected final float topPadding;
	protected final float bottomPadding;
	protected final float sidePadding;
	private final float z;
	private final boolean transparentWhite;
	private final Property<Integer> arrowDirectionProperty;

	public RenderRouteBase(BlockEntityRenderDispatcher dispatcher, float z, float topPadding, float bottomPadding, float sidePadding, boolean transparentWhite, Property<Integer> arrowDirectionProperty) {
		super(dispatcher);
		this.z = z / 16;
		this.topPadding = topPadding / 16;
		this.bottomPadding = bottomPadding / 16;
		this.sidePadding = sidePadding / 16;
		this.transparentWhite = transparentWhite;
		this.arrowDirectionProperty = arrowDirectionProperty;
	}

	@Override
	public final void render(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final Level world = entity.getLevel();
		if (world == null) {
			return;
		}

		final BlockPos pos = entity.getBlockPos();
		final BlockState state = world.getBlockState(pos);
		final Direction facing = IBlock.getStatePropertySafe(state, HorizontalDirectionalBlock.FACING);

		final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations();
		storedMatrixTransformations.add(matricesNew -> {
			IDrawing.translateWorldOrigin(matricesNew, 0.5 + entity.getBlockPos().getX(), entity.getBlockPos().getY(), 0.5 + entity.getBlockPos().getZ());
			UtilitiesClient.rotateYDegrees(matricesNew, -facing.toYRot());
		});

		renderAdditionalUnmodified(storedMatrixTransformations.copy(), state, facing, light);

		if (!RenderTrains.shouldNotRender(pos, RenderTrains.maxTrainRenderDistance, null)) {
			final long platformId = entity.getPlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE);

			if (platformId != 0) {
				storedMatrixTransformations.add(matricesNew -> {
					matricesNew.translate(0, 1, 0);
					UtilitiesClient.rotateZDegrees(matricesNew, 180);
					matricesNew.translate(-0.5, -getAdditionalOffset(state), z);
				});

				final int leftCount = getTextureNumber(world, pos, facing, true);
				final int rightCount = getTextureNumber(world, pos, facing, false);
				// The run shared out by the length of each block's piece (0.91.3), a whole block each for straight blocks
				final float leftBlocks = mtr.block.CurvedPSD.runLength(world, pos, true, leftCount);
				final float rightBlocks = mtr.block.CurvedPSD.runLength(world, pos, false, rightCount);
				final float ownLength = mtr.block.CurvedPSD.pieceLength(world, pos);
				final int color = getShadingColor(facing, ARGB_WHITE);
				final RenderType renderType = getRenderType(world, mtr.block.CurvedPSD.walk(world, pos, true, leftCount), state);

				if ((renderType == RenderType.ARROW || renderType == RenderType.ROUTE) && IBlock.getStatePropertySafe(state, SIDE_EXTENDED) != EnumSide.SINGLE) {
					final float width = leftBlocks + rightBlocks + ownLength - sidePadding * 2;
					final float height = 1 - topPadding - bottomPadding;
					final int arrowDirection = IBlock.getStatePropertySafe(state, arrowDirectionProperty);

					final ResourceLocation resourceLocation;
					if (renderType == RenderType.ARROW) {
						resourceLocation = ClientData.DATA_CACHE.getDirectionArrow(platformId, (arrowDirection & 0b01) > 0, (arrowDirection & 0b10) > 0, HorizontalAlignment.CENTER, true, 0.25F, width / height, ARGB_WHITE, ARGB_BLACK, transparentWhite ? ARGB_WHITE : 0).resourceLocation;
					} else {
						resourceLocation = ClientData.DATA_CACHE.getRouteMap(platformId, false, arrowDirection == 2, width / height, transparentWhite).resourceLocation;
					}

					RenderTrains.scheduleRender(resourceLocation, false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matricesNew, vertexConsumer) -> {
						storedMatrixTransformations.transform(matricesNew);
						IDrawing.drawTexture(matricesNew, vertexConsumer, leftBlocks == 0 ? sidePadding : 0, topPadding, 0, 1 - (rightBlocks == 0 ? sidePadding : 0), 1 - bottomPadding, 0, (leftBlocks - (leftBlocks == 0 ? 0 : sidePadding)) / width, 0, (width - rightBlocks + (rightBlocks == 0 ? 0 : sidePadding)) / width, 1, facing.getOpposite(), color, light);
						matricesNew.popPose();
					});
				}

				renderAdditional(storedMatrixTransformations, platformId, state, leftBlocks, rightBlocks, ownLength, facing.getOpposite(), color, light);
			}
		}
	}

	@Override
	public boolean shouldRenderOffScreen(T blockEntity) {
		return true;
	}

	protected void renderAdditionalUnmodified(StoredMatrixTransformations storedMatrixTransformations, BlockState state, Direction facing, int light) {
	}

	protected float getAdditionalOffset(BlockState state) {
		return 0;
	}

	protected boolean isLeft(BlockState state) {
		return IBlock.getStatePropertySafe(state, SIDE_EXTENDED) == IBlock.EnumSide.LEFT;
	}

	protected boolean isRight(BlockState state) {
		return IBlock.getStatePropertySafe(state, SIDE_EXTENDED) == IBlock.EnumSide.RIGHT;
	}

	protected abstract RenderType getRenderType(BlockGetter world, BlockPos pos, BlockState state);

	/**
	 * @param leftBlocks  how long the run is on the left of this block, in blocks
	 * @param rightBlocks on the right
	 * @param ownLength   how long this block's piece is (1 for a straight block, its piece of the edge line for a curved one)
	 */
	protected abstract void renderAdditional(StoredMatrixTransformations storedMatrixTransformations, long platformId, BlockState state, float leftBlocks, float rightBlocks, float ownLength, Direction facing, int color, int light);

	/**
	 * Counts the blocks of the run beside this one on a side. Since 0.91.0 the next block is found along the bent edge line
	 * for a curved screen door ({@link mtr.block.CurvedPSD#walk}), and curved and straight blocks of one kind make one run.
	 */
	private static final int MOST_BLOCKS_IN_RUN = 256;

	private int getTextureNumber(BlockGetter world, BlockPos pos, Direction facing, boolean searchLeft) {
		int number = 0;
		final Block thisBlock = world.getBlockState(pos).getBlock();
		// Step by step, never past a block already counted (0.91.2: along a bent line two blocks could each find the other
		// as the next one, and the count ran on for ever on the render thread)
		final java.util.Set<BlockPos> visited = new java.util.HashSet<>();
		BlockPos current = pos;

		while (number < MOST_BLOCKS_IN_RUN) {
			if (number > 0) {
				current = mtr.block.CurvedPSD.neighbour(world, current, searchLeft);
			}
			if (!visited.add(current)) {
				break;
			}
			final BlockState state = world.getBlockState(current);

			if (mtr.block.CurvedPSD.sameKind(state.getBlock(), thisBlock)) {
				final boolean isLeft = isLeft(state);
				final boolean isRight = isRight(state);

				if (number == 0 || (searchLeft ? !isRight : !isLeft)) {
					number++;
					if (searchLeft ? isLeft : isRight) {
						break;
					}
				} else {
					break;
				}
			} else {
				break;
			}
		}

		return number - 1;
	}

	public static int getShadingColor(Direction facing, int grayscaleColorByte) {
		final int colorByte = Math.round((grayscaleColorByte & 0xFF) * (facing.getAxis() == Direction.Axis.X ? 0.75F : 1));
		return ARGB_BLACK | ((colorByte << 16) + (colorByte << 8) + colorByte);
	}

	protected enum RenderType {ARROW, ROUTE, NONE}
}
