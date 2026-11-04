package mtr.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Which version of each part of the railway data players edit the server has, so a change made from an older copy, as
 * when two players edit the same thing at once, is turned down rather than written over the other's (multiplayer edit
 * conflicts). Each kind of data (station, route, ...) is counted by object and by the part a screen sends at once, such
 * as its name and colour or its timetable, so two players editing different parts of the same route don't hold each
 * other up. Objects deleted while the server runs are remembered, so a change from a screen still open on one doesn't
 * make it again.
 */
public final class EditRevisions {

	private final Map<String, Integer> revisions = new HashMap<>();
	private final Map<String, String> editors = new HashMap<>();
	private final Map<String, String> deleted = new HashMap<>();

	/**
	 * The part a deletion counts as, which is the whole object.
	 */
	public static final String WHOLE = "*";
	/**
	 * The revision sent with a change that isn't checked, as from code that doesn't know one.
	 */
	public static final int UNCHECKED = -1;

	public int get(String kind, long id, String part) {
		return revisions.getOrDefault(getKey(kind, id, part), 0);
	}

	/**
	 * @param base the revision the change was made from, or {@link #UNCHECKED}
	 * @return null if the change can be made, or why not
	 */
	public Conflict check(String kind, long id, String part, int base, boolean isDelete) {
		final String deletedBy = deleted.get(getKey(kind, id, WHOLE));
		if (deletedBy != null) {
			return new Conflict(true, deletedBy);
		}
		if (isDelete || base == UNCHECKED) {
			return null;
		}
		final String key = getKey(kind, id, part);
		final int current = revisions.getOrDefault(key, 0);
		return base == current ? null : new Conflict(false, editors.getOrDefault(key, ""));
	}

	/**
	 * A change was made by this player.
	 *
	 * @return the part's new revision
	 */
	public int record(String kind, long id, String part, String playerName, boolean isDelete) {
		if (isDelete) {
			deleted.put(getKey(kind, id, WHOLE), playerName);
			part = WHOLE;
		}
		final String key = getKey(kind, id, part);
		final int revision = revisions.getOrDefault(key, 0) + 1;
		revisions.put(key, revision);
		editors.put(key, playerName);
		return revision;
	}

	/**
	 * @return every part's revision that isn't 0, by {@code kind:id:part}, for a player joining
	 */
	public Map<String, Integer> getAll() {
		return new HashMap<>(revisions);
	}

	public static String getKey(String kind, long id, String part) {
		return kind + ":" + id + ":" + part;
	}

	public static final class Conflict {

		/**
		 * Whether the object was deleted, rather than the part changed.
		 */
		public final boolean deleted;
		/**
		 * Who made the change it conflicts with, which may be empty.
		 */
		public final String playerName;

		private Conflict(boolean deleted, String playerName) {
			this.deleted = deleted;
			this.playerName = playerName;
		}
	}
}
