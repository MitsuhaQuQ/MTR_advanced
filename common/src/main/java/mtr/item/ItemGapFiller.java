package mtr.item;

import mtr.CreativeModeTabs;
import mtr.block.BlockGapFiller;
import mtr.block.BlockPlatformEdge;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.mappings.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import transitsim.geometry.PlatformEdges;

import java.util.List;
import java.util.Map;

/**
 * Cuts a block by hand along the nearest rail (0.93.0), as the creators cut the gaps they leave: the line runs beside the
 * rail through the place clicked, and the block keeps the part towards the rail (a bridge's deck, a platform), or, used
 * sneaking, the part away from it (a tunnel's side). The block becomes a {@link BlockGapFiller} looking like it; a filler
 * clicked is cut again. Nothing is used up.
 */
public class ItemGapFiller extends ItemWithCreativeTabBase {

	/**
	 * How far from a rail a block may be to be cut along it, in blocks across and up or down
	 */
	private static final double REACH = 20;
	private static final double REACH_UP_DOWN = 12;

	public ItemGapFiller() {
		super(CreativeModeTabs.STATION_BUILDING_BLOCKS, properties -> properties.stacksTo(1));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Level world = context.getLevel();
		if (world.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		final Player player = context.getPlayer();
		final BlockPos pos = context.getClickedPos();
		final BlockState state = world.getBlockState(pos);
		final BlockState source;
		if (state.getBlock() instanceof BlockGapFiller) {
			final BlockEntity entity = world.getBlockEntity(pos);
			source = entity instanceof BlockPlatformEdge.TileEntityPlatformEdge ? ((BlockPlatformEdge.TileEntityPlatformEdge) entity).getSource() : null;
		} else {
			source = BlockGapFiller.canBeCutFrom(world, pos, state) ? state : null;
		}
		if (source == null) {
			message(player, "gap_filler_cannot_cut");
			return InteractionResult.FAIL;
		}
		final double centreX = pos.getX() + 0.5;
		final double centreZ = pos.getZ() + 0.5;
		final Rail rail = nearestRail(world, centreX, pos.getY(), centreZ);
		if (rail == null) {
			message(player, "gap_filler_no_rail");
			return InteractionResult.FAIL;
		}
		final double[] pointAndOut = BlockGapFiller.pointAndOut(rail, BlockGapFiller.nearestAlong(rail, centreX, centreZ, 0, rail.getLength()), centreX, centreZ);
		final Vec3 hit = context.getClickLocation();
		// The line goes through the place clicked: its distance from the rail, measured square to the rail
		final double distance = (hit.x - pointAndOut[0]) * pointAndOut[2] + (hit.z - pointAndOut[1]) * pointAndOut[3];
		final boolean keepInside = player == null || !player.isShiftKeyDown();
		final int packed = PlatformEdges.fillerCutOf(pos.getX(), pos.getZ(), pointAndOut[0], pointAndOut[1], pointAndOut[2], pointAndOut[3], distance, keepInside);
		if (packed == PlatformEdges.FULL) {
			message(player, "gap_filler_whole");
			return InteractionResult.FAIL;
		}
		if (packed == PlatformEdges.EMPTY) {
			message(player, "gap_filler_nothing_left");
			return InteractionResult.FAIL;
		}
		BlockGapFiller.put(world, pos, packed, source);
		return InteractionResult.SUCCESS;
	}

	/**
	 * @return the rail passing nearest a place across, within {@link #REACH} across and {@link #REACH_UP_DOWN} up or down
	 * there; null if none does
	 */
	private static Rail nearestRail(Level world, double x, int y, double z) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData == null) {
			return null;
		}
		Rail best = null;
		double bestDistance = REACH;
		for (final Map.Entry<BlockPos, Map<BlockPos, Rail>> entry : railwayData.getRails().entrySet()) {
			final BlockPos start = entry.getKey();
			for (final Map.Entry<BlockPos, Rail> to : entry.getValue().entrySet()) {
				final BlockPos end = to.getKey();
				final Rail rail = to.getValue();
				// Each rail is kept both ways round; one is enough
				if (start.asLong() > end.asLong()) {
					continue;
				}
				final double margin = rail.getLength() / 2 + REACH;
				if (x < Math.min(start.getX(), end.getX()) - margin || x > Math.max(start.getX(), end.getX()) + margin || z < Math.min(start.getZ(), end.getZ()) - margin || z > Math.max(start.getZ(), end.getZ()) + margin) {
					continue;
				}
				if (y < Math.min(start.getY(), end.getY()) - REACH_UP_DOWN || y > Math.max(start.getY(), end.getY()) + REACH_UP_DOWN) {
					continue;
				}
				final Vec3 point = rail.getPosition(BlockGapFiller.nearestAlong(rail, x, z, 0, rail.getLength()));
				final double distance = Math.hypot(point.x - x, point.z - z);
				if (distance < bestDistance && Math.abs(point.y - y) <= REACH_UP_DOWN) {
					bestDistance = distance;
					best = rail;
				}
			}
		}
		return best;
	}

	private static void message(Player player, String key) {
		if (player != null) {
			player.displayClientMessage(Text.translatable("gui.mtr." + key), true);
		}
	}

	@Override
	public void appendHoverText(ItemStack itemStack, Level level, List<Component> tooltip, TooltipFlag tooltipFlag) {
		tooltip.add(Text.translatable("tooltip.mtr.gap_filler").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
	}
}
