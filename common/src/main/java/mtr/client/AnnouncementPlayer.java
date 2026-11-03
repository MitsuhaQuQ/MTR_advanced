package mtr.client;

import mtr.audio.AnnouncementSound;
import mtr.audio.AudioFiles;
import mtr.mappings.RegistryUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays what an announcer names ({@link AnnouncementSound}). A sound of the game goes through the game as before. A file
 * added to the server and a web address are played straight to the computer's audio output, at the game's master and
 * block volume, as the game can only play sounds that a resource pack registers. One announcement plays at a time; a new
 * one cuts off the last. A file that isn't here yet is asked for and skipped this time.
 * <p>
 * A web address is only fetched if the player turned it on in {@code config/mtr.json} and listed the site, as otherwise
 * whoever edits an announcer could make every player nearby contact any site. Only WAV and Ogg Vorbis are played, and a
 * file is checked before it is decoded.
 */
public final class AnnouncementPlayer {

	private static final int TIMEOUT_MILLIS = 10000;
	private static final int MAX_NETWORK_BYTES = 4 * 1024 * 1024;
	private static final int MAX_NETWORK_CACHE = 16;
	private static final long MAX_DECODED_MILLIS = 10 * 60 * 1000;
	private static final int WRITE_FRAMES = 2048;
	private static final String USER_AGENT = "Minecraft-Transit-Railway";

	private static final AtomicInteger GENERATION = new AtomicInteger();
	private static final Map<String, byte[]> NETWORK_CACHE = Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(MAX_NETWORK_CACHE, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
			return size() > MAX_NETWORK_CACHE;
		}
	});
	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> createThread(runnable, "MTR Announcement Loader"));
	private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(runnable -> createThread(runnable, "MTR Announcement Player"));

	private AnnouncementPlayer() {
	}

	/**
	 * Called on the game thread. A file added to the server is found by who added it and its file name in the list the
	 * server sent; only text that names no such file is taken as a sound of the game.
	 *
	 * @param pos where a sound of the game is played
	 */
	public static void play(String soundString, BlockPos pos) {
		final Minecraft minecraft = Minecraft.getInstance();
		final ClientLevel world = minecraft.level;
		final String cleanSoundString = AnnouncementSound.clean(soundString);
		if (world == null || cleanSoundString.isEmpty()) {
			return;
		}

		final float volume = minecraft.options.getSoundSourceVolume(SoundSource.MASTER) * minecraft.options.getSoundSourceVolume(SoundSource.BLOCKS);
		final String[] uploaded = AnnouncementSound.splitUploaded(cleanSoundString);
		final String hash = uploaded == null ? null : AudioCache.getHash(uploaded[0], uploaded[1]);
		if (hash != null) {
			final byte[] bytes = AudioCache.read(hash);
			if (bytes != null) {
				final int generation = GENERATION.incrementAndGet();
				LOADER.execute(() -> decodeAndPlay(bytes, volume, generation, cleanSoundString));
			}
			return;
		}

		final ResourceLocation soundId = AnnouncementSound.getSoundId(cleanSoundString);
		if (soundId != null) {
			world.playLocalSound(pos, RegistryUtilities.createSoundEvent(soundId), SoundSource.BLOCKS, 1000000, 1, false);
		} else if (AnnouncementSound.isNetwork(cleanSoundString)) {
			if (!isAllowed(cleanSoundString)) {
				System.out.println("Not playing announcement audio from a site that isn't allowed in config/mtr.json: " + cleanSoundString);
				return;
			}
			final int generation = GENERATION.incrementAndGet();
			LOADER.execute(() -> {
				try {
					decodeAndPlay(download(cleanSoundString), volume, generation, cleanSoundString);
				} catch (Exception e) {
					System.out.println("Failed to fetch announcement audio " + cleanSoundString + ": " + e.getMessage());
				}
			});
		}
	}

	public static void stop() {
		GENERATION.incrementAndGet();
	}

	/**
	 * @return whether the player turned on audio from the web and listed this site, or a site it is part of
	 */
	public static boolean isAllowed(String url) {
		if (!Config.networkAudioEnabled()) {
			return false;
		}
		try {
			final URI uri = new URI(url);
			final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ENGLISH);
			final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ENGLISH);
			if (!scheme.equals("http") && !scheme.equals("https") || host.isEmpty() || uri.getUserInfo() != null) {
				return false;
			}
			return Config.networkAudioAllowedHosts().stream().anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Redirects aren't followed, as they could lead to a site that isn't allowed.
	 */
	private static byte[] download(String url) throws Exception {
		final byte[] cached = NETWORK_CACHE.get(url);
		if (cached != null) {
			return cached;
		}
		final HttpURLConnection connection = (HttpURLConnection) new URI(url).toURL().openConnection();
		try {
			connection.setInstanceFollowRedirects(false);
			connection.setConnectTimeout(TIMEOUT_MILLIS);
			connection.setReadTimeout(TIMEOUT_MILLIS);
			connection.setRequestProperty("User-Agent", USER_AGENT);
			final int responseCode = connection.getResponseCode();
			if (responseCode != HttpURLConnection.HTTP_OK) {
				throw new IllegalStateException("HTTP " + responseCode);
			}
			if (connection.getContentLengthLong() > MAX_NETWORK_BYTES) {
				throw new IllegalStateException("too large");
			}
			final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			final byte[] buffer = new byte[8192];
			try (final InputStream inputStream = connection.getInputStream()) {
				int read;
				while ((read = inputStream.read(buffer)) > 0) {
					if (outputStream.size() + read > MAX_NETWORK_BYTES) {
						throw new IllegalStateException("too large");
					}
					outputStream.write(buffer, 0, read);
				}
			}
			final byte[] bytes = outputStream.toByteArray();
			NETWORK_CACHE.put(url, bytes);
			return bytes;
		} finally {
			connection.disconnect();
		}
	}

	private static void decodeAndPlay(byte[] bytes, float volume, int generation, String name) {
		if (generation != GENERATION.get()) {
			return;
		}
		final Pcm pcm;
		try {
			pcm = isWav(bytes) ? decodeWav(bytes) : decodeVorbis(bytes);
		} catch (Exception | LinkageError e) {
			System.out.println("Can't play announcement audio " + name + ": " + e.getMessage());
			return;
		}
		PLAYER.execute(() -> playPcm(pcm, volume, generation));
	}

	private static boolean isWav(byte[] bytes) {
		return bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF") && new String(bytes, 8, 4, StandardCharsets.ISO_8859_1).equals("WAVE");
	}

	/**
	 * The file is checked as the server checks uploads before the decoder sees it, which also bounds how long it can be.
	 */
	private static Pcm decodeVorbis(byte[] bytes) throws Exception {
		final AudioFiles.Info info = AudioFiles.check(bytes);
		if (info.durationMillis > MAX_DECODED_MILLIS) {
			throw new AudioFiles.InvalidAudioException("too long");
		}
		final ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
		try (final MemoryStack stack = MemoryStack.stackPush()) {
			buffer.put(bytes).flip();
			final IntBuffer channels = stack.mallocInt(1);
			final IntBuffer sampleRate = stack.mallocInt(1);
			final ShortBuffer samples = STBVorbis.stb_vorbis_decode_memory(buffer, channels, sampleRate);
			if (samples == null) {
				throw new AudioFiles.InvalidAudioException("not decodable");
			}
			try {
				final byte[] data = new byte[samples.remaining() * 2];
				for (int i = 0; samples.hasRemaining(); i += 2) {
					final short sample = samples.get();
					data[i] = (byte) sample;
					data[i + 1] = (byte) (sample >> 8);
				}
				return new Pcm(data, sampleRate.get(0), channels.get(0));
			} finally {
				LibCStdlib.free(samples);
			}
		} finally {
			MemoryUtil.memFree(buffer);
		}
	}

	private static Pcm decodeWav(byte[] bytes) throws Exception {
		try (final AudioInputStream sourceStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
			final AudioFormat sourceFormat = sourceStream.getFormat();
			final int channels = sourceFormat.getChannels();
			if (channels < 1 || channels > 2) {
				throw new AudioFiles.InvalidAudioException("only mono or stereo");
			}
			final AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sourceFormat.getSampleRate(), 16, channels, channels * 2, sourceFormat.getSampleRate(), false);
			try (final AudioInputStream stream = AudioSystem.getAudioInputStream(format, sourceStream)) {
				final long maxBytes = (long) (format.getSampleRate() * channels * 2 * MAX_DECODED_MILLIS / 1000);
				final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				final byte[] buffer = new byte[8192];
				int read;
				while ((read = stream.read(buffer)) > 0) {
					if (outputStream.size() + read > maxBytes) {
						throw new AudioFiles.InvalidAudioException("too long");
					}
					outputStream.write(buffer, 0, read);
				}
				return new Pcm(outputStream.toByteArray(), (int) format.getSampleRate(), channels);
			}
		}
	}

	private static void playPcm(Pcm pcm, float volume, int generation) {
		if (generation != GENERATION.get() || volume <= 0) {
			return;
		}
		final AudioFormat format = new AudioFormat(pcm.sampleRate, 16, pcm.channels, true, false);
		final int frameBytes = pcm.channels * 2;
		try (final SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format))) {
			line.open(format);
			line.start();
			final byte[] chunk = new byte[WRITE_FRAMES * frameBytes];
			for (int offset = 0; offset < pcm.data.length && generation == GENERATION.get(); offset += chunk.length) {
				final int length = Math.min(chunk.length, pcm.data.length - offset);
				for (int i = 0; i + 1 < length; i += 2) {
					final int sample = Math.round((short) ((pcm.data[offset + i] & 0xFF) | pcm.data[offset + i + 1] << 8) * Math.min(1, volume));
					chunk[i] = (byte) sample;
					chunk[i + 1] = (byte) (sample >> 8);
				}
				line.write(chunk, 0, length);
			}
			if (generation == GENERATION.get()) {
				line.drain();
			} else {
				line.flush();
			}
			line.stop();
		} catch (Exception e) {
			System.out.println("Failed to play announcement audio: " + e.getMessage());
		}
	}

	private static Thread createThread(Runnable runnable, String name) {
		final Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}

	private static final class Pcm {

		private final byte[] data;
		private final int sampleRate;
		private final int channels;

		private Pcm(byte[] data, int sampleRate, int channels) {
			this.data = data;
			this.sampleRate = sampleRate;
			this.channels = channels;
		}
	}
}
