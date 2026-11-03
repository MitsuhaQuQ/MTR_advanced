package mtr.item;

import mtr.data.RailwayData;
import mtr.mappings.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class ItemRoundTunnelWallCreator extends ItemNodeModifierSelectableBlockBase {

	private final int tunnelRadius;

	// The ring wraps the round tunnel of the same radius, so its outside is 2 * radius + 1 blocks wide and high
	public ItemRoundTunnelWallCreator(int radius) {
		super(true, 2 * radius + 1, 2 * radius + 1);
		tunnelRadius = radius;
	}

	@Override
	public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag tooltipFlag) {
		tooltip.add(Text.translatable("tooltip.mtr.rail_action_radius", tunnelRadius).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
		super.appendHoverText(stack, level, tooltip, tooltipFlag);
	}

	@Override
	protected boolean onConnect(Player player, ItemStack stack, RailwayData railwayData, BlockPos posStart, BlockPos posEnd, int radius, int height) {
		final BlockState state = getSavedState(stack);
		return state == null || railwayData.railwayDataRailActionsModule.markRailForRoundTunnelWall(player, posStart, posEnd, tunnelRadius, state);
	}
}
