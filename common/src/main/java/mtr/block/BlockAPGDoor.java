package mtr.block;

import mtr.BlockEntityTypes;
import mtr.Items;
import mtr.mappings.BlockEntityMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

public class BlockAPGDoor extends BlockPSDAPGDoorBase {

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityAPGDoor(pos, state);
	}

	@Override
	public Item asItem() {
		return Items.APG_DOOR.get();
	}

	public static class TileEntityAPGDoor extends TileEntityPSDAPGDoorBase {

		public TileEntityAPGDoor(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.APG_DOOR_TILE_ENTITY.get(), pos, state);
		}

		/**
		 * For the curved screen doors (0.91.0), which have block entity types of their own
		 */
		protected TileEntityAPGDoor(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
			super(type, pos, state);
		}
	}
}
