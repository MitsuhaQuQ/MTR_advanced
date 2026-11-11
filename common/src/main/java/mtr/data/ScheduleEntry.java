package mtr.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

public class ScheduleEntry implements Comparable<mtr.data.ScheduleEntry> {

	public final long arrivalMillis;
	public final int trainCars;
	public final long routeId;
	public final int currentStationIndex;
	public final long trainId;
	public final boolean pass;
	/**
	 * The arrival planned by the timetable, or 0 for a train without one.
	 */
	public final long scheduledMillis;
	public final boolean planned;
	/**
	 * For a train standing at the platform, when it is expected to leave, or 0 (0.67.0). A train held there longer than
	 * the platform's stop time was told as arriving now each time the arrivals were sent, so a display counting down the
	 * stop time from the arrival jumped back every few seconds; the client works the arrival out from this every tick
	 * instead ({@link #standingArrivalMillis}). Negative for a train held for something whose end isn't known: its planned
	 * departure, for displays to show but not to count down to (0.67.1).
	 */
	public final long departureMillis;
	/**
	 * On the client, for a train standing at the platform: true while more than the platform's stop time is left before it
	 * leaves, or when it is held for something whose end isn't known (0.67.3). A departure timer counting down the stop
	 * time stays blank until then, rather than standing at the full stop time. Not sent.
	 */
	public final boolean waitingToCountDown;
	// A depot estimate replaced by the duty plan on passenger displays only.
	final boolean standingForDuty;

	public ScheduleEntry(long arrivalMillis, int trainCars, long routeId, int currentStationIndex, long trainId, boolean pass) {
		this(arrivalMillis, trainCars, routeId, currentStationIndex, trainId, pass, 0);
	}

	public ScheduleEntry(long arrivalMillis, int trainCars, long routeId, int currentStationIndex, long trainId, boolean pass, long scheduledMillis) {
		this(arrivalMillis, trainCars, routeId, currentStationIndex, trainId, pass, scheduledMillis, false, false);
	}

	ScheduleEntry(long arrivalMillis, int trainCars, long routeId, int currentStationIndex, long trainId, boolean pass, long scheduledMillis, boolean planned, boolean standingForDuty) {
		this(arrivalMillis, trainCars, routeId, currentStationIndex, trainId, pass, scheduledMillis, planned, standingForDuty, 0);
	}

	ScheduleEntry(long arrivalMillis, int trainCars, long routeId, int currentStationIndex, long trainId, boolean pass, long scheduledMillis, boolean planned, boolean standingForDuty, long departureMillis) {
		this(arrivalMillis, trainCars, routeId, currentStationIndex, trainId, pass, scheduledMillis, planned, standingForDuty, departureMillis, departureMillis < 0);
	}

	private ScheduleEntry(long arrivalMillis, int trainCars, long routeId, int currentStationIndex, long trainId, boolean pass, long scheduledMillis, boolean planned, boolean standingForDuty, long departureMillis, boolean waitingToCountDown) {
		this.departureMillis = departureMillis;
		this.waitingToCountDown = waitingToCountDown;
		this.arrivalMillis = arrivalMillis;
		this.trainCars = trainCars;
		this.routeId = routeId;
		this.currentStationIndex = currentStationIndex;
		this.trainId = trainId;
		this.pass = pass;
		this.scheduledMillis = scheduledMillis;
		this.planned = planned;
		this.standingForDuty = standingForDuty;
	}

	/**
	 * @return this entry with the arrival a standing train is told by at this time: as long before its departure as the
	 * platform's stop time, or now if that is later, so a stop time counted down from it ends as the train leaves
	 */
	public ScheduleEntry atTime(long now, long platformStopMillis) {
		if (departureMillis <= 0) {
			return this;
		}
		final long arrival = standingArrivalMillis(now, departureMillis, platformStopMillis);
		final boolean waiting = now < departureMillis - platformStopMillis;
		return arrival == arrivalMillis && waiting == waitingToCountDown ? this : new ScheduleEntry(arrival, trainCars, routeId, currentStationIndex, trainId, pass, scheduledMillis, planned, standingForDuty, departureMillis, waiting);
	}

	public static long standingArrivalMillis(long now, long departureMillis, long platformStopMillis) {
		return Math.min(now, departureMillis - platformStopMillis);
	}

	/**
	 * Whether other mods reading the arrivals at a platform are given this one (0.65.31): only trains that stop there and
	 * haven't come already. They don't know that a train passes, or that a planned arrival is only a plan, which this
	 * mod's own displays tell apart, so a passing express would show as stopping and a past plan would stay on their
	 * displays.
	 */
	public static boolean shownToOtherMods(ScheduleEntry entry, long now) {
		return !entry.pass && (!entry.planned || entry.arrivalMillis > now);
	}

	/**
	 * @return the arrivals other mods are given ({@link #shownToOtherMods}), or null for none
	 */
	public static List<ScheduleEntry> forOtherMods(Collection<ScheduleEntry> entries, long now) {
		if (entries == null) {
			return null;
		}
		final List<ScheduleEntry> shown = new ArrayList<>();
		entries.forEach(entry -> {
			if (shownToOtherMods(entry, now)) {
				shown.add(entry);
			}
		});
		return shown;
	}

	public ScheduleEntry(FriendlyByteBuf packet) {
		arrivalMillis = packet.readLong();
		trainCars = packet.readInt();
		routeId = packet.readLong();
		currentStationIndex = packet.readInt();
		trainId = packet.readLong();
		pass = packet.readBoolean();
		scheduledMillis = packet.readLong();
		planned = packet.readBoolean();
		standingForDuty = false;
		departureMillis = packet.readLong();
		waitingToCountDown = departureMillis < 0;
	}

	public void writePacket(FriendlyByteBuf packet) {
		packet.writeLong(arrivalMillis);
		packet.writeInt(trainCars);
		packet.writeLong(routeId);
		packet.writeInt(currentStationIndex);
		packet.writeLong(trainId);
		packet.writeBoolean(pass);
		packet.writeLong(scheduledMillis);
		packet.writeBoolean(planned);
		packet.writeLong(departureMillis);
	}

	/**
	 * @return how many whole minutes this train is behind its timetable
	 */
	public int getDelayMinutes() {
		return scheduledMillis == 0 ? 0 : (int) Math.max(0, (arrivalMillis - scheduledMillis) / 60000);
	}

	@Override
	public int compareTo(mtr.data.ScheduleEntry o) {
		final int byTime = Long.compare(arrivalMillis, o.arrivalMillis);
		return byTime == 0 ? Long.compare(routeId, o.routeId) : byTime;
	}
}
