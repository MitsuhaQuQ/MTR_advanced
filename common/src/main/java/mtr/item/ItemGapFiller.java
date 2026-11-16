package mtr.item;

import mtr.CreativeModeTabs;
import mtr.block.BlockGapFiller;
import mtr.block.BlockPlatformEdge;
import mtr.block.IBlock;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.mappings.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import transitsim.geometry.PlatformEdges;
import transitsim.geometry.SlopedTops;

import java.util.List;
import java.util.Map;

/**
 * Cuts a block by hand along the nearest rail (0.93.0), as the creators cut the gaps they leave. The block becomes a
 * {@link BlockGapFiller}; nothing is used up.
 * <ul>
 * <li>On a side: the line runs beside the rail through the place clicked, and the side clicked is shaved off (0.94.0):
 * clicking the side facing the rail keeps the part away from it, the side facing away keeps the part towards it.</li>
 * <li>On the top (0.94.0): the block's top follows the rail's height over it; where the rail is over the block's top and
 * the place above is free, a filler sloped to the rail is put there.</li>
 * <li>Sneaking (0.94.0): picks the block clicked as the material fillers are made of; sneaking at nothing forgets it, and
 * fillers are made of the block cut again.</li>
 * </ul>
 */
public class ItemGapFiller extends ItemWithCreativeTabBase {

	/**
	 * How far from a rail a block may be to be cut along it, in blocks across and up or down
	 */
	private static final double REACH = 20;
	private static final double REACH_UP_DOWN = 12;
	private static final String KEY_MATERIAL = "material";

	public ItemGapFiller() {
		super(CreativeModeTabs.STATION_BUILDING_BLOCKS, properties -> properties.stacksTo(1));
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
		final ItemStack stack = player.getItemInHand(hand);
		if (!world.isClientSide && player.isShiftKeyDown()) {
			setMaterial(stack, null);
			message(player, "gap_filler_material_cleared");
		}
		return InteractionResultHolder.success(stack);
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
		final boolean filler = state.getBlock() instanceof BlockGapFiller;
		final BlockEntity entity = world.getBlockEntity(pos);
		final BlockState ownSource = filler ? entity instanceof BlockPlatformEdge.TileEntityPlatformEdge ? ((BlockPlatformEdge.TileEntityPlatformEdge) entity).getSource() : null : BlockGapFiller.canBeCutFrom(world, pos, state) ? state : null;

		if (player != null && player.isShiftKeyDown()) {
			if (ownSource == null) {
				message(player, "gap_filler_cannot_cut");
				return InteractionResult.FAIL;
			}
			setMaterial(context.getItemInHand(), ownSource);
			if (player != null) {
				player.displayClientMessage(Text.translatable("gui.mtr.gap_filler_material", ownSource.getBlock().getName()), true);
			}
			return InteractionResult.SUCCESS;
		}

		final BlockState material = getMaterial(context.getItemInHand());
		final double centreX = pos.getX() + 0.5;
		final double centreZ = pos.getZ() + 0.5;
		final Rail rail = nearestRail(world, centreX, pos.getY(), centreZ);
		if (rail == null) {
			message(player, "gap_filler_no_rail");
			return InteractionResult.FAIL;
		}
		final int ownCut = filler ? PlatformEdges.pack(IBlock.getStatePropertySafe(state, BlockPlatformEdge.ANGLE), IBlock.getStatePropertySafe(state, BlockPlatformEdge.CUT)) : PlatformEdges.FULL;
		final int ownTop = entity instanceof BlockGapFiller.TileEntityGapFiller ? ((BlockGapFiller.TileEntityGapFiller) entity).getTop() : SlopedTops.FLAT;
		final Direction face = context.getClickedFace();

		if (face == Direction.UP) {
			final double[] heights = BlockGapFiller.cornerHeights(rail, pos.getX(), pos.getZ(), 0, rail.getLength());
			final int top = SlopedTops.of(heights, pos.getY());
			if (top != SlopedTops.FLAT) {
				// The rail's height is in this block, or under it
				if (ownSource == null) {
					message(player, "gap_filler_cannot_cut");
					return InteractionResult.FAIL;
				}
				if (top == SlopedTops.NONE) {
					message(player, "gap_filler_nothing_left");
					return InteractionResult.FAIL;
				}
				BlockGapFiller.put(world, pos, ownCut, top, material == null ? ownSource : material);
				return InteractionResult.SUCCESS;
			}
			// The rail is over this block's top: fill the place above up to it
			final BlockPos above = pos.above();
			final int aboveTop = SlopedTops.of(heights, above.getY());
			final BlockState fillWith = material == null ? ownSource == null ? state : ownSource : material;
			if (!world.getBlockState(above).canBeReplaced() || aboveTop == SlopedTops.NONE) {
				message(player, "gap_filler_rail_not_here");
				return InteractionResult.FAIL;
			}
			if (aboveTop == SlopedTops.FLAT) {
				message(player, "gap_filler_rail_too_high");
				return InteractionResult.FAIL;
			}
			if (fillWith.isAir() || !fillWith.getFluidState().isEmpty()) {
				message(player, "gap_filler_cannot_cut");
				return InteractionResult.FAIL;
			}
			BlockGapFiller.put(world, above, PlatformEdges.FULL, aboveTop, fillWith);
			return InteractionResult.SUCCESS;
		}

		if (face == Direction.DOWN || ownSource == null) {
			message(player, "gap_filler_cannot_cut");
			return InteractionResult.FAIL;
		}
		final double[] pointAndOut = BlockGapFiller.pointAndOut(rail, BlockGapFiller.nearestAlong(rail, centreX, centreZ, 0, rail.getLength()), centreX, centreZ);
		final Vec3 hit = context.getClickLocation();
		// The line goes through the place clicked: its distance from the rail, measured square to the rail
		final double distance = (hit.x - pointAndOut[0]) * pointAndOut[2] + (hit.z - pointAndOut[1]) * pointAndOut[3];
		// The side clicked is shaved off: a side facing away from the rail leaves the part towards it
		final boolean keepInside = face.getStepX() * pointAndOut[2] + face.getStepZ() * pointAndOut[3] > 0;
		final int packed = PlatformEdges.fillerCutOf(pos.getX(), pos.getZ(), pointAndOut[0], pointAndOut[1], pointAndOut[2], pointAndOut[3], distance, keepInside);
		if (packed == PlatformEdges.FULL) {
			message(player, "gap_filler_whole");
			return InteractionResult.FAIL;
		}
		if (packed == PlatformEdges.EMPTY) {
			message(player, "gap_filler_nothing_left");
			return InteractionResult.FAIL;
		}
		BlockGapFiller.put(world, pos, packed, ownTop, material == null ? ownSource : material);
		return InteractionResult.SUCCESS;
	}

	/**
	 * @return the material picked (0.94.0); null when fillers are made of the block cut
	 */
	private static BlockState getMaterial(ItemStack stack) {
		final CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains(KEY_MATERIAL)) {
			return null;
		}
		final BlockState material = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound(KEY_MATERIAL));
		return material.isAir() ? null : material;
	}

	private static void setMaterial(ItemStack stack, BlockState material) {
		if (material == null) {
			if (stack.getTag() != null) {
				stack.getTag().remove(KEY_MATERIAL);
			}
		} else {
			stack.getOrCreateTag().put(KEY_MATERIAL, NbtUtils.writeBlockState(material));
		}
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
		final BlockState material = getMaterial(itemStack);
		if (material != null) {
			tooltip.add(Text.translatable("tooltip.mtr.gap_filler_material", material.getBlock().getName()).setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
		}
	}
}
