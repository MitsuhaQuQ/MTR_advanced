package mtr.image;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finding images by name in the painting screen's list (0.65.28): every word of the query, apart from case, somewhere in
 * the name, in the order the list has them.
 */
public final class ImageSearch {

	private ImageSearch() {
	}

	/**
	 * @return the indices of the names matching the query, all of them for a blank query
	 */
	public static List<Integer> filter(List<String> names, String query) {
		final String[] words = query == null ? new String[0] : query.trim().toLowerCase(Locale.ROOT).split("\s+");
		final List<Integer> matches = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			if (matches(names.get(i), words)) {
				matches.add(i);
			}
		}
		return matches;
	}

	/**
	 * @return whether the name has every word, ignoring case; true when there are no words
	 */
	public static boolean matches(String name, String query) {
		return matches(name, query == null ? new String[0] : query.trim().toLowerCase(Locale.ROOT).split("\s+"));
	}

	private static boolean matches(String name, String[] words) {
		final String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
		for (final String word : words) {
			if (!word.isEmpty() && !lower.contains(word)) {
				return false;
			}
		}
		return true;
	}
}
