package mtr.client;

import mtr.Blocks;
import mtr.audio.AnnouncementSound;
import mtr.audio.AudioFiles;
import mtr.audio.SpeakerSettings;
import mtr.mappings.RegistryUtilities;
import mtr.mappings.TickableSoundInstanceMapper;
import mtr.packet.AudioSpeakerPackets;
import mtr.screen.AudioSpeakerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.util.HashMap;
import java.util.Map;

/** Owns the game's sound instances for the speakers addressed to this player. */
public final class SpeakerPlayback {
	private static final int LOOP_LEASE_TICKS = 80;
	private static final int DOWNLOAD_TIMEOUT_TICKS = 600;
	private static final int MAX_SPEAKERS = 32;
	private static final Map<BlockPos, Playback> PLAYING = new HashMap<>();
	private static ClientLevel world;

	private SpeakerPlayback() {
	}

	public static void open(FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		final SpeakerSettings settings = SpeakerSettings.read(packet);
		final Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.level != null) client.setScreen(new AudioSpeakerScreen(pos, settings));
		});
	}

	public static void receive(FriendlyByteBuf packet) {
		final ResourceLocation dimension = packet.readResourceLocation();
		final BlockPos pos = packet.readBlockPos();
		final AudioSpeakerPackets.Action action = packet.readEnum(AudioSpeakerPackets.Action.class);
		final String reference = action == AudioSpeakerPackets.Action.STOP ? "" : packet.readUtf(AnnouncementSound.MAX_LENGTH);
		final String hash = action == AudioSpeakerPackets.Action.STOP ? "" : packet.readUtf(64);
		final int radius = action == AudioSpeakerPackets.Action.STOP ? 0 : packet.readVarInt();
		final int audienceMask = action == AudioSpeakerPackets.Action.STOP ? 0 : packet.readUnsignedByte();
		final Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			checkWorld(client);
			if (world == null || !dimension.equals(world.dimension().location())) return;
			final Playback previous = PLAYING.get(pos);
			if (action == AudioSpeakerPackets.Action.STOP) {
				if (previous != null) previous.stop(client.getSoundManager());
				PLAYING.remove(pos);
				return;
			}
			if (AnnouncementSound.splitUploaded(reference) == null || !AudioFiles.isHash(hash) || !SpeakerSettings.validRadius(radius)
					|| !SpeakerSettings.validAudienceMask(audienceMask)) return;
			final boolean loop = action == AudioSpeakerPackets.Action.LOOP;
			if (loop && previous != null && previous.loop && previous.reference.equals(reference) && previous.radius == radius
					&& previous.hash.equals(hash) && previous.audienceMask == audienceMask) {
				previous.lease = LOOP_LEASE_TICKS;
			} else if (previous != null || PLAYING.size() < MAX_SPEAKERS) {
				if (previous != null) previous.stop(client.getSoundManager());
				PLAYING.put(pos, new Playback(reference, hash, radius, loop, audienceMask));
			}
		});
	}

	public static void tick(Minecraft client) {
		checkWorld(client);
		if (world == null || client.isPaused()) return;
		final SoundManager manager = client.getSoundManager();
		PLAYING.entrySet().removeIf(entry -> {
			final BlockPos pos = entry.getKey();
			final Playback playback = entry.getValue();
			if (client.player != null && SpeakerSettings.audibleFrom(pos, playback.radius, playback.audienceMask,
					client.player.getX(), client.player.getY(), client.player.getZ())
					&& (!world.hasChunkAt(pos) || world.getBlockState(pos).is(Blocks.AUDIO_SPEAKER.get()))
					&& playback.tick(manager, pos)) return false;
			playback.stop(manager);
			return true;
		});
	}

	private static void checkWorld(Minecraft client) {
		if (world != client.level) {
			PLAYING.values().forEach(playback -> playback.stop(client.getSoundManager()));
			PLAYING.clear();
			world = client.level;
		}
	}

	private static final class Playback {
		private final String reference;
		private final int radius;
		private final boolean loop;
		private final int audienceMask;
		private int lease = LOOP_LEASE_TICKS;
		private int waited;
		private int pollDelay;
		private int soundAge;
		private final String hash;
		private ResourceLocation soundId;
		private SpeakerSound sound;

		private Playback(String reference, String hash, int radius, boolean loop, int audienceMask) {
			this.reference = reference;
			this.hash = hash;
			this.radius = radius;
			this.loop = loop;
			this.audienceMask = audienceMask;
		}

		private boolean tick(SoundManager manager, BlockPos pos) {
			if (loop && --lease <= 0) return false;
			if (sound != null) {
				soundAge++;
				if (!loop) return soundAge <= 20 || manager.isActive(sound);
			} else if (!loop && ++waited > DOWNLOAD_TIMEOUT_TICKS) {
				return false;
			}
			if (pollDelay-- > 0) return true;
			pollDelay = 9;
			final String[] uploaded = AnnouncementSound.splitUploaded(reference);
			final String currentHash = AudioCache.getHash(uploaded[0], uploaded[1]);
			final ResourceLocation currentId = UploadedSounds.getLocation(uploaded[0], uploaded[1]);
			if (sound != null && (!hash.equals(currentHash) || !soundId.equals(currentId)
					|| (soundAge > 20 && !manager.isActive(sound)))) stop(manager);
			if (sound != null || !hash.equals(currentHash) || currentId == null) return true;
			if (AudioCache.isCached(hash)) {
				soundId = currentId;
				soundAge = 0;
				sound = new SpeakerSound(currentId, pos, radius, loop);
				manager.play(sound);
			} else {
				AudioCache.want(hash);
			}
			return true;
		}

		private void stop(SoundManager manager) {
			if (sound != null) {
				sound.finish();
				manager.stop(sound);
				sound = null;
			}
		}
	}

	private static final class SpeakerSound extends TickableSoundInstanceMapper {
		private final int radius;

		private SpeakerSound(ResourceLocation id, BlockPos pos, int radius, boolean loop) {
			super(RegistryUtilities.createSoundEvent(id), SoundSource.BLOCKS);
			this.radius = radius;
			x = pos.getX() + 0.5;
			y = pos.getY() + 0.5;
			z = pos.getZ() + 0.5;
			pitch = 1;
			looping = loop;
			attenuation = Attenuation.NONE;
			tick();
		}

		private void finish() {
			stop();
		}

		@Override
		public void tick() {
			final LocalPlayer player = Minecraft.getInstance().player;
			volume = player == null ? 0 : SpeakerSettings.volumeAtDistance(player.distanceToSqr(x, y, z), radius);
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}
