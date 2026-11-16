package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class VehicleRidingServer {

	private static final float INNER_PADDING = 0.5F;
	private static final int BOX_PADDING = 3;

	public static void mountRider(Level world, Set<UUID> ridingEntities, long id, long routeId, double carX, double carY, double carZ, double length, double width, float carYaw, float carPitch, boolean doorOpen, boolean canMount, int percentageOffset, ResourceLocation packetId, Function<Player, Boolean> canRide, Consumer<Player> ridingCallback) {
		mountRider(world, ridingEntities, id, routeId, carX, carY, carZ, length, width, carYaw, carPitch, doorOpen, canMount, percentageOffset, packetId, canRide, ridingCallback, 0, 0);
	}

	/**
	 * @param bridgeLeft  how much further than the car's side a player over a gap is taken on board on its left (the side
	 *                    the car's x goes negative), where its doors are open: the boarding bridge over the gap beside a
	 *                    curved or diagonal platform (0.88.0); 0 for none
	 * @param bridgeRight the same on its right
	 */
	public static void mountRider(Level world, Set<UUID> ridingEntities, long id, long routeId, double carX, double carY, double carZ, double length, double width, float carYaw, float carPitch, boolean doorOpen, boolean canMount, int percentageOffset, ResourceLocation packetId, Function<Player, Boolean> canRide, Consumer<Player> ridingCallback, double bridgeLeft, double bridgeRight) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData == null) {
			return;
		}

		final double halfLength = length / 2;
		final double halfWidth = width / 2;

		if (canMount) {
			final double margin = halfLength + BOX_PADDING;
			world.getEntitiesOfClass(Player.class, new AABB(carX + margin, carY + margin, carZ + margin, carX - margin, carY - margin, carZ - margin), player -> !player.isSpectator() && !ridingEntities.contains(player.getUUID()) && railwayData.railwayDataCoolDownModule.canRide(player) && canRide.apply(player)).forEach(player -> {
				final Vec3 positionRotated = player.position().subtract(carX, carY, carZ).yRot(-carYaw).xRot(-carPitch);
				final double bridge = positionRotated.x < 0 ? bridgeLeft : bridgeRight;
				final boolean onBoard = Math.abs(positionRotated.x) < halfWidth + INNER_PADDING;
				// Over the gap by an open door, not standing on anything: on the bridge, so taken on board rather than falling
				final boolean onBridge = !onBoard && bridge > 0 && Math.abs(positionRotated.x) < halfWidth + INNER_PADDING + bridge && isOverGap(world, player.position());
				if ((onBoard || onBridge) && Math.abs(positionRotated.y) < 2.5 && Math.abs(positionRotated.z) <= halfLength && !railwayData.railwayDataCoolDownModule.shouldDismount(player)) {
					ridingEntities.add(player.getUUID());
					final float percentageX = (float) (positionRotated.x / width + 0.5);
					final float percentageZ = (float) (length == 0 ? 0 : positionRotated.z / length + 0.5) + percentageOffset;
					final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
					packet.writeLong(id);
					packet.writeFloat(percentageX);
					packet.writeFloat(percentageZ);
					packet.writeUUID(player.getUUID());
					world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, packetId, packet));
				}
			});
		}

		final Set<UUID> ridersToRemove = new HashSet<>();
		ridingEntities.forEach(uuid -> {
			final Player player = world.getPlayerByUUID(uuid);

			if (player != null) {
				final boolean remove;

				if (player.isSpectator() || railwayData.railwayDataCoolDownModule.shouldDismount(player)) {
					remove = true;
				} else if (doorOpen) {
					final Vec3 positionRotated = player.position().subtract(carX, carY, carZ).yRot(-carYaw).xRot(-carPitch);
					remove = Math.abs(positionRotated.z) <= halfLength && (Math.abs(positionRotated.x) > halfWidth + INNER_PADDING || Math.abs(positionRotated.y) > 10);
					// Getting off over a gap by an open door: put down on the platform past it rather than falling in
					final double bridge = positionRotated.x < 0 ? bridgeLeft : bridgeRight;
					if (remove && bridge > 0 && Math.abs(positionRotated.y) <= 10 && isOverGap(world, player.position())) {
						stepOverGap(world, player, positionRotated, carX, carY, carZ, carYaw, carPitch, halfWidth + INNER_PADDING + bridge + INNER_PADDING);
					}
				} else {
					remove = false;
				}

				if (remove) {
					ridersToRemove.add(uuid);
				}

				railwayData.railwayDataCoolDownModule.updatePlayerRiding(player, routeId);
				ridingCallback.accept(player);
			}
		});

		if (!ridersToRemove.isEmpty()) {
			ridersToRemove.forEach(ridingEntities::remove);
		}
	}

	/**
	 * @return whether nothing is under the feet at this position, as over the gap between a car and the platform
	 */
	private static boolean isOverGap(Level world, Vec3 position) {
		final BlockPos below = RailwayData.newBlockPos(position.x, position.y - 0.05, position.z);
		final VoxelShape shape = world.getBlockState(below).getCollisionShape(world, below);
		if (shape.isEmpty()) {
			return true;
		}
		final double localX = position.x - below.getX();
		final double localZ = position.z - below.getZ();
		// The shape of a cut platform edge leaves part of the block open
		return shape.toAabbs().stream().noneMatch(box -> box.minX <= localX && localX <= box.maxX && box.minZ <= localZ && localZ <= box.maxZ);
	}

	/**
	 * Moves a player getting off away from the car's side, along the car's crosswise direction, to the first place with
	 * something to stand on, up to the given distance from the car's centre line; left where it is when there is none.
	 */
	private static void stepOverGap(Level world, Player player, Vec3 positionRotated, double carX, double carY, double carZ, float carYaw, float carPitch, double reach) {
		final double sign = positionRotated.x < 0 ? -1 : 1;
		for (double x = Math.abs(positionRotated.x); x <= reach; x += 0.25) {
			final Vec3 position = new Vec3(sign * x, positionRotated.y, positionRotated.z).xRot(carPitch).yRot(carYaw).add(carX, carY, carZ);
			for (final double rise : new double[]{0, 0.5, 1}) {
				final Vec3 candidate = position.add(0, rise, 0);
				if (!isOverGap(world, candidate) && world.noCollision(player, player.getDimensions(player.getPose()).makeBoundingBox(candidate))) {
					player.teleportTo(candidate.x, candidate.y, candidate.z);
					return;
				}
			}
		}
	}
}
