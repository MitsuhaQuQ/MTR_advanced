package mtr.render;

import mtr.block.BlockStationNameBase;
import mtr.block.BlockStationNameEntrance;
import mtr.block.IBlock;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.IGui;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class RenderStationNameTiled<T extends BlockStationNameBase.TileEntityStationNameBase> extends RenderStationNameBase<T> {

	private final boolean showLogo;

	public RenderStationNameTiled(BlockEntityRenderDispatcher dispatcher, boolean showLogo) {
		super(dispatcher);
		this.showLogo = showLogo;
	}

	@Override
	protected void drawStationName(BlockGetter world, BlockPos pos, BlockState state, Direction facing, StoredMatrixTransformations storedMatrixTransformations, MultiBufferSource vertexConsumers, String stationName, int stationColor, int color, int light) {
		// The row found once (0.101.1), again when a block of it or next to it changes
		final int[] row = rowOf(world, pos, state);
		final int lengthLeft = row[0];
		final int lengthRight = row[1];

		final int totalLength = lengthLeft + lengthRight - 1;
		if (showLogo) {
			final int propagateProperty = IBlock.getStatePropertySafe(world, pos, BlockStationNameEntrance.STYLE);
			final float logoSize = propagateProperty % 2 == 0 ? 0.5F : 1;
			RenderTrains.scheduleRender(ClientData.DATA_CACHE.getStationNameEntrance(propagateProperty < 2 || propagateProperty >= 4 ? ARGB_WHITE : ARGB_BLACK, mtr.data.SignTexts.station(stationName), totalLength / logoSize).resourceLocation, false, RenderTrains.QueuedRenderLayer.INTERIOR, (matrices, vertexConsumer) -> {
				storedMatrixTransformations.transform(matrices);
				IDrawing.drawTexture(matrices, vertexConsumer, -0.5F, -logoSize / 2, 1, logoSize, (float) (lengthLeft - 1) / totalLength, 0, (float) lengthLeft / totalLength, 1, facing, color, light);
				matrices.popPose();
			});
		} else {
			RenderTrains.scheduleRender(ClientData.DATA_CACHE.getStationName(stationName, totalLength).resourceLocation, false, RenderTrains.QueuedRenderLayer.EXTERIOR, (matrices, vertexConsumer) -> {
				storedMatrixTransformations.transform(matrices);
				IDrawing.drawTexture(matrices, vertexConsumer, -0.5F, -0.5F, 1, 1, (float) (lengthLeft - 1) / totalLength, 0, (float) lengthLeft / totalLength, 1, facing, color, light);
				matrices.popPose();
			});
		}
	}

	private final java.util.Map<BlockPos, Object[]> rows = new java.util.HashMap<>();
	private Object rowsWorld;

	private int[] rowOf(BlockGetter world, BlockPos pos, BlockState state) {
		if (world != rowsWorld) {
			rowsWorld = world;
			rows.clear();
		}
		final Object[] kept = rows.get(pos);
		if (kept != null && kept[0] == state && ((SectionStamps.Watch) kept[1]).isCurrent()) {
			return (int[]) kept[2];
		}
		final int[] row = {getLength(world, pos, false), getLength(world, pos, true)};
		final Direction facing = IBlock.getStatePropertySafe(state, BlockStationNameBase.FACING);
		// Every block of the row and the one past each end
		final java.util.List<BlockPos> ends = new java.util.ArrayList<>();
		for (int i = -row[0]; i <= row[1]; i++) {
			ends.add(pos.relative(facing.getClockWise(), i));
		}
		if (rows.size() > 4096) {
			rows.clear();
		}
		rows.put(pos.immutable(), new Object[]{state, new SectionStamps.Watch(ends), row});
		return row;
	}

	private int getLength(BlockGetter world, BlockPos pos, boolean lookRight) {
		if (world == null) {
			return 1;
		}
		final Direction facing = IBlock.getStatePropertySafe(world, pos, BlockStationNameBase.FACING);
		final Block thisBlock = world.getBlockState(pos).getBlock();

		int length = 1;
		while (true) {
			final Block checkBlock = world.getBlockState(pos.relative(lookRight ? facing.getClockWise() : facing.getCounterClockWise(), length)).getBlock();
			if (checkBlock instanceof BlockStationNameBase && checkBlock == thisBlock) {
				length++;
			} else {
				break;
			}
		}

		return length;
	}
}
