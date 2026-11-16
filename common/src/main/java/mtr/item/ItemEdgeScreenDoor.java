package mtr.item;

import mtr.CreativeModeTabs;
import mtr.block.BlockEdgeScreenDoor;
import mtr.block.BlockPlatformEdge;
import mtr.block.IBlock;
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

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Puts down screen doors cut along a curved or diagonal platform's edge (0.90.0, {@link BlockEdgeScreenDoor}): right-click
 * puts one glass block where the edge line crosses the place, sneaking lays the whole side of the platform at that height,
 * glass with a two-leaf door every quarter car. On a door block already there, right-click turns it from glass to a leaf
 * sliding one way, the other way and back to glass; sneaking changes the style of the whole run of door blocks it is in.
 */
public class ItemEdgeScreenDoor extends ItemWithCreativeTabBase {

	/**
	 * The most blocks a change of style goes through
	 */
	private static final int MOST_RESTYLED = 2048;
	/**
	 * How wide a door laid by sneaking opens, in blocks along the edge, its two leaves together
	 */
	private static final double DOOR_WIDTH = 2;

	public ItemEdgeScreenDoor() {
		super(CreativeModeTabs.STATION_BUILDING_BLOCKS);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Level world = context.getLevel();
		if (world.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		final Player player = context.getPlayer();
		final boolean sneaking = player != null && player.isShiftKeyDown();
		final BlockPos clicked = context.getClickedPos();
		final BlockEntity clickedEntity = world.getBlockEntity(clicked);

		// On a door block already there: change it
		if (clickedEntity instanceof BlockEdgeScreenDoor.TileEntityEdgeScreenDoor) {
			final BlockEdgeScreenDoor.TileEntityEdgeScreenDoor door = (BlockEdgeScreenDoor.TileEntityEdgeScreenDoor) clickedEntity;
			if (sneaking) {
				final int style = door.getStyle() + 1;
				final int changed = restyle(world, clicked, style);
				message(player, Text.translatable("gui.mtr.edge_screen_door_style", style % BlockEdgeScreenDoor.STYLES + 1, changed));
			} else {
				door.setLook(door.getStyle(), door.getPart() + 1);
				message(player, Text.translatable(door.isDoor() ? "gui.mtr.edge_screen_door_part_door" : "gui.mtr.edge_screen_door_part_glass"));
			}
			return InteractionResult.SUCCESS;
		}

		final BlockPos pos = clicked.relative(context.getClickedFace());
		final ItemStack stack = context.getItemInHand();
		final boolean creative = player != null && player.isCreative();
		if (sneaking) {
			final int placed = layDoors(world, pos, creative ? Integer.MAX_VALUE : stack.getCount());
			if (!creative && placed > 0) {
				stack.shrink(placed);
			}
			message(player, placed < 0 ? Text.translatable("gui.mtr.platform_edge_no_platform") : Text.translatable("gui.mtr.edge_screen_door_laid", placed));
			return InteractionResult.SUCCESS;
		}
		if (!world.getBlockState(pos).canBeReplaced()) {
			return InteractionResult.FAIL;
		}
		final BlockState state = BlockEdgeScreenDoor.stateAt(world, pos, null);
		if (state == null) {
			message(player, Text.translatable("gui.mtr.edge_screen_door_no_edge"));
			return InteractionResult.FAIL;
		}
		world.setBlockAndUpdate(pos, state);
		if (!creative) {
			stack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * @return how many blocks were laid where the edge line of the platform nearest the place crosses, on the place's side
	 * and at its height, at most the given number; -1 when no platform is near
	 */
	private static int layDoors(Level world, BlockPos pos, int most) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		final Platform platform = railwayData == null ? null : BlockPlatformEdge.nearestPlatform(railwayData, pos);
		final Rail rail = platform == null ? null : PlatformCurves.railOf(platform, railwayData.getRails());
		if (rail == null) {
			return -1;
		}
		final int[] cars = PlatformCurves.carsAt(platform.id, railwayData.routes, railwayData.sidings, railwayData.dataCache.sidingIdToDepot);
		final PlatformCurves.Nearest start = PlatformCurves.nearest(rail, pos.getX() + 0.5, pos.getZ() + 0.5, cars[0]);
		final double offset = PlatformEdges.edgeOffset(cars[1] / 2D, 0);

		// The blocks along the edge line in the order the rail goes, each with the rail's direction there
		final Map<BlockPos, double[]> places = new LinkedHashMap<>();
		final double length = rail.getLength();
		for (double along = 0; along <= length; along += 0.25) {
			final Vec3 point = rail.getPosition(along);
			final Vec3 ahead = rail.getPosition(Math.min(length, along + 0.25));
			final Vec3 behind = rail.getPosition(Math.max(0, along - 0.25));
			final double directionX = ahead.x - behind.x;
			final double directionZ = ahead.z - behind.z;
			final double directionLength = Math.hypot(directionX, directionZ);
			if (directionLength == 0) {
				continue;
			}
			double sideX = directionZ / directionLength;
			double sideZ = -directionX / directionLength;
			if (sideX * start.sideX + sideZ * start.sideZ < 0) {
				sideX = -sideX;
				sideZ = -sideZ;
			}
			// On the inside of a curve the edge stands out by up to the sagitta
			for (double beyond = -0.3; beyond <= PlatformEdges.WARN_EXTRA_GAP + 0.3; beyond += 0.1) {
				final double x = point.x + sideX * (offset + beyond);
				final double z = point.z + sideZ * (offset + beyond);
				places.putIfAbsent(new BlockPos((int) Math.floor(x), pos.getY(), (int) Math.floor(z)), new double[]{directionX / directionLength, directionZ / directionLength});
			}
		}

		// A door every quarter car, its two leaves parting from its middle
		final double period = Math.max(DOOR_WIDTH + 2, cars[0] / 4D);
		double laidLength = 0;
		int placed = 0;
		for (final Map.Entry<BlockPos, double[]> entry : places.entrySet()) {
			if (placed >= most) {
				break;
			}
			final BlockPos place = entry.getKey();
			if (!world.getBlockState(place).canBeReplaced()) {
				continue;
			}
			final BlockState state = BlockEdgeScreenDoor.stateAt(world, place, platform);
			if (state == null) {
				continue;
			}
			final int angle = IBlock.getStatePropertySafe(state, BlockPlatformEdge.ANGLE);
			final double[] segment = PlatformEdges.wallSegment(angle, IBlock.getStatePropertySafe(state, BlockPlatformEdge.CUT));
			if (segment == null) {
				continue;
			}
			final double segmentLength = Math.hypot(segment[2] - segment[0], segment[3] - segment[1]);
			final double phase = (laidLength + segmentLength / 2) % period;
			laidLength += segmentLength;
			final boolean railRunsAlong = entry.getValue()[0] * PlatformEdges.alongX(angle) + entry.getValue()[1] * PlatformEdges.alongZ(angle) > 0;
			final int part;
			if (phase >= period / 2 - DOOR_WIDTH / 2 && phase < period / 2) {
				// The first leaf slides back the way the rail came
				part = railRunsAlong ? BlockEdgeScreenDoor.PART_DOOR_BACK : BlockEdgeScreenDoor.PART_DOOR_AHEAD;
			} else if (phase >= period / 2 && phase < period / 2 + DOOR_WIDTH / 2) {
				part = railRunsAlong ? BlockEdgeScreenDoor.PART_DOOR_AHEAD : BlockEdgeScreenDoor.PART_DOOR_BACK;
			} else {
				part = BlockEdgeScreenDoor.PART_GLASS;
			}
			world.setBlockAndUpdate(place, state);
			final BlockEntity entity = world.getBlockEntity(place);
			if (entity instanceof BlockEdgeScreenDoor.TileEntityEdgeScreenDoor) {
				((BlockEdgeScreenDoor.TileEntityEdgeScreenDoor) entity).setLook(0, part);
			}
			placed++;
		}
		return placed;
	}

	/**
	 * Gives every door block joined to the clicked one, side by side or at a corner at the same height, the style
	 *
	 * @return how many blocks were changed
	 */
	private static int restyle(Level world, BlockPos from, int style) {
		final Set<BlockPos> seen = new HashSet<>();
		final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(from);
		seen.add(from);
		int changed = 0;
		while (!queue.isEmpty() && changed < MOST_RESTYLED) {
			final BlockPos pos = queue.poll();
			final BlockEntity entity = world.getBlockEntity(pos);
			if (!(entity instanceof BlockEdgeScreenDoor.TileEntityEdgeScreenDoor)) {
				continue;
			}
			final BlockEdgeScreenDoor.TileEntityEdgeScreenDoor door = (BlockEdgeScreenDoor.TileEntityEdgeScreenDoor) entity;
			door.setLook(style, door.getPart());
			changed++;
			for (int x = -1; x <= 1; x++) {
				for (int z = -1; z <= 1; z++) {
					final BlockPos next = pos.offset(x, 0, z);
					if (seen.add(next)) {
						queue.add(next);
					}
				}
			}
		}
		return changed;
	}

	private static void message(Player player, Component component) {
		if (player != null) {
			player.displayClientMessage(component, true);
		}
	}

	@Override
	public void appendHoverText(ItemStack itemStack, Level level, List<Component> tooltip, TooltipFlag tooltipFlag) {
		tooltip.add(Text.translatable("tooltip.mtr.edge_screen_door").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
	}
}
