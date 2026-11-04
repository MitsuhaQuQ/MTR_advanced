package transitsim.plan;

import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;
import transitsim.io.ByteArrayBinary;

import java.util.*;

/**
 * The duties (运用) of a depot's trains, from the advanced editor (design document 6.12, block E). Each siding has a row
 * of slots (库位), one per train, and each slot has a duty for each group of weekdays that run the same tables: the
 * services it runs in order, and whether the train goes back to the depot after the last one or stays on the line.
 * <p>
 * In E1 the duties are only kept and edited; trains still pick their services online until a depot runs by its duties.
 */
public final class DepotDuties {

	/**
	 * Whether the depot runs its trains by these duties (按运用图) rather than picking services online (在线预约).
	 */
	public boolean diagramMode;
	/**
	 * Siding ID → its slots, in order.
	 */
	public final Map<Long, List<Slot>> sidings = new TreeMap<>();

	public static final int MAX_SLOTS = 256;
	public static final int MAX_DAYS = 7;
	public static final int MAX_SEGMENTS = 128;
	public static final int ALL_WEEKDAYS = 0x7F;
	/**
	 * Saved and sent data starts with this. The first data saved, before overnight trains could pick their next duty, started
	 * with the mode, a boolean, so it reads as 0 or 1 there.
	 */
	private static final int FORMAT_VERSION = 2;

	public boolean isEmpty() {
		return sidings.values().stream().allMatch(List::isEmpty);
	}

	public List<Slot> getSlots(long sidingId) {
		return sidings.getOrDefault(sidingId, Collections.emptyList());
	}

	/**
	 * Changes the slots of a siding: sets how many there are, then replaces the one at {@code index} when it is in range.
	 */
	public void setSlot(long sidingId, int slotCount, int index, Slot slot) {
		final int count = Math.max(0, Math.min(slotCount, MAX_SLOTS));
		final List<Slot> slots = sidings.computeIfAbsent(sidingId, key -> new ArrayList<>());
		while (slots.size() > count) {
			slots.remove(slots.size() - 1);
		}
		while (slots.size() < count) {
			slots.add(new Slot());
		}
		if (index >= 0 && index < count && slot != null) {
			slots.set(index, slot);
		}
		if (slots.isEmpty()) {
			sidings.remove(sidingId);
		}
	}

	public void write(BinaryWriter packet) {
		packet.writeByte(FORMAT_VERSION);
		packet.writeBoolean(diagramMode);
		packet.writeVarInt(sidings.size());
		sidings.forEach((sidingId, slots) -> {
			packet.writeLong(sidingId);
			packet.writeVarInt(slots.size());
			slots.forEach(slot -> slot.write(packet));
		});
	}

	public void read(BinaryReader packet) {
		final int first = packet.readUnsignedByte();
		final boolean hasVersion = first >= FORMAT_VERSION;
		diagramMode = hasVersion ? packet.readBoolean() : first != 0;
		sidings.clear();
		final int sidingCount = limit(packet.readVarInt(), 1 << 16);
		for (int i = 0; i < sidingCount; i++) {
			final long sidingId = packet.readLong();
			final int slotCount = limit(packet.readVarInt(), MAX_SLOTS);
			final List<Slot> slots = new ArrayList<>();
			for (int j = 0; j < slotCount; j++) {
				slots.add(Slot.read(packet, hasVersion));
			}
			if (!slots.isEmpty()) {
				sidings.put(sidingId, slots);
			}
		}
	}

	public byte[] toBytes() {
		final ByteArrayBinary.Writer packet = new ByteArrayBinary.Writer();
		write(packet);
		return packet.toBytes();
	}

	public void fromBytes(byte[] bytes) {
		try {
			read(new ByteArrayBinary.Reader(bytes));
		} catch (Exception e) {
			e.printStackTrace();
			diagramMode = false;
			sidings.clear();
		}
	}

	private static int limit(int count, int max) {
		if (count < 0 || count > max) {
			throw new IllegalArgumentException("Too many duty entries: " + count);
		}
		return count;
	}

	/**
	 * One train of a siding, with a duty for each group of weekdays. The groups don't overlap.
	 */
	public static final class Slot {

		public final List<DayDuty> days = new ArrayList<>();

		/**
		 * @param weekday 0 for Monday
		 * @return the duty for a weekday, or null if the train doesn't run that day
		 */
		public DayDuty getDuty(int weekday) {
			for (final DayDuty day : days) {
				if ((day.weekdays >> weekday & 1) != 0) {
					return day;
				}
			}
			return null;
		}

		public void write(BinaryWriter packet) {
			packet.writeVarInt(days.size());
			days.forEach(day -> {
				packet.writeByte(day.weekdays);
				packet.writeBoolean(day.stayOnLine);
				packet.writeVarInt(day.nextSlot + 1);
				packet.writeVarInt(day.segments.size());
				day.segments.forEach(segment -> {
					packet.writeLong(segment.routeId);
					packet.writeVarInt(segment.service);
					packet.writeVarInt(segment.fromStop);
					packet.writeVarInt(segment.toStop + 1);
				});
			});
		}

		public static Slot read(BinaryReader packet) {
			return read(packet, true);
		}

		/**
		 * @param hasNextSlot false for data saved before overnight trains could pick their next duty
		 */
		public static Slot read(BinaryReader packet, boolean hasNextSlot) {
			final Slot slot = new Slot();
			final int dayCount = limit(packet.readVarInt(), MAX_DAYS);
			int usedDays = 0;
			for (int i = 0; i < dayCount; i++) {
				final DayDuty day = new DayDuty();
				// A day belongs to one duty of the slot, the first that has it
				day.weekdays = packet.readByte() & ALL_WEEKDAYS & ~usedDays;
				usedDays |= day.weekdays;
				day.stayOnLine = packet.readBoolean();
				day.nextSlot = hasNextSlot ? Math.min(packet.readVarInt(), MAX_SLOTS) - 1 : -1;
				final int segmentCount = limit(packet.readVarInt(), MAX_SEGMENTS);
				for (int j = 0; j < segmentCount; j++) {
					day.segments.add(new Segment(packet.readLong(), packet.readVarInt(), packet.readVarInt(), packet.readVarInt() - 1));
				}
				if (day.weekdays != 0) {
					slot.days.add(day);
				}
			}
			return slot;
		}
	}

	/**
	 * What a train does on the weekdays of {@link #weekdays} (bit 0 is Monday).
	 */
	public static final class DayDuty {

		public int weekdays;
		/**
		 * After the last segment the train stays on the line overnight, and the next day's duty carries on from there.
		 */
		public boolean stayOnLine;
		/**
		 * For a train staying on the line, the slot of the same siding whose next day's duty it runs, or -1 for its own.
		 */
		public int nextSlot = -1;
		public final List<Segment> segments = new ArrayList<>();

		/**
		 * @return a copy that later edits of the duties don't change, sharing the segments, which never change
		 */
		public DayDuty copy() {
			final DayDuty copy = new DayDuty();
			copy.weekdays = weekdays;
			copy.stayOnLine = stayOnLine;
			copy.nextSlot = nextSlot;
			copy.segments.addAll(segments);
			return copy;
		}

		/**
		 * @return the duty as text, for a train to keep the duty it took with it when saved (decoupling document, step D)
		 */
		public String toText() {
			final StringBuilder builder = new StringBuilder().append(weekdays).append(',').append(stayOnLine ? 1 : 0).append(',').append(nextSlot);
			segments.forEach(segment -> builder.append(';').append(segment.routeId).append(',').append(segment.service).append(',').append(segment.fromStop).append(',').append(segment.toStop));
			return builder.toString();
		}

		/**
		 * @return the duty {@link #toText} wrote, or null if the text isn't one
		 */
		public static DayDuty fromText(String text) {
			if (text == null || text.isEmpty()) {
				return null;
			}
			try {
				final String[] parts = text.split(";");
				final String[] head = parts[0].split(",");
				final DayDuty day = new DayDuty();
				day.weekdays = Integer.parseInt(head[0]);
				day.stayOnLine = Integer.parseInt(head[1]) != 0;
				day.nextSlot = Integer.parseInt(head[2]);
				for (int i = 1; i < parts.length; i++) {
					final String[] values = parts[i].split(",");
					day.segments.add(new Segment(Long.parseLong(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2]), Integer.parseInt(values[3])));
				}
				return day;
			} catch (RuntimeException e) {
				return null;
			}
		}
	}

	/**
	 * A service of a route, run from one stop to another. A train joining or leaving part of the way along follows the
	 * route's through links ({@link RouteTimetable.ServiceLink}).
	 */
	public static final class Segment {

		public final long routeId;
		/**
		 * The service, as UTC milliseconds of the day like {@link RouteTimetable#services}.
		 */
		public final int service;
		/**
		 * The stop the train joins at, 0 for the first.
		 */
		public final int fromStop;
		/**
		 * The stop the train leaves at, or -1 for the last.
		 */
		public final int toStop;

		public Segment(long routeId, int service, int fromStop, int toStop) {
			this.routeId = routeId;
			this.service = service;
			this.fromStop = Math.max(0, fromStop);
			this.toStop = Math.max(-1, toStop);
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof Segment)) {
				return false;
			}
			final Segment segment = (Segment) object;
			return routeId == segment.routeId && service == segment.service && fromStop == segment.fromStop && toStop == segment.toStop;
		}

		@Override
		public int hashCode() {
			return Objects.hash(routeId, service, fromStop, toStop);
		}
	}
}
