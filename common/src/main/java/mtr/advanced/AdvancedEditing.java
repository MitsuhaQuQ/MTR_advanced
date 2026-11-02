package mtr.advanced;

import mtr.data.Route;

/**
 * Rules of the advanced editor that the game's own screens follow. This package holds the logic this fork adds on top of
 * MTR, so the original classes only keep the fields they save and call in here.
 */
public interface AdvancedEditing {

	/**
	 * A route set up in the advanced editor keeps its timetable and stops there, so the game's own controls only show them
	 * and can't change them behind the editor's back.
	 */
	static boolean isLockedInGame(Route route) {
		return route != null && route.editorManaged;
	}
}
