package mtr.item;

import mtr.CreativeModeTabs;
import mtr.escalator.FreeEscalators;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The free escalator (0.100.0): clicking blocks marks, in order, the points the escalator runs over (their top middle;
 * the chat says the slope from the point before and has buttons to build, take back the last point or clear them);
 * sneaking and clicking builds it ({@link FreeEscalators#build}). Not used up.
 */
public class ItemFreeEscalator extends ItemWithCreativeTabBase {

	public ItemFreeEscalator() {
		super(CreativeModeTabs.ESCALATORS_LIFTS, properties -> properties.stacksTo(1));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.FAIL;
		}
		if (!context.getLevel().isClientSide) {
			if (player.isShiftKeyDown()) {
				FreeEscalators.build(context.getLevel(), player);
			} else {
				FreeEscalators.mark(player, context.getClickedPos());
			}
		}
		return InteractionResult.SUCCESS;
	}
}
