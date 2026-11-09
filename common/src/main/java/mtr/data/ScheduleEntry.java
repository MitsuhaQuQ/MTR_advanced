package mtr.data;

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
	// A depot estimate replaced by the duty plan on passenger displays only.
	final boolean standingForDuty;

	public ScheduleEntry(long arrivalMillis, int trainCars, long routeId, int currentStationIndex, long trainId, boolean pass) {
		this(arrivalMillis, trainCars, routeId, currentStationIndex, trainId, pass, 0);
	}

	public ScheduleEntry(long arrivalMillis, int trainCars, long routeId, int currentStationIndex, long trainId, boolean pass, long scheduledMillis) {
		this(arrivalMillis, trainCars, routeId, currentStationIndex, trainId, pass, scheduledMillis, false, false);
	}

	ScheduleEntry(long arrivalMillis, int trainCars, long routeId, int currentStationIndex, long trainId, boolean pass, long scheduledMillis, boolean planned, boolean standingForDuty) {
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
