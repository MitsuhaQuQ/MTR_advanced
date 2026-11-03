package mtr.data;

import mtr.mappings.Text;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;

/**
 * Messages made where the player's language isn't known, such as on the server, are kept as a translation key and its
 * arguments in one string, and translated where they are shown.
 */
public interface TranslatedMessage {

	char START = '\u0001';
	String SEPARATOR = "\u001f";

	/**
	 * @return the key and arguments in one string, to translate later with {@link #decode(String)}
	 */
	static String encode(String key, Object... arguments) {
		final StringBuilder builder = new StringBuilder().append(START).append(key);
		for (final Object argument : arguments) {
			builder.append(SEPARATOR).append(argument);
		}
		return builder.toString();
	}

	/**
	 * @return the message in this side's language, or the message itself if it wasn't made by {@link #encode(String, Object...)}
	 */
	static String decode(String message) {
		return toComponent(message).getString();
	}

	static MutableComponent toComponent(String message) {
		if (message == null || message.isEmpty() || message.charAt(0) != START) {
			return Text.literal(message == null ? "" : message);
		}
		final String[] parts = message.substring(1).split(SEPARATOR, -1);
		return Text.translatable(parts[0], (Object[]) Arrays.copyOfRange(parts, 1, parts.length));
	}

	/**
	 * Translates straight away, for messages made on the client.
	 */
	static String get(String key, Object... arguments) {
		return Text.translatable(key, arguments).getString();
	}
}
