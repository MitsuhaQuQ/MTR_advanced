package mtr.audio;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Set;

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
