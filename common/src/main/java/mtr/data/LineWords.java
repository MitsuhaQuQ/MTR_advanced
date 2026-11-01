package mtr.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Words for the direction or type of service in a route name, which routes of one line don't have to share. They are read
 * from a file in the config folder, which starts as a copy of the file in the jar, so servers can add their own.
 */
public final class LineWords {

	private static volatile Path configPath;
	private static volatile Pattern startPattern;
	private static volatile Pattern endPattern;

	public static final String FILE_NAME = "mtr_line_words.json";

	private static final String DEFAULT_FILE = "/" + FILE_NAME;
	private static final String KEY_DIRECTION_WORDS = "directionWords";
	private static final String KEY_SERVICE_WORDS = "serviceWords";
	private static final String SEPARATORS = "\\s\\-_/:：·・";
	private static final String OPEN_BRACKETS = "(（\\[【";
	private static final String CLOSE_BRACKETS = ")）\\]】";

	static {
		try {
			setWords(new String(readDefaultFile(), StandardCharsets.UTF_8));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private LineWords() {
	}

	/**
	 * Reads the file, or creates it from the default words. If it can't be read, the words stay as they were.
	 */
	public static void load(Path path) {
		configPath = path;
		reload();
	}

	public static void reload() {
		final Path path = configPath;
		if (path != null) {
			try {
				if (Files.exists(path)) {
					setWords(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
				} else {
					final byte[] defaultFile = readDefaultFile();
					setWords(new String(defaultFile, StandardCharsets.UTF_8));
					Files.createDirectories(path.getParent());
					Files.write(path, defaultFile);
				}
			} catch (Exception e) {
				System.out.println("Failed to read Minecraft Transit Railway line words from " + path);
				e.printStackTrace();
			}
		}
	}

	/**
	 * Removes these words from both ends of the text until there are none left there.
	 */
	public static String removeFromEnds(String text) {
		final Pattern start = startPattern;
		final Pattern end = endPattern;
		if (start == null || end == null) {
			return text.trim();
		}

		String result = text.trim();
		String lastResult;
		do {
			lastResult = result;
			result = start.matcher(end.matcher(result).replaceAll("")).replaceAll("").trim();
		} while (!result.equals(lastResult));
		return result;
	}

	private static byte[] readDefaultFile() throws Exception {
		try (final InputStream inputStream = LineWords.class.getResourceAsStream(DEFAULT_FILE); final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			final byte[] buffer = new byte[1024];
			int length;
			while ((length = inputStream.read(buffer)) >= 0) {
				outputStream.write(buffer, 0, length);
			}
			return outputStream.toByteArray();
		}
	}

	private static void setWords(String json) {
		final JsonObject jsonObject = new JsonParser().parse(json).getAsJsonObject();
		final List<String> words = new ArrayList<>();
		addWords(jsonObject, KEY_DIRECTION_WORDS, words);
		addWords(jsonObject, KEY_SERVICE_WORDS, words);
		if (words.isEmpty()) {
			startPattern = null;
			endPattern = null;
		} else {
			// Longer words come first, so "upbound" isn't read as "up"
			final String wordGroup = words.stream().distinct().sorted(Comparator.comparingInt(String::length).reversed()).map(LineWords::toRegex).collect(Collectors.joining("|", "(", ")"));
			endPattern = Pattern.compile("[" + SEPARATORS + "]*[" + OPEN_BRACKETS + "]?\\s*" + wordGroup + "[线線]?\\s*[" + CLOSE_BRACKETS + "]?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
			startPattern = Pattern.compile("^[" + OPEN_BRACKETS + "]?\\s*" + wordGroup + "\\s*[" + CLOSE_BRACKETS + SEPARATORS + "]+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		}
	}

	private static void addWords(JsonObject jsonObject, String key, List<String> words) {
		if (jsonObject.has(key)) {
			final JsonArray jsonArray = jsonObject.getAsJsonArray(key);
			jsonArray.forEach(word -> {
				final String text = word.getAsString().trim();
				if (!text.isEmpty()) {
					words.add(text);
				}
			});
		}
	}

	/**
	 * English words have to stand alone, or names like Backup would lose their ending.
	 */
	private static String toRegex(String word) {
		return word.matches("[A-Za-z ]+") ? "(?<![a-z])" + Pattern.quote(word) + "(?![a-z])" : Pattern.quote(word);
	}
}
