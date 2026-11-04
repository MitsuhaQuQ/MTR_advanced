package mtr.audio;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * What an announcer plays, as one piece of text that the block keeps and the server sends: a file added to the server,
 * named by who added it and its file name ({@code steve:下一站.ogg}, or {@code mtr_player:下一站.ogg} for a player named
 * like a mod, see {@link AudioServer}), a sound of the game or a
 * resource pack ({@code mtr:ticket_barrier}), or a web address ({@code https://...}), which a client only plays when its
 * player allows that site. Added files and sounds look alike, so a client looks for an added file first. The same text
 * works on the server and the client.
 */
public final class AnnouncementSound {

	public static final int MAX_LENGTH = 1024;
	/**
	 * The namespace the sounds of added files have in the game, so {@code /playsound} and resource-style names play them
	 * ({@code mtr_audio:steve/next_station}).
	 */
	public static final String SOUND_NAMESPACE = "mtr_audio";
	public static final int MAX_SOUND_ID_LENGTH = 64;
	private static final Pattern SOUND_ID = Pattern.compile("[a-z0-9_.-]+(/[a-z0-9_.-]+)*");
	private static final int HASH_SOUND_ID_LENGTH = 12;

	private AnnouncementSound() {
	}

	public static String clean(String soundString) {
		final String trimmed = soundString == null ? "" : soundString.trim();
		return trimmed.length() > MAX_LENGTH ? "" : trimmed;
	}

	public static final String PLAYER_SUFFIX = "_player";

	/**
	 * @param namespace who added the file, as {@link #getNamespace} made it when the file was added
	 */
	public static String getUploadedString(String namespace, String fileName) {
		return namespace + ":" + fileName;
	}

	/**
	 * What tells names apart: names are matched without case, so this is the name in lowercase.
	 */
	public static String getNameKey(String namespace, String fileName) {
		return getUploadedString(namespace, fileName).toLowerCase(Locale.ENGLISH);
	}

	/**
	 * The player's name in lowercase, with {@link #PLAYER_SUFFIX} if a mod uses that name, so a player named like a mod
	 * doesn't hide its sounds. It is kept with the file, so the name stays if the player renames or mods change.
	 *
	 * @param modNamespaces the names that belong to mods
	 */
	public static String getNamespace(String playerName, Set<String> modNamespaces) {
		final String namespace = playerName.toLowerCase(Locale.ENGLISH);
		return modNamespaces.contains(namespace) ? namespace + PLAYER_SUFFIX : namespace;
	}

	/**
	 * @return who added the file and its file name, if this could name an added file, or null
	 */
	public static String[] splitUploaded(String soundString) {
		final int colon = soundString.indexOf(':');
		if (colon <= 0 || colon == soundString.length() - 1 || isNetwork(soundString)) {
			return null;
		}
		return new String[]{soundString.substring(0, colon).toLowerCase(Locale.ENGLISH), soundString.substring(colon + 1)};
	}

	/**
	 * @return whether this can be the sound ID of an added file: lowercase letters, digits, {@code _ - .} and {@code /}
	 * between parts, as the game's sound names allow
	 */
	public static boolean isValidSoundId(String soundId) {
		return soundId != null && !soundId.isEmpty() && soundId.length() <= MAX_SOUND_ID_LENGTH && SOUND_ID.matcher(soundId).matches();
	}

	/**
	 * @return the file name without {@code .ogg} in lowercase, spaces as {@code _}, if that is a valid sound ID, or else
	 * empty, as for a name in Chinese
	 */
	public static String getSoundIdFromFileName(String fileName) {
		String stem = fileName == null ? "" : fileName.trim();
		if (stem.toLowerCase(Locale.ENGLISH).endsWith(".ogg")) {
			stem = stem.substring(0, stem.length() - 4);
		}
		final String soundId = stem.toLowerCase(Locale.ENGLISH).replace(' ', '_');
		return isValidSoundId(soundId) ? soundId : "";
	}

	/**
	 * The sound ID a file gets when none was given, as for files added before sound IDs: from its file name if it can be,
	 * else from its hash, made unique among the files of whoever added it.
	 *
	 * @param taken whether another file of the same uploader has this sound ID
	 */
	public static String getDefaultSoundId(String fileName, String hash, Predicate<String> taken) {
		final String fromName = getSoundIdFromFileName(fileName);
		final String base = fromName.isEmpty() ? hash.substring(0, Math.min(HASH_SOUND_ID_LENGTH, hash.length())) : fromName;
		if (!taken.test(base)) {
			return base;
		}
		for (int length = 6; length <= hash.length(); length += 6) {
			final String candidate = (base.length() + 1 + length > MAX_SOUND_ID_LENGTH ? base.substring(0, MAX_SOUND_ID_LENGTH - 1 - length) : base) + "_" + hash.substring(0, length);
			if (!taken.test(candidate)) {
				return candidate;
			}
		}
		return hash.substring(0, Math.min(MAX_SOUND_ID_LENGTH, hash.length()));
	}

	/**
	 * @param namespace who added the file, as {@link #getNamespace} made it
	 * @return the name of the file's sound in the game, or null if the sound ID isn't valid
	 */
	public static ResourceLocation getSoundLocation(String namespace, String soundId) {
		if (!isValidSoundId(soundId) || !isValidSoundId(namespace)) {
			return null;
		}
		try {
			return new ResourceLocation(SOUND_NAMESPACE, namespace + "/" + soundId);
		} catch (Exception e) {
			return null;
		}
	}

	public static boolean isNetwork(String soundString) {
		final String lowerCase = soundString.toLowerCase(Locale.ENGLISH);
		return lowerCase.startsWith("http://") || lowerCase.startsWith("https://");
	}

	/**
	 * @return the sound of the game, or null if this isn't one or isn't a valid name
	 */
	public static ResourceLocation getSoundId(String soundString) {
		if (soundString.isEmpty() || isNetwork(soundString)) {
			return null;
		}
		try {
			return new ResourceLocation(soundString);
		} catch (Exception e) {
			return null;
		}
	}
}
