package mtr.item;

import mtr.CreativeModeTabs;
import mtr.block.BlockPlatformEdge;
import mtr.data.Platform;
import mtr.data.PlatformCurves;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts down a platform edge block cut along the nearest platform's track (0.88.0), looking like the platform block it is
 * put against (0.89.0, {@link BlockPlatformEdge#sourceFor}); used sneaking, lays the whole edge of
 * that side of the platform at that height, in the free places only, the platform's part of each block from where the cars'
 * sides need it to a block and a half beyond.
 */
public class ItemPlatformEdge extends ItemWithCreativeTabBase {

	/**
	 * How far beyond the edge the strip laid by sneaking reaches, in blocks
	 */
	private static final double STRIP = 1.5;

	public ItemPlatformEdge() {
		super(CreativeModeTabs.STATION_BUILDING_BLOCKS);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Level world = context.getLevel();
		final BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
		if (world.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		final Player player = context.getPlayer();
		final ItemStack stack = context.getItemInHand();
		final boolean creative = player != null && player.isCreative();
		if (player != null && player.isShiftKeyDown()) {
			final int placed = layEdge(world, pos, BlockPlatformEdge.sourceFor(world, pos, context.getClickedPos()), creative ? Integer.MAX_VALUE : stack.getCount());
			if (!creative) {
				stack.shrink(placed);
			}
			player.displayClientMessage(placed < 0 ? Text.translatable("gui.mtr.platform_edge_no_platform") : Text.translatable("gui.mtr.platform_edge_laid", placed), true);
			return InteractionResult.SUCCESS;
		}
		if (!world.getBlockState(pos).canBeReplaced()) {
			return InteractionResult.FAIL;
		}
		final BlockState state = BlockPlatformEdge.stateAt(world, pos, null);
		if (state == null) {
			if (player != null) {
				player.displayClientMessage(Text.translatable("gui.mtr.platform_edge_in_the_way"), true);
			}
			return InteractionResult.FAIL;
		}
		world.setBlockAndUpdate(pos, state);
		setSource(world, pos, BlockPlatformEdge.sourceFor(world, pos, context.getClickedPos()));
		if (!creative) {
			stack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * @return how many blocks were laid along the edge of the platform nearest the place, on the place's side and at its
	 * height, at most the given number; -1 when no platform is near
	 */
	private static int layEdge(Level world, BlockPos pos, BlockState source, int most) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		final Platform platform = railwayData == null ? null : BlockPlatformEdge.nearestPlatform(railwayData, pos);
		final Rail rail = platform == null ? null : PlatformCurves.railOf(platform, railwayData.getRails());
		if (rail == null) {
			return -1;
		}
		final int[] cars = PlatformCurves.carsAt(platform.id, railwayData.routes, railwayData.sidings, railwayData.dataCache.sidingIdToDepot);
		final PlatformCurves.Nearest start = PlatformCurves.nearest(rail, pos.getX() + 0.5, pos.getZ() + 0.5, cars[0]);
		final Set<BlockPos> places = new LinkedHashSet<>();
		final double length = rail.getLength();
		for (double along = 0; along <= length; along += 0.5) {
			final Vec3 point = rail.getPosition(along);
			final Vec3 ahead = rail.getPosition(Math.min(length, along + 0.25));
			final Vec3 behind = rail.getPosition(Math.max(0, along - 0.25));
			final double directionX = ahead.x - behind.x;
			final double directionZ = ahead.z - behind.z;
			final double directionLength = Math.hypot(directionX, directionZ);
			if (directionLength == 0) {
				continue;
			}
			// Square to the rail, on the side the place is on
			double sideX = directionZ / directionLength;
			double sideZ = -directionX / directionLength;
			if (sideX * start.sideX + sideZ * start.sideZ < 0) {
				sideX = -sideX;
				sideZ = -sideZ;
			}
			final double offset = PlatformEdges.edgeOffset(cars[1] / 2D, 0);
			for (double beyond = -0.5; beyond <= STRIP + PlatformEdges.WARN_EXTRA_GAP; beyond += 0.25) {
				final double x = point.x + sideX * (offset + beyond);
				final double z = point.z + sideZ * (offset + beyond);
				places.add(new BlockPos((int) Math.floor(x), pos.getY(), (int) Math.floor(z)));
			}
		}
		int placed = 0;
		for (final BlockPos place : places) {
			if (placed >= most) {
				break;
			}
			if (!world.getBlockState(place).canBeReplaced()) {
				continue;
			}
			final BlockState state = BlockPlatformEdge.stateAt(world, place, platform);
			if (state != null) {
				world.setBlockAndUpdate(place, state);
				setSource(world, place, source);
				placed++;
			}
		}
		return placed;
	}

	/**
	 * Gives an edge block just put down the look of the platform it was put against (0.89.0)
	 */
	private static void setSource(Level world, BlockPos pos, BlockState source) {
		final BlockEntity entity = world.getBlockEntity(pos);
		if (source != null && entity instanceof BlockPlatformEdge.TileEntityPlatformEdge) {
			((BlockPlatformEdge.TileEntityPlatformEdge) entity).setSource(source);
		}
	}

	@Override
	public void appendHoverText(ItemStack itemStack, Level level, List<Component> tooltip, TooltipFlag tooltipFlag) {
		tooltip.add(Text.translatable("tooltip.mtr.platform_edge").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
	}
}
