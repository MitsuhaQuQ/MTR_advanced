package mtr.block;

import mtr.BlockEntityTypes;
import mtr.audio.AnnouncementClips;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Announces to the riders of a train going over it, as in MTR: its message, and its sounds one after another with the
 * gaps after them (0.72.0). Since 0.72.0 it does nothing else: the triggers at a station (before arrival, after
 * stopping, before leaving) and announcing to a platform this fork had added are gone (user, 2026-11-12); their settings
 * in older saves are kept as they were but not used, so such an announcer announces to riders going over it again.
 */
public class BlockTrainAnnouncer extends BlockTrainSensorBase {

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityTrainAnnouncer(pos, state);
	}

	public static class TileEntityTrainAnnouncer extends TileEntityTrainSensorBase {

		private String message = "";
		/**
		 * The first sound: a sound of the game, a file added to the server or a web address ({@link AnnouncementSound}).
		 */
		private String soundString = "";
		/**
		 * The sounds played one after another with the gaps after them (0.72.0); the first is {@link #soundString}
		 */
		private List<AnnouncementClips.Clip> clips = AnnouncementClips.single("");
		/** The station trigger settings of 0.6x saves, kept to be written back as they were, not used */
		private final CompoundTag retiredSettings = new CompoundTag();
		private final Map<Player, Long> lastAnnouncedMillis = new HashMap<>();
		private static final int ANNOUNCE_COOL_DOWN_MILLIS = 20000;
		private static final String KEY_MESSAGE = "message";
		private static final String KEY_SOUND_ID = "sound_id";
		// The same key as MTR 1.20.1-3.5.0, so its announcers keep their web addresses
		private static final String KEY_SOUND_STRING = "sound_string";
		private static final String KEY_SOUND_CLIPS = "sound_clips";
		private static final String[] RETIRED_KEYS = {"station_trigger", "station_seconds", "station_to_riders"};

		public TileEntityTrainAnnouncer(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.TRAIN_ANNOUNCER_TILE_ENTITY.get(), pos, state);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			message = compoundTag.getString(KEY_MESSAGE);
			soundString = AnnouncementSound.clean(compoundTag.contains(KEY_SOUND_STRING) ? compoundTag.getString(KEY_SOUND_STRING) : compoundTag.getString(KEY_SOUND_ID));
			// Before 0.72.0 the one sound; a version without clips keeps playing the first
			setClips(compoundTag.contains(KEY_SOUND_CLIPS) ? AnnouncementClips.parse(compoundTag.getString(KEY_SOUND_CLIPS)) : AnnouncementClips.single(soundString));
			for (final String key : RETIRED_KEYS) {
				if (compoundTag.contains(key)) {
					retiredSettings.put(key, compoundTag.get(key).copy());
				}
			}
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
			compoundTag.putString(KEY_SOUND_CLIPS, AnnouncementClips.toText(clips));
			for (final String key : retiredSettings.getAllKeys()) {
				compoundTag.put(key, retiredSettings.get(key).copy());
			}
			super.writeCompoundTag(compoundTag);
		}

		/**
		 * @param strings the message, the first sound, and (0.72.0) the sounds with their gaps
		 *                ({@link AnnouncementClips#toText}); the field's sound is their first
		 */
		@Override
		public void setData(Set<Long> filterRouteIds, boolean stoppedOnly, boolean movingOnly, int number, String... strings) {
			if (strings.length >= 2) {
				message = strings[0];
				setClips(AnnouncementClips.single(strings[1]));
			}
			if (strings.length >= 3) {
				setClips(AnnouncementClips.parse(strings[2]));
			}
			setData(filterRouteIds, stoppedOnly, movingOnly);
		}

		public String getMessage() {
			return message;
		}

		public String getSoundIdString() {
			return soundString;
		}

		public List<AnnouncementClips.Clip> getClips() {
			return clips;
		}

		private void setClips(List<AnnouncementClips.Clip> newClips) {
			clips = newClips;
			soundString = newClips.isEmpty() ? "" : newClips.get(0).sound;
		}

		/**
		 * A rider of a train going over it, at most once in {@link #ANNOUNCE_COOL_DOWN_MILLIS} each.
		 */
		public void announce(Player player) {
			final long currentMillis = System.currentTimeMillis();
			if (player instanceof ServerPlayer && (!lastAnnouncedMillis.containsKey(player) || currentMillis - lastAnnouncedMillis.get(player) >= ANNOUNCE_COOL_DOWN_MILLIS)) {
				lastAnnouncedMillis.put(player, currentMillis);
				PacketTrainDataGuiServer.announceS2C((ServerPlayer) player, message, clips);
			}
		}
	}
}
