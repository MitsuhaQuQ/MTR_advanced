package cn.zbx1425.mtrsteamloco.item;

import mtr.CreativeModeTabs;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import mtr.mappings.Text;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import cn.zbx1425.mtrsteamloco.block.BlockDirectNode.BlockEntityDirectNode;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import mtr.mappings.RegistryUtilities;

import java.util.function.Function;
import java.util.List;

public class BlockItemDirectNode extends BlockItem {
	public final CreativeModeTabs.Wrapper creativeModeTab;

    public BlockItemDirectNode(CreativeModeTabs.Wrapper creativeModeTab, Block block)  {
		super(block, RegistryUtilities.createItemProperties(creativeModeTab::get));
        this.creativeModeTab = creativeModeTab;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> list, TooltipFlag flag) {
        if (stack.getItem() instanceof BlockItemDirectNode bi) {
            CompoundTag tag = stack.getTagElement("BlockEntityTag");
            if (tag == null) {
                list.add(Text.translatable("tooltip.mtrsteamloco.direct_node.unbound"));
                return;
            }
            if (tag.contains(BlockEntityDirectNode.KEY_ANGLE)) {
                double angle = tag.getDouble(BlockEntityDirectNode.KEY_ANGLE);
                list.add(Text.translatable("tooltip.mtrsteamloco.direct_node.bound", angle));
            } else {
                list.add(Text.translatable("tooltip.mtrsteamloco.direct_node.unbound"));
            }
        }
    }
}