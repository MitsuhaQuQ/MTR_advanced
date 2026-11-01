package mtr.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One timetable service of a route. Stop offsets are measured from the planned arrival at the first stop.
 */
public final class TimetableService {

	public final long id;
	public final long routeId;
	public final int daysMask;
	public final long firstArrivalMillis;
	public final boolean blank;
	public final List<Stop> stops;

	public TimetableService(long id, long routeId, int daysMask, long firstArrivalMillis, boolean blank, List<Stop> stops) {
		if (stops.isEmpty()) {
			throw new IllegalArgumentException("A service needs at least one stop");
		}
		this.id = id;
		this.routeId = routeId;
		this.daysMask = daysMask;
		this.firstArrivalMillis = firstArrivalMillis;
		this.blank = blank;
		this.stops = Collections.unmodifiableList(new ArrayList<>(stops));
	}

	public boolean runsOn(int day) {
		return (daysMask >> day & 1) != 0;
	}

	public static final class Stop {

		public final long platformId;
		public final long arrivalOffsetMillis;
		public final long departureOffsetMillis;
		public final boolean pass;

		public Stop(long platformId, long arrivalOffsetMillis, long departureOffsetMillis, boolean pass) {
			this.platformId = platformId;
			this.arrivalOffsetMillis = arrivalOffsetMillis;
			this.departureOffsetMillis = departureOffsetMillis;
			this.pass = pass;
		}
	}

	/**
	 * A through trip made of several services, which may be blank ones reserved for this trip.
	 */
	public static final class Through {

		public final long id;
		public final int daysMask;
		public final List<Long> serviceIds;

		public Through(long id, int daysMask, List<Long> serviceIds) {
			if (serviceIds.size() < 2) {
				throw new IllegalArgumentException("A through trip needs at least two services");
			}
			this.id = id;
			this.daysMask = daysMask;
			this.serviceIds = Collections.unmodifiableList(new ArrayList<>(serviceIds));
		}
	}
}
