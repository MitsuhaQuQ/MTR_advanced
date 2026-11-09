package mtr.block;

import mtr.BlockEntityTypes;
import mtr.audio.AnnouncementSound;
import mtr.data.RailwayData;
import mtr.data.StationCall;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.TickableMapper;
import mtr.packet.PacketTrainDataGuiServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class BlockTrainAnnouncer extends BlockTrainSensorBase {

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityTrainAnnouncer(pos, state);
	}

	@Override
	public <T extends BlockEntityMapper> void tick(Level world, BlockPos pos, T blockEntity) {
		if (blockEntity instanceof TileEntityTrainAnnouncer) {
			((TileEntityTrainAnnouncer) blockEntity).tick();
		}
	}

	@Override
	public BlockEntityType<? extends BlockEntityMapper> getType() {
		return BlockEntityTypes.TRAIN_ANNOUNCER_TILE_ENTITY.get();
	}

	public static class TileEntityTrainAnnouncer extends TileEntityTrainSensorBase implements TickableMapper {

		private String message = "";
		/**
		 * A sound of the game, a file added to the server or a web address ({@link AnnouncementSound}).
		 */
		private String soundString = "";
		private StationCall.Trigger trigger = StationCall.Trigger.LEGACY;
		private int seconds;
		private boolean toRiders;
		private final Map<Player, Long> lastAnnouncedMillis = new HashMap<>();
		private static final int AUDIBLE_RADIUS = 24;
		private static final int ANNOUNCE_COOL_DOWN_MILLIS = 20000;
		private static final String KEY_MESSAGE = "message";
		private static final String KEY_SOUND_ID = "sound_id";
		// The same key as MTR 1.20.1-3.5.0, so its announcers keep their web addresses
		private static final String KEY_SOUND_STRING = "sound_string";
		private static final String KEY_TRIGGER = "station_trigger";
		private static final String KEY_SECONDS = "station_seconds";
		private static final String KEY_TO_RIDERS = "station_to_riders";

		public TileEntityTrainAnnouncer(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.TRAIN_ANNOUNCER_TILE_ENTITY.get(), pos, state);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			message = compoundTag.getString(KEY_MESSAGE);
			trigger = StationCall.Trigger.read(compoundTag.getString(KEY_TRIGGER));
			seconds = Math.max(0, compoundTag.getInt(KEY_SECONDS));
			toRiders = compoundTag.getBoolean(KEY_TO_RIDERS);
			soundString = AnnouncementSound.clean(compoundTag.contains(KEY_SOUND_STRING) ? compoundTag.getString(KEY_SOUND_STRING) : compoundTag.getString(KEY_SOUND_ID));
			super.readCompoundTag(compoundTag);
		}

		/**
		 * The game's sounds are also kept under the old key, so a version without the new one still plays them.
		 */
		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			compoundTag.putString(KEY_MESSAGE, message);
			compoundTag.putString(KEY_TRIGGER, trigger.name());
			compoundTag.putInt(KEY_SECONDS, seconds);
			compoundTag.putBoolean(KEY_TO_RIDERS, toRiders);
			final ResourceLocation soundId = AnnouncementSound.getSoundId(soundString);
			compoundTag.putString(KEY_SOUND_ID, soundId == null ? "" : soundId.toString());
			compoundTag.putString(KEY_SOUND_STRING, soundString);
			super.writeCompoundTag(compoundTag);
		}

		@Override
		public void setData(Set<Long> filterRouteIds, boolean stoppedOnly, boolean movingOnly, int number, String... strings) {
			if (strings.length >= 2) {
				message = strings[0];
				soundString = AnnouncementSound.clean(strings[1]);
			}
			if (strings.length >= 5) {
				trigger = StationCall.Trigger.read(strings[3]);
				seconds = Math.max(0, number);
				toRiders = Boolean.parseBoolean(strings[4]);
			}
			setData(filterRouteIds, stoppedOnly, movingOnly);
		}

		public String getMessage() {
			return message;
		}

		public String getSoundIdString() {
			return soundString;
		}

		public StationCall.Trigger getTrigger() {
			return trigger;
		}

		public int getSeconds() {
			return seconds;
		}

		public boolean getToRiders() {
			return toRiders;
		}

		@Override
		public void tick() {
			if (level == null || level.isClientSide || trigger == StationCall.Trigger.LEGACY) {
				return;
			}
			final RailwayData railwayData = RailwayData.getInstance(level);
			if (railwayData == null) {
				return;
			}
			final long now = System.currentTimeMillis();
			for (final StationCall call : railwayData.getStationCalls(worldPosition)) {
				if (matchesFilter(call.getRouteId(), -1) && call.trigger(worldPosition, trigger, seconds, now)) {
					if (toRiders) {
						call.forEachRider(this::sendAnnouncement);
					} else {
						level.players().forEach(player -> {
							if (!call.isRider(player) && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= AUDIBLE_RADIUS * AUDIBLE_RADIUS) {
								sendAnnouncement(player);
							}
						});
					}
				}
			}
		}

		public void announce(Player player) {
			if (trigger != StationCall.Trigger.LEGACY) {
				return;
			}
			final long currentMillis = System.currentTimeMillis();
			if (player != null && (!lastAnnouncedMillis.containsKey(player) || currentMillis - lastAnnouncedMillis.get(player) >= ANNOUNCE_COOL_DOWN_MILLIS)) {
				lastAnnouncedMillis.put(player, currentMillis);
				sendAnnouncement(player);
			}
		}

		private void sendAnnouncement(Player player) {
			if (player instanceof ServerPlayer) {
				PacketTrainDataGuiServer.announceS2C((ServerPlayer) player, message, soundString);
			}
		}
	}
}
