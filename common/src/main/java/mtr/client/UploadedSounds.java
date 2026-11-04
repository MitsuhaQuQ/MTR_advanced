package mtr.client;

import com.mojang.blaze3d.audio.SoundBuffer;
import mtr.audio.AnnouncementSound;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The server's announcement audio as sounds of the game, named {@code mtr_audio:<who added it>/<sound ID>}, so announcers
 * play it through the game's sound system, at the game's volume and output, and {@code /playsound} can play it too (its
 * suggestions come from the sounds the client knows). The sounds are added to the game's list whenever the server's list
 * or the game's sounds are loaded again, and the game asks for their audio as for any sound: it is decoded from the files
 * this client has ({@link AudioCache}) and kept by the game until the next reload. A file not here yet is asked for, and
 * plays the next time. Called on the game thread, apart from decoding.
 */
public final class UploadedSounds {

	/**
	 * By the path the game asks for the audio of a sound with ({@link Sound#getPath}).
	 */
	private static final Map<ResourceLocation, AudioCache.Entry> BY_PATH = new HashMap<>();
	private static final Map<ResourceLocation, AudioCache.Entry> BY_LOCATION = new HashMap<>();
	private static final int ATTENUATION_DISTANCE = 16;

	private UploadedSounds() {
	}

	/**
	 * Puts the server's files in the game's list of sounds in place of the ones there before. Without the mixin, as with
	 * a loader it doesn't apply to, the game's list isn't touched and announcers play the files by themselves.
	 */
	public static void refresh() {
		BY_PATH.clear();
		BY_LOCATION.clear();
		final SoundManager soundManager = Minecraft.getInstance().getSoundManager();
		if (!(soundManager instanceof SoundManagerAccess)) {
			return;
		}
		final Map<ResourceLocation, WeighedSoundEvents> registry = ((SoundManagerAccess) soundManager).mtr$getSoundRegistry();
		registry.keySet().removeIf(location -> location.getNamespace().equals(AnnouncementSound.SOUND_NAMESPACE));
		AudioCache.ENTRIES.forEach(entry -> {
			final ResourceLocation location = entry.getSoundLocation();
			if (location == null || BY_LOCATION.containsKey(location)) {
				return;
			}
			final Sound sound = new Sound(location.toString(), ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, false, false, ATTENUATION_DISTANCE);
			final WeighedSoundEvents events = new WeighedSoundEvents(location, null);
			events.addSound(sound);
			registry.put(location, events);
			BY_PATH.put(sound.getPath(), entry);
			BY_LOCATION.put(location, entry);
		});
	}

	/**
	 * @return the sound the game plays a file by, or null if the file has none in the game
	 */
	public static ResourceLocation getLocation(String namespace, String fileName) {
		for (final Map.Entry<ResourceLocation, AudioCache.Entry> mapEntry : BY_LOCATION.entrySet()) {
			final AudioCache.Entry entry = mapEntry.getValue();
			if (entry.namespace.equals(namespace) && entry.name.equalsIgnoreCase(fileName)) {
				return mapEntry.getKey();
			}
		}
		return null;
	}

	public static boolean isUploadedPath(ResourceLocation path) {
		return path.getNamespace().equals(AnnouncementSound.SOUND_NAMESPACE);
	}

	/**
	 * In place of the game reading a sound's file from the resource packs ({@code SoundBufferLibrary#getCompleteBuffer}).
	 * What fails, as a file not here yet, isn't kept, so it is tried again the next time.
	 *
	 * @param cache the game's audio of the sounds it has played, kept until the next reload
	 */
	public static CompletableFuture<SoundBuffer> getBuffer(Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache, ResourceLocation path) {
		final CompletableFuture<SoundBuffer> cached = cache.get(path);
		if (cached != null) {
			return cached;
		}
		final AudioCache.Entry entry = BY_PATH.get(path);
		if (entry == null) {
			return failed("no such announcement audio " + path);
		}
		if (!AudioCache.isCached(entry.hash)) {
			AudioCache.want(entry.hash);
			return failed("announcement audio not here yet " + path);
		}
		final CompletableFuture<SoundBuffer> future = CompletableFuture.supplyAsync(() -> {
			final byte[] bytes = AudioCache.readStored(entry.hash);
			if (bytes == null) {
				throw new IllegalStateException("announcement audio not here " + path);
			}
			try {
				return AnnouncementPlayer.toSoundBuffer(bytes);
			} catch (Exception e) {
				throw new IllegalStateException("can't decode announcement audio " + path + ": " + e.getMessage(), e);
			}
		}, Util.backgroundExecutor());
		cache.put(path, future);
		future.whenComplete((buffer, throwable) -> {
			if (throwable != null) {
				System.out.println("Can't play announcement audio: " + throwable.getMessage());
				Minecraft.getInstance().execute(() -> cache.remove(path, future));
			}
		});
		return future;
	}

	private static CompletableFuture<SoundBuffer> failed(String message) {
		final CompletableFuture<SoundBuffer> future = new CompletableFuture<>();
		future.completeExceptionally(new IllegalStateException(message));
		return future;
	}
}
