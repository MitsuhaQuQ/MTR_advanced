package transitsim.plan;

import java.util.List;

/** Per-stop arrival/departure pairs, followed by pairs for subsequent timetable indices. */
public final class TimetableTimes {
	private TimetableTimes() {
	}

	public static long[][] pack(List<long[][]> tables) {
		if (tables.isEmpty()) return new long[0][];
		int stops = tables.get(0).length;
		long[][] result = new long[stops][tables.size() * 2];
		for (int table = 0; table < tables.size(); table++) {
			if (tables.get(table).length != stops) throw new IllegalArgumentException("Inconsistent stop count");
			for (int stop = 0; stop < stops; stop++) {
				System.arraycopy(tables.get(table)[stop], 0, result[stop], table * 2, 2);
			}
		}
		return result;
	}

	public static boolean samePerformance(RouteTimetable first, RouteTimetable second) {
		if (first.getTableCount() != second.getTableCount()) return false;
		for (int i = 0; i < first.getTableCount(); i++) {
			RouteTimetable a = first.getTable(i);
			RouteTimetable b = second.getTable(i);
			if (a.minAcceleration != b.minAcceleration || a.minDeceleration != b.minDeceleration || a.minMaxSpeed != b.minMaxSpeed) return false;
		}
		return true;
	}

	/** Legacy two-column timings are shared by all tables. */
	public static int column(long[] stop, int table) {
		return stop.length == 2 ? 0 : table * 2;
	}
}
