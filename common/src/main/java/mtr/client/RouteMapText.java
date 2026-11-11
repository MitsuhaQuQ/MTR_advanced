package mtr.client;

import com.ibm.icu.text.Transliterator;

/** Two display lines for route maps, independent of the player's GUI language. */
public final class RouteMapText {

	private static final Transliterator TRADITIONAL = Transliterator.getInstance("Simplified-Traditional");

	private RouteMapText() {
	}

	public static String bilingual(String name) {
		return format(name, "%s", "%s");
	}

	public static String to(String name) {
		return format(name, "往%s", "To %s");
	}

	public static String loopNext(String kind, String name) {
		if ("clockwise".equals(kind)) return format(name, "順時針環線 下一站%s", "Clockwise loop, next %s");
		if ("anticlockwise".equals(kind)) return format(name, "逆時針環線 下一站%s", "Anticlockwise loop, next %s");
		return format(name, "環線 下一站%s", "Loop, next %s");
	}

	private static String format(String name, String chineseTemplate, String englishTemplate) {
		if (name == null || name.isEmpty()) return "";
		String chinese = "", english = "";
		for (final String raw : name.split("\\|")) {
			final String part = raw.trim();
			if (part.isEmpty()) continue;
			final boolean kana = part.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA);
			if (kana) continue;
			if (part.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
				if (chinese.isEmpty()) chinese = part;
			} else if (english.isEmpty()) english = part;
		}
		if (!chinese.isEmpty()) {
			// ICU is provided by Minecraft; its mutable transliterator is shared by texture workers.
			synchronized (TRADITIONAL) { chinese = TRADITIONAL.transliterate(chinese); }
			chinese = String.format(chineseTemplate, chinese);
		}
		if (!english.isEmpty()) english = String.format(englishTemplate, english);
		return chinese.isEmpty() ? english : english.isEmpty() ? chinese : chinese + "|" + english;
	}
}
