package mtr.item;

import mtr.Blocks;
import mtr.CreativeModeTabs;
import mtr.block.BlockPSDAPGBase;
import mtr.block.BlockPSDTop;
import mtr.block.CurvedPSD;
import mtr.block.IBlock;
import mtr.mappings.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;
import java.util.function.Supplier;

/**
 * Puts down curved screen door or gate glass or doors (0.91.0, {@link CurvedPSD}), as the straight item does but on the
 * platform's edge line: glass one column at a time, a door as two leaves side by side along the line, a screen door with
 * its top above; the edge line is that of the platform edge block below, else of the nearest platform. A door used on
 * curved glass of its kind puts the door there instead, so doors can be set where the cars' doors stop. (Laying a whole
 * side by sneaking was taken out in 0.91.2, at the user's word.)
 */
public class ItemCurvedPSD extends ItemWithCreativeTabBase {

	private final Supplier<Block> block;
	private final Supplier<Block> glass;
	private final boolean isDoor;
	private final boolean hasTop;

	/**
	 * @param block  what it puts down
	 * @param glass  the curved glass of the same kind, which a door can replace
	 * @param hasTop whether a screen door top goes above
	 */
	public ItemCurvedPSD(Supplier<Block> block, Supplier<Block> glass, boolean isDoor, boolean hasTop) {
		super(CreativeModeTabs.RAILWAY_FACILITIES);
		this.block = block;
		this.glass = glass;
		this.isDoor = isDoor;
		this.hasTop = hasTop;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Level world = context.getLevel();
		if (world.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		final Player player = context.getPlayer();
		final ItemStack stack = context.getItemInHand();
		final boolean creative = player != null && player.isCreative();
		final BlockPos clicked = context.getClickedPos();
		final BlockState clickedState = world.getBlockState(clicked);

		// A door on curved glass of its kind: the glass there and next along the line make way for it
		if (isDoor && clickedState.is(glass.get())) {
			final BlockPos lowerPos = IBlock.getStatePropertySafe(clickedState, BlockPSDAPGBase.HALF) == DoubleBlockHalf.UPPER ? clicked.below() : clicked;
			final BlockPos rightPos = CurvedPSD.neighbour(world, lowerPos, false);
			if (!placeDoor(world, lowerPos, rightPos, true)) {
				message(player, "gui.mtr.curved_psd_no_room");
				return InteractionResult.FAIL;
			}
			if (!creative) {
				stack.shrink(1);
			}
			return InteractionResult.SUCCESS;
		}

		final BlockPos pos = clicked.relative(context.getClickedFace());
		final int line = CurvedPSD.lineAt(world, pos);
		if (line < 0) {
			message(player, "gui.mtr.curved_psd_no_edge");
			return InteractionResult.FAIL;
		}

		if (isDoor) {
			// The right leaf is on the next block along the line, as the straight door's is on the block to the right
			final BlockPos rightPos = rightOf(world, pos, line);
			if (rightPos == null || !placeDoor(world, pos, rightPos, false)) {
				message(player, "gui.mtr.curved_psd_no_room");
				return InteractionResult.FAIL;
			}
		} else {
			if (!isFree(world, pos)) {
				return InteractionResult.FAIL;
			}
			placeColumn(world, pos, line, block.get().defaultBlockState(), null);
			CurvedPSD.connectGlass(world, pos);
		}
		if (!creative) {
			stack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * @return the lower block of the column right of a column whose line is given, along the line; null if none is there
	 */
	private static BlockPos rightOf(Level world, BlockPos pos, int line) {
		final transitsim.geometry.PlatformEdges.Bend bend = transitsim.geometry.PlatformEdges.bend(transitsim.geometry.PlatformEdges.angleOf(line), transitsim.geometry.PlatformEdges.cutOfPacked(line));
		if (bend == null) {
			return null;
		}
		final double[] point = bend.beyond(false);
		return new BlockPos((int) Math.floor(pos.getX() + point[0]), pos.getY(), (int) Math.floor(pos.getZ() + point[1]));
	}

	/**
	 * Puts a door's two leaves on two columns along the line, over curved glass of the kind when asked
	 *
	 * @return false when there is no room or no edge line for one of them
	 */
	private boolean placeDoor(Level world, BlockPos leftPos, BlockPos rightPos, boolean overGlass) {
		final int leftLine = CurvedPSD.lineAt(world, leftPos);
		final int rightLine = CurvedPSD.lineAt(world, rightPos);
		if (leftLine < 0 || rightLine < 0 || leftPos.equals(rightPos)) {
			return false;
		}
		for (final BlockPos columnPos : new BlockPos[]{leftPos, rightPos}) {
			if (!(overGlass && world.getBlockState(columnPos).is(glass.get())) && !isFree(world, columnPos)) {
				return false;
			}
		}
		placeColumn(world, leftPos, leftLine, block.get().defaultBlockState(), IBlock.EnumSide.LEFT);
		placeColumn(world, rightPos, rightLine, block.get().defaultBlockState(), IBlock.EnumSide.RIGHT);
		// The glass either side no longer has glass where the door is
		for (final BlockPos glassPos : new BlockPos[]{CurvedPSD.neighbour(world, leftPos, true), CurvedPSD.neighbour(world, rightPos, false)}) {
			CurvedPSD.connectGlass(world, glassPos);
		}
		return true;
	}

	/**
	 * Puts down a column: the lower and upper block, and a screen door's top; each given the column's piece of the line and
	 * the four-way facing nearest it
	 *
	 * @param side the leaf for a door, null for glass
	 * @return false when the line doesn't cross the block
	 */
	private boolean placeColumn(Level world, BlockPos pos, int line, BlockState state, IBlock.EnumSide side) {
		if (line < 0) {
			return false;
		}
		final transitsim.geometry.PlatformEdges.Bend bend = transitsim.geometry.PlatformEdges.bend(transitsim.geometry.PlatformEdges.angleOf(line), transitsim.geometry.PlatformEdges.cutOfPacked(line));
		if (bend == null) {
			return false;
		}
		final Direction facing = CurvedPSD.facingOf(bend);
		for (int y = 0; y < 2; y++) {
			BlockState newState = state.setValue(BlockPSDAPGBase.FACING, facing).setValue(BlockPSDAPGBase.HALF, y == 0 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
			newState = side == null ? newState.setValue(IBlock.SIDE_EXTENDED, IBlock.EnumSide.SINGLE) : newState.setValue(IBlock.SIDE, side);
			world.setBlock(pos.above(y), newState, 2);
			CurvedPSD.setBend(world, pos.above(y), line);
		}
		if (hasTop) {
			world.setBlock(pos.above(2), Blocks.CURVED_PSD_TOP.get().defaultBlockState().setValue(BlockPSDTop.FACING, facing), 2);
			CurvedPSD.setBend(world, pos.above(2), line);
			world.setBlockAndUpdate(pos.above(2), BlockPSDTop.getActualState(world, pos.above(2)));
		}
		for (int y = 0; y < 2; y++) {
			world.blockUpdated(pos.above(y), world.getBlockState(pos.above(y)).getBlock());
		}
		return true;
	}

	private boolean isFree(Level world, BlockPos pos) {
		for (int y = 0; y < (hasTop ? 3 : 2); y++) {
			if (!world.getBlockState(pos.above(y)).canBeReplaced()) {
				return false;
			}
		}
		return true;
	}

	private static void message(Player player, String key) {
		if (player != null) {
			player.displayClientMessage(Text.translatable(key), true);
		}
	}

	@Override
	public void appendHoverText(ItemStack itemStack, Level level, List<Component> tooltip, TooltipFlag tooltipFlag) {
		tooltip.add(Text.translatable(isDoor ? "tooltip.mtr.curved_psd_door" : "tooltip.mtr.curved_psd_glass").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
	}
}
