package mtr.item;

import mtr.CreativeModeTabs;
import mtr.mappings.Text;
import mtr.packet.PacketTrainDataGuiServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Sets a station's area in three dimensions: a block clicked, then another, make a box, which is given to a station as its
 * main area or one of its other rectangles together with the heights it takes in, so stations one above another, such as
 * an underground line under a main line, are told apart.
 */
public class ItemStationAreaTool extends ItemBlockClickingBase {

	public ItemStationAreaTool() {
		super(CreativeModeTabs.CORE, properties -> properties.stacksTo(1));
	}

	@Override
	protected void onStartClick(UseOnContext context, CompoundTag compoundTag) {
		if (context.getPlayer() != null) {
			context.getPlayer().displayClientMessage(Text.translatable("gui.mtr.station_area_tool_first", context.getClickedPos().toShortString()), true);
		}
	}

	@Override
	protected void onEndClick(UseOnContext context, BlockPos posEnd, CompoundTag compoundTag) {
		if (context.getPlayer() instanceof ServerPlayer) {
			PacketTrainDataGuiServer.openStationAreaToolScreenS2C((ServerPlayer) context.getPlayer(), posEnd, context.getClickedPos());
		}
	}

	@Override
	protected boolean clickCondition(UseOnContext context) {
		return true;
	}
}
