package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.Items;
import mtr.block.BlockNode;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.mappings.Text;
import mtr.path.PathData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

/**
 * While holding a brush, highlights the rail under the crosshair and shows what occupies it.
 */
public final class RenderRailInspect {

	private static float lastTick;
	private static Rail aimedRail;
	private static UUID aimedRailProduct;

	private static final int MAX_DISTANCE = 64;
	private static final double MAX_AIM_DISTANCE = 1.5;
	private static final int MIN_SAMPLES = 2;
	private static final int MAX_SAMPLES = 64;
	private static final double BLOCKS_PER_SAMPLE = 2;
	private static final double LINE_Y_OFFSET = 0.5;

	private RenderRailInspect() {
	}

	public static void render(PoseStack matrices, MultiBufferSource vertexConsumers, Player player, float gameTick) {
		if (!player.isHolding(Items.BRUSH.get())) {
			aimedRail = null;
			return;
		}

		if ((int) gameTick != (int) lastTick) {
			lastTick = gameTick;
			findAimedRail(player);
			if (aimedRail != null && !isLookingAtNode(player)) {
				player.displayClientMessage(getInfoText(), true);
			}
		}

		if (aimedRail != null) {
			final boolean occupied = isOccupied();
			final int samples = getSamples(aimedRail);
			final double length = aimedRail.getLength();
			Vec3 lastPosition = aimedRail.getPosition(0);
			for (int i = 1; i <= samples; i++) {
				final Vec3 position = aimedRail.getPosition(length * i / samples);
				IDrawing.drawLineWorld(matrices, vertexConsumers, lastPosition.x, lastPosition.y + LINE_Y_OFFSET, lastPosition.z, position.x, position.y + LINE_Y_OFFSET, position.z, 0xFF, occupied ? 0 : 0xFF, 0);
				lastPosition = position;
			}
		}
	}

	private static void findAimedRail(Player player) {
		final Vec3 eye = player.getEyePosition(1);
		final Vec3 look = player.getViewVector(1);
		aimedRail = null;
		final double[] closestDistance = {MAX_AIM_DISTANCE};
		ClientData.RAILS.forEach((startPos, railMap) -> railMap.forEach((endPos, rail) -> {
			if (RailwayData.isBetween(player.getX(), startPos.getX(), endPos.getX(), MAX_DISTANCE) && RailwayData.isBetween(player.getZ(), startPos.getZ(), endPos.getZ(), MAX_DISTANCE)) {
				final int samples = getSamples(rail);
				final double length = rail.getLength();
				for (int i = 0; i <= samples; i++) {
					final double distance = distanceToRay(rail.getPosition(length * i / samples).add(0, LINE_Y_OFFSET, 0), eye, look);
					if (distance < closestDistance[0]) {
						closestDistance[0] = distance;
						aimedRail = rail;
						aimedRailProduct = PathData.getRailProduct(startPos, endPos);
					}
				}
			}
		}));
	}

	private static double distanceToRay(Vec3 position, Vec3 eye, Vec3 look) {
		final Vec3 offset = position.subtract(eye);
		final double along = offset.dot(look);
		return along < 0 || along > MAX_DISTANCE ? Double.MAX_VALUE : offset.subtract(look.scale(along)).length();
	}

	private static int getSamples(Rail rail) {
		return Math.max(MIN_SAMPLES, Math.min(MAX_SAMPLES, (int) (rail.getLength() / BLOCKS_PER_SAMPLE)));
	}

	private static boolean isOccupied() {
		final java.util.Set<Integer> pieces = ClientData.OCCUPIED_SECTIONS == null ? null : ClientData.OCCUPIED_SECTIONS.get(aimedRailProduct);
		return pieces != null && !pieces.isEmpty() || Boolean.TRUE.equals(ClientData.OCCUPIED_RAILS.get(aimedRailProduct)) || ClientData.OCCUPIED_SECTIONS == null && ClientData.SIGNAL_BLOCKS.isOccupied(aimedRailProduct);
	}

	private static Component getInfoText() {
		final String state = Text.translatable(isOccupied() ? "gui.mtr.rail_occupied" : "gui.mtr.rail_free").getString();
		final Component info = Text.translatable("gui.mtr.rail_info", RailwayData.round(aimedRail.getLength(), 1), aimedRail.railType.speedLimit, state, ClientData.SIGNAL_BLOCKS.getSignalBlocksAtTrack(aimedRailProduct).size());
		// A long rail is cut into block sections, each taken on its own
		final int pieces = transitsim.network.BlockSections.getPieceCount(aimedRail.getLength(), mtr.data.TrainServer.cutSpeed(aimedRail.railType));
		if (pieces <= 1) {
			return info;
		}
		final java.util.Set<Integer> taken = ClientData.OCCUPIED_SECTIONS == null ? java.util.Collections.emptySet() : ClientData.OCCUPIED_SECTIONS.getOrDefault(aimedRailProduct, java.util.Collections.emptySet());
		final String takenText = taken.isEmpty() ? Text.translatable("gui.mtr.rail_sections_none").getString() : taken.stream().sorted().map(piece -> String.valueOf(piece + 1)).collect(java.util.stream.Collectors.joining(", "));
		return Text.translatable("gui.mtr.rail_sections", info.getString(), pieces, RailwayData.round(aimedRail.getLength() / pieces, 1), takenText);
	}

	private static boolean isLookingAtNode(Player player) {
		final Level world = Minecraft.getInstance().level;
		final HitResult hitResult = Minecraft.getInstance().hitResult;
		if (world != null && hitResult instanceof BlockHitResult) {
			final BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
			return world.getBlockState(pos).getBlock() instanceof BlockNode;
		}
		return false;
	}
}
