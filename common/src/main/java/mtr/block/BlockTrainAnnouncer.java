package mtr.block;

import mtr.BlockEntityTypes;
import mtr.audio.AnnouncementSound;
import mtr.mappings.BlockEntityMapper;
import mtr.packet.PacketTrainDataGuiServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class BlockTrainAnnouncer extends BlockTrainSensorBase {

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityTrainAnnouncer(pos, state);
	}

	public static class TileEntityTrainAnnouncer extends TileEntityTrainSensorBase {

		private String message = "";
		/**
		 * A sound of the game, a file added to the server or a web address ({@link AnnouncementSound}).
		 */
		private String soundString = "";
		private final Map<Player, Long> lastAnnouncedMillis = new HashMap<>();
		private static final int ANNOUNCE_COOL_DOWN_MILLIS = 20000;
		private static final String KEY_MESSAGE = "message";
		private static final String KEY_SOUND_ID = "sound_id";
		// The same key as MTR 1.20.1-3.5.0, so its announcers keep their web addresses
		private static final String KEY_SOUND_STRING = "sound_string";

		public TileEntityTrainAnnouncer(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.TRAIN_ANNOUNCER_TILE_ENTITY.get(), pos, state);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			message = compoundTag.getString(KEY_MESSAGE);
			soundString = AnnouncementSound.clean(compoundTag.contains(KEY_SOUND_STRING) ? compoundTag.getString(KEY_SOUND_STRING) : compoundTag.getString(KEY_SOUND_ID));
			super.readCompoundTag(compoundTag);
		}

		/**
		 * The game's sounds are also kept under the old key, so a version without the new one still plays them.
		 */
		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			compoundTag.putString(KEY_MESSAGE, message);
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
			setData(filterRouteIds, stoppedOnly, movingOnly);
		}

		public String getMessage() {
			return message;
		}

		public String getSoundIdString() {
			return soundString;
		}

		public void announce(Player player) {
			final long currentMillis = System.currentTimeMillis();
			if (player != null && (!lastAnnouncedMillis.containsKey(player) || currentMillis - lastAnnouncedMillis.get(player) >= ANNOUNCE_COOL_DOWN_MILLIS)) {
				lastAnnouncedMillis.put(player, System.currentTimeMillis());
				PacketTrainDataGuiServer.announceS2C((ServerPlayer) player, message, soundString);
			}
		}
	}
}
