package mtr.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits of two players at once (multiplayer edit conflicts).
 */
class EditRevisionsTest {

	@Test
	void aChangeFromAnOlderCopyIsTurnedDown() {
		final EditRevisions revisions = new EditRevisions();
		// Both opened the route's timetable at revision 0; Alice saves first
		assertNull(revisions.check("route", 5, "timetable", 0, false));
		assertEquals(1, revisions.record("route", 5, "timetable", "Alice", false));
		final EditRevisions.Conflict conflict = revisions.check("route", 5, "timetable", 0, false);
		assertNotNull(conflict);
		assertFalse(conflict.deleted);
		assertEquals("Alice", conflict.playerName);
		// From the new revision it can be made
		assertNull(revisions.check("route", 5, "timetable", 1, false));
	}

	@Test
	void differentPartsDontHoldEachOtherUp() {
		final EditRevisions revisions = new EditRevisions();
		revisions.record("route", 5, "timetable", "Alice", false);
		assertNull(revisions.check("route", 5, "name_color", 0, false));
		// Nor do the same parts of different objects
		assertNull(revisions.check("route", 6, "timetable", 0, false));
		assertNull(revisions.check("depot", 5, "timetable", 0, false));
	}

	@Test
	void aDeletedObjectIsntMadeAgain() {
		final EditRevisions revisions = new EditRevisions();
		revisions.record("route", 5, EditRevisions.WHOLE, "Bob", true);
		final EditRevisions.Conflict conflict = revisions.check("route", 5, "name_color", 0, false);
		assertNotNull(conflict);
		assertTrue(conflict.deleted);
		assertEquals("Bob", conflict.playerName);
		// Even unchecked
		assertNotNull(revisions.check("route", 5, "name_color", EditRevisions.UNCHECKED, false));
	}

	@Test
	void uncheckedChangesAndDeletionsGoThrough() {
		final EditRevisions revisions = new EditRevisions();
		revisions.record("station", 1, "name_color", "Alice", false);
		assertNull(revisions.check("station", 1, "name_color", EditRevisions.UNCHECKED, false));
		assertNull(revisions.check("station", 1, EditRevisions.WHOLE, 0, true));
		assertEquals(1, revisions.getAll().get(EditRevisions.getKey("station", 1, "name_color")));
	}
}
