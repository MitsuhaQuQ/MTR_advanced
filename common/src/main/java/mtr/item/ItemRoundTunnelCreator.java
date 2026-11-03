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

import java.util.List;

public class ItemRoundTunnelCreator extends ItemNodeModifierSelectableBlockBase {

	private final int tunnelRadius;

	// The cleared circle is 2 * radius - 1 blocks wide and high, with its centre radius - 1 blocks above the rail
	public ItemRoundTunnelCreator(int radius) {
		super(false, 2 * radius - 1, 2 * radius - 1);
		tunnelRadius = radius;
	}

	@Override
	public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag tooltipFlag) {
		tooltip.add(Text.translatable("tooltip.mtr.rail_action_radius", tunnelRadius).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
		super.appendHoverText(stack, level, tooltip, tooltipFlag);
	}

	@Override
	protected boolean onConnect(Player player, ItemStack stack, RailwayData railwayData, BlockPos posStart, BlockPos posEnd, int radius, int height) {
		return railwayData.railwayDataRailActionsModule.markRailForRoundTunnel(player, posStart, posEnd, tunnelRadius);
	}
}
