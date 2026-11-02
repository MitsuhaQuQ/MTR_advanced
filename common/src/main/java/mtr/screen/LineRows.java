package mtr.screen;

import mtr.data.DataConverter;
import mtr.data.NameColorDataBase;
import mtr.data.Route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folds the routes of one line into a row of their own in a list, which opens to show each route.
 */
public class LineRows {

	private final Set<String> expandedLines = new HashSet<>();
	private final Map<Long, String> headerRows = new HashMap<>();
	private final Set<Long> childRows = new HashSet<>();

	/**
	 * @param routes the routes in the order to show them
	 * @return the rows of the list, where a line with one route is just that route
	 */
	public List<NameColorDataBase> getRows(List<Route> routes) {
		clear();
		final Map<String, List<Route>> lines = new LinkedHashMap<>();
		routes.forEach(route -> lines.computeIfAbsent(route.getLineKey(), key -> new ArrayList<>()).add(route));

		final List<NameColorDataBase> rows = new ArrayList<>();
		lines.forEach((lineKey, lineRoutes) -> {
			final Route firstRoute = lineRoutes.get(0);
			if (lineRoutes.size() == 1) {
				rows.add(firstRoute);
			} else {
				final boolean isExpanded = expandedLines.contains(lineKey);
				final long headerId = getHeaderId(lineKey);
				headerRows.put(headerId, lineKey);
				rows.add(new DataConverter(headerId, String.format("[%s] %s (%s)", isExpanded ? "-" : "+", firstRoute.getLineName(), lineRoutes.size()), firstRoute.color));
				if (isExpanded) {
					lineRoutes.forEach(route -> childRows.add(route.id));
					rows.addAll(lineRoutes);
				}
			}
		});
		return rows;
	}

	public void clear() {
		headerRows.clear();
		childRows.clear();
	}

	public boolean isHeader(NameColorDataBase data) {
		return headerRows.containsKey(data.id);
	}

	public boolean hasHeader(long headerId) {
		return headerRows.containsKey(headerId);
	}

	public boolean isChild(NameColorDataBase data) {
		return data instanceof Route && childRows.contains(data.id);
	}

	/**
	 * @return the colour of a row. The routes of an open line show their own colours, which are otherwise that of the line.
	 */
	public int getColor(NameColorDataBase data) {
		return isChild(data) ? ((Route) data).getOwnColor() : data.color;
	}

	/**
	 * Opens or closes the line of a header row.
	 *
	 * @return true if this is a header row
	 */
	public boolean toggle(NameColorDataBase data) {
		final String lineKey = headerRows.get(data.id);
		if (lineKey == null) {
			return false;
		}
		if (!expandedLines.remove(lineKey)) {
			expandedLines.add(lineKey);
		}
		return true;
	}

	public static long getHeaderId(String lineKey) {
		return lineKey.hashCode() & 0xFFFFFFFFL | 1L << 40;
	}
}
