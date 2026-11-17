package mtr.item;

import mtr.CreativeModeTabs;
import mtr.block.SignalBinding;
import mtr.block.TileEntitySignalBase;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The brush. Used sneaking on a signal it starts binding the signal by hand to the node clicked next (0.99.0,
 * {@link SignalBinding}): sneaking with an item in hand, the game doesn't let the block itself have the click. Everything
 * else the brush does is the blocks' own, when they are clicked with it. Sneaking on a free escalator it gives the
 * escalator's settings (0.100.0).
 */
public class ItemBrush extends ItemWithCreativeTabBase {

	public ItemBrush(CreativeModeTabs.Wrapper creativeModeTab) {
		super(creativeModeTab, properties -> properties.stacksTo(1));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Player player = context.getPlayer();
		if (player != null && player.isShiftKeyDown() && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof TileEntitySignalBase) {
			if (!context.getLevel().isClientSide) {
				SignalBinding.onBrush(context.getLevel(), context.getClickedPos(), player);
			}
			return InteractionResult.SUCCESS;
		}
		if (player != null && player.isShiftKeyDown() && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof mtr.block.BlockFreeEscalator.TileEntityFreeEscalator) {
			if (!context.getLevel().isClientSide) {
				mtr.escalator.FreeEscalators.showSettings(context.getLevel(), context.getClickedPos(), player);
			}
			return InteractionResult.SUCCESS;
		}
		return super.useOn(context);
	}
}
