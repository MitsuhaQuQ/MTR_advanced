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
import transitsim.sim.ClipSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the game's sound instances for the speakers addressed to this player. Since 0.70.0 a speaker plays its clips one
 * after another with the gaps set after them ({@link ClipSequence}); a loop of one clip with no interval stays the game's
 * own looping sound, with no gap at all.
 */
public final class SpeakerPlayback {
	private static final int LOOP_LEASE_TICKS = 80;
	private static final int DOWNLOAD_TIMEOUT_TICKS = 600;
	private static final int MAX_SPEAKERS = 32;
	/** How long a clip just started counts as playing before the sound manager is asked */
	private static final int START_TICKS = 2;
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
		final List<Clip> clips = new ArrayList<>();
		int radius = 0, audienceMask = 0, interval = 0;
		boolean finish = false;
		if (action != AudioSpeakerPackets.Action.STOP) {
			final int count = packet.readVarInt();
			if (count < 1 || count > SpeakerSettings.MAX_CLIPS) return;
			for (int i = 0; i < count; i++) {
				clips.add(new Clip(packet.readUtf(AnnouncementSound.MAX_LENGTH), packet.readUtf(64), packet.readVarInt()));
			}
			radius = packet.readVarInt();
			audienceMask = packet.readUnsignedByte();
			finish = packet.readBoolean();
			interval = packet.readVarInt();
		}
		final int finalRadius = radius, finalMask = audienceMask, finalInterval = interval;
		final boolean finalFinish = finish;
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
			if (clips.stream().anyMatch(clip -> !clip.valid()) || !SpeakerSettings.validRadius(finalRadius)
					|| !SpeakerSettings.validAudienceMask(finalMask) || !SpeakerSettings.validTenths(finalInterval)) return;
			final boolean loop = action == AudioSpeakerPackets.Action.LOOP;
			if (!loop && finalFinish && previous != null && !previous.loop && previous.clips.equals(clips)) return;
			if (loop && previous != null && previous.loop && previous.clips.equals(clips) && previous.radius == finalRadius
					&& previous.audienceMask == finalMask && previous.intervalTenths == finalInterval) {
				previous.lease = LOOP_LEASE_TICKS;
			} else if (previous != null || makeRoom(client, pos, finalRadius, finalMask)) {
				if (previous != null) previous.stop(client.getSoundManager());
				PLAYING.put(pos, new Playback(clips, finalRadius, loop, finalMask, finalInterval, finalFinish));
			}
		});
	}

	private static boolean makeRoom(Minecraft client, BlockPos pos, int radius, int audienceMask) {
		if (PLAYING.size() < MAX_SPEAKERS) return true;
		final LocalPlayer player = client.player;
		if (player == null || !SpeakerSettings.audibleFrom(pos, radius, audienceMask, player.getX(), player.getY(), player.getZ())) return false;
		BlockPos farthest = null;
		double distance = -1;
		for (final Map.Entry<BlockPos, Playback> entry : PLAYING.entrySet()) {
			final BlockPos at = entry.getKey();
			final Playback playback = entry.getValue();
			final double candidate = player.distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
			if (candidate > distance && !SpeakerSettings.audibleFrom(at, playback.radius, playback.audienceMask,
					player.getX(), player.getY(), player.getZ())) {
				farthest = at;
				distance = candidate;
			}
		}
		if (farthest == null) return false;
		PLAYING.remove(farthest).stop(client.getSoundManager());
		return true;
	}

	public static void tick(Minecraft client) {
		checkWorld(client);
		if (world == null || client.isPaused()) return;
		final SoundManager manager = client.getSoundManager();
		PLAYING.entrySet().removeIf(entry -> {
			final BlockPos pos = entry.getKey();
			final Playback playback = entry.getValue();
			if (client.player != null
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

	/** A clip as the server sent it: the uploaded sound, its file's hash and the gap after it in tenths of a second */
	private static final class Clip {
		private final String reference;
		private final String hash;
		private final int gapTenths;

		private Clip(String reference, String hash, int gapTenths) {
			this.reference = reference;
			this.hash = hash;
			this.gapTenths = gapTenths;
		}

		private boolean valid() {
			return AnnouncementSound.splitUploaded(reference) != null && AudioFiles.isHash(hash) && SpeakerSettings.validTenths(gapTenths);
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Clip && ((Clip) other).reference.equals(reference) && ((Clip) other).hash.equals(hash) && ((Clip) other).gapTenths == gapTenths;
		}

		@Override
		public int hashCode() {
			return (reference.hashCode() * 31 + hash.hashCode()) * 31 + gapTenths;
		}
	}

	private static final class Playback {
		private final List<Clip> clips;
		private final int radius;
		private final boolean loop;
		private final boolean seamless;
		private final int audienceMask;
		private final int intervalTenths;
		private final ClipSequence sequence;
		private int lease = LOOP_LEASE_TICKS;
		private int waited;
		private int pollDelay;
		private int soundAge;
		private int cooldown = -1;
		private final int cooldownTicks;
		/** Whether every clip's file is here; nothing plays before */
		private boolean ready;
		private SpeakerSound sound;

		private Playback(List<Clip> clips, int radius, boolean loop, int audienceMask, int intervalTenths, boolean finish) {
			this.clips = clips;
			this.radius = radius;
			this.loop = loop;
			this.audienceMask = audienceMask;
			this.intervalTenths = intervalTenths;
			seamless = loop && clips.size() == 1 && intervalTenths == 0;
			final long[] gaps = new long[clips.size()];
			for (int i = 0; i < gaps.length; i++) gaps[i] = SpeakerSettings.tenthsToTicks(clips.get(i).gapTenths);
			sequence = new ClipSequence(gaps, loop, SpeakerSettings.tenthsToTicks(intervalTenths));
			// After a single play, the interval before another is the server's; it is waited here only when finishing first
			cooldownTicks = finish ? (int) Math.min(Integer.MAX_VALUE, SpeakerSettings.tenthsToTicks(intervalTenths)) : 0;
		}

		private boolean tick(SoundManager manager, BlockPos pos) {
			if (cooldown >= 0) return --cooldown > 0;
			if (loop && --lease <= 0) return false;
			if (!ready) {
				if (!loop && ++waited > DOWNLOAD_TIMEOUT_TICKS) return false;
				if (pollDelay-- > 0) return true;
				pollDelay = 9;
				ready = true;
				for (final Clip clip : clips) {
					final String[] uploaded = AnnouncementSound.splitUploaded(clip.reference);
					if (!clip.hash.equals(AudioCache.getHash(uploaded[0], uploaded[1])) || UploadedSounds.getLocation(uploaded[0], uploaded[1]) == null) {
						ready = false;
					} else if (!AudioCache.isCached(clip.hash)) {
						AudioCache.want(clip.hash);
						ready = false;
					}
				}
				if (!ready) return true;
			}
			if (sound != null) soundAge++;
			if (seamless) {
				if (sound == null) start(manager, pos, 0);
				return true;
			}
			final boolean playing = sound != null && (soundAge <= START_TICKS || manager.isActive(sound));
			if (!playing && sound != null) stop(manager);
			final int next = sequence.tick(playing);
			if (next >= 0) start(manager, pos, next);
			if (sequence.hasEnded()) {
				cooldown = cooldownTicks;
				return cooldown > 0;
			}
			return true;
		}

		private void start(SoundManager manager, BlockPos pos, int index) {
			final String[] uploaded = AnnouncementSound.splitUploaded(clips.get(index).reference);
			final ResourceLocation id = UploadedSounds.getLocation(uploaded[0], uploaded[1]);
			stop(manager);
			soundAge = 0;
			if (id == null) return;
			sound = new SpeakerSound(id, pos, radius, seamless, audienceMask);
			manager.play(sound);
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
		private final BlockPos position;
		private final int audienceMask;

		private SpeakerSound(ResourceLocation id, BlockPos pos, int radius, boolean loop, int audienceMask) {
			super(RegistryUtilities.createSoundEvent(id), SoundSource.BLOCKS);
			this.radius = radius;
			this.position = pos.immutable();
			this.audienceMask = audienceMask;
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
			volume = player == null ? 0 : SpeakerSettings.volumeAt(position, radius, audienceMask,
					player.getX(), player.getY(), player.getZ());
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}
