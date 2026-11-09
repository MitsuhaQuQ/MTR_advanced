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
	/**
	 * The pool each siding with slots was in when they were last placed there (运用补完 step 5): the duties are the pool's,
	 * the siding only where their trains start, so a siding gone or changed to another vehicle hands them on to its pool.
	 */
	public final Map<Long, String> sidingPools = new TreeMap<>();
	/**
	 * Pool → the slots of a pool left with no siding in the depot, kept rather than lost and not run, until a siding of the
	 * pool is there again.
	 */
	public final Map<String, List<Slot>> unplaced = new TreeMap<>();
	/**
	 * The slots moved to another siding of their pool or left unplaced, the latest last, for the editor to say so until a
	 * player changes the duties.
	 */
	public final List<Move> moves = new ArrayList<>();
	public static final int MAX_MOVES = 64;

	public static final int MAX_SLOTS = 256;
	public static final int MAX_DAYS = 7;
	public static final int MAX_SEGMENTS = 4096;
	public static final int ALL_WEEKDAYS = 0x7F;
	/**
	 * Saved and sent data starts with this. The first data saved, before overnight trains could pick their next duty, started
	 * with the mode, a boolean, so it reads as 0 or 1 there.
	 */
	private static final int FORMAT_VERSION = 7;

	public boolean isEmpty() {
		return sidings.values().stream().allMatch(List::isEmpty);
	}

	/**
	 * Hands each siding's slots on to its pool where the siding is gone or now in another pool (运用补完 step 5): to the
	 * pool's siding with the fewest slots, or unplaced where the pool has none left; and puts unplaced slots back once a
	 * siding of their pool is there. Sidings whose pool was never recorded, as in data saved before, are taken as they are.
	 *
	 * @param livePools each siding of the depot now and its pool
	 * @return whether any slot moved
	 */
	public boolean rehome(Map<Long, String> livePools) {
		boolean moved = false;
		for (final Long sidingId : new ArrayList<>(sidings.keySet())) {
			final List<Slot> slots = sidings.get(sidingId);
			final String livePool = livePools.get(sidingId);
			final String recorded = sidingPools.get(sidingId);
			if (livePool != null && (recorded == null || recorded.equals(livePool))) {
				continue;
			}
			// Gone with no pool recorded: unplaced under no pool, so they are not lost
			final String pool = recorded == null ? "" : recorded;
			sidings.remove(sidingId);
			sidingPools.remove(sidingId);
			place(pool, slots, sidingId, livePools);
			moved = true;
		}
		for (final String pool : new ArrayList<>(unplaced.keySet())) {
			if (findSiding(pool, livePools) != null) {
				place(pool, unplaced.remove(pool), 0, livePools);
				moved = true;
			}
		}
		// The pools as they are now, of the sidings with slots
		sidings.keySet().forEach(sidingId -> {
			final String livePool = livePools.get(sidingId);
			if (livePool != null) {
				sidingPools.put(sidingId, livePool);
			}
		});
		sidingPools.keySet().retainAll(sidings.keySet());
		return moved;
	}

	private void place(String pool, List<Slot> slots, long fromSidingId, Map<Long, String> livePools) {
		for (final Slot slot : slots) {
			final Long target = findSiding(pool, livePools);
			if (target == null) {
				unplaced.computeIfAbsent(pool, key -> new ArrayList<>()).add(slot);
			} else {
				sidings.computeIfAbsent(target, key -> new ArrayList<>()).add(slot);
				sidingPools.put(target, pool);
			}
			addMove(new Move(slot.id, fromSidingId, target == null ? 0 : target, pool));
		}
	}

	/**
	 * @return the siding of the pool with the fewest slots and room for one more, the lowest ID first, or null
	 */
	private Long findSiding(String pool, Map<Long, String> livePools) {
		Long best = null;
		for (final Map.Entry<Long, String> entry : new TreeMap<>(livePools).entrySet()) {
			final int count = getSlots(entry.getKey()).size();
			if (entry.getValue().equals(pool) && count < MAX_SLOTS && (best == null || count < getSlots(best).size())) {
				best = entry.getKey();
			}
		}
		return best;
	}

	private void addMove(Move move) {
		moves.add(move);
		while (moves.size() > MAX_MOVES) {
			moves.remove(0);
		}
	}

	public List<Slot> getSlots(long sidingId) {
		return sidings.getOrDefault(sidingId, Collections.emptyList());
	}

	/**
	 * Changes the slots of a siding: sets how many there are, then replaces the one at {@code index} when it is in range.
	 */
	public void setSlot(long sidingId, int slotCount, int index, Slot slot) {
		final int count = Math.max(0, Math.min(slotCount, MAX_SLOTS));
		// A player changing the duties has seen where they were moved to
		moves.clear();
		final List<Slot> slots = sidings.computeIfAbsent(sidingId, key -> new ArrayList<>());
		while (slots.size() > count) {
			slots.remove(slots.size() - 1);
		}
		while (slots.size() < count) {
			final Slot added = new Slot();
			added.id = defaultId(sidingId, slots.size());
			slots.add(added);
		}
		if (index >= 0 && index < count && slot != null) {
			if (slot.id == 0) {
				slot.id = defaultId(sidingId, index);
			}
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
		// Format 7 (运用补完 step 5): the sidings' pools, the unplaced slots and the moves
		packet.writeVarInt(sidingPools.size());
		sidingPools.forEach((sidingId, pool) -> {
			packet.writeLong(sidingId);
			DepotRules.writeString(packet, pool);
		});
		packet.writeVarInt(unplaced.size());
		unplaced.forEach((pool, slots) -> {
			DepotRules.writeString(packet, pool);
			packet.writeVarInt(slots.size());
			slots.forEach(slot -> slot.write(packet));
		});
		packet.writeVarInt(moves.size());
		moves.forEach(move -> {
			packet.writeLong(move.slotId);
			packet.writeLong(move.fromSidingId);
			packet.writeLong(move.toSidingId);
			DepotRules.writeString(packet, move.pool);
		});
	}

	/**
	 * @return the siding and the index of the slot with this duty number, or null if there is none
	 */
	public long[] findSlot(long id) {
		if (id == 0) {
			return null;
		}
		for (final Map.Entry<Long, List<Slot>> entry : sidings.entrySet()) {
			for (int i = 0; i < entry.getValue().size(); i++) {
				if (entry.getValue().get(i).id == id) {
					return new long[]{entry.getKey(), i};
				}
			}
		}
		return null;
	}

	/**
	 * The duty number of a slot that had none, as in data saved before duties had numbers: the same for the same siding and
	 * index, so older data reads the same every time until saved with them (P2-D).
	 */
	public static long defaultId(long sidingId, int index) {
		long value = sidingId * 0x9E3779B97F4A7C15L + (index + 1) * 0xBF58476D1CE4E5B9L;
		value ^= value >>> 31;
		value *= 0x94D049BB133111EBL;
		value ^= value >>> 29;
		return value == 0 ? 1 : value;
	}

	public void read(BinaryReader packet) {
		final int first = packet.readUnsignedByte();
		final boolean hasVersion = first >= 2 && first <= FORMAT_VERSION;
		final int version = hasVersion ? first : 1;
		diagramMode = hasVersion ? packet.readBoolean() : first != 0;
		sidings.clear();
		final int sidingCount = limit(packet.readVarInt(), 1 << 16);
		for (int i = 0; i < sidingCount; i++) {
			final long sidingId = packet.readLong();
			final int slotCount = limit(packet.readVarInt(), MAX_SLOTS);
			final List<Slot> slots = new ArrayList<>();
			for (int j = 0; j < slotCount; j++) {
				final Slot slot = Slot.read(packet, version);
				if (slot.id == 0) {
					slot.id = defaultId(sidingId, j);
				}
				slots.add(slot);
			}
			if (!slots.isEmpty()) {
				sidings.put(sidingId, slots);
			}
		}
		sidingPools.clear();
		unplaced.clear();
		moves.clear();
		if (version >= 7) {
			for (int i = limit(packet.readVarInt(), 1 << 16); i > 0; i--) {
				final long sidingId = packet.readLong();
				sidingPools.put(sidingId, DepotRules.readString(packet));
			}
			for (int i = limit(packet.readVarInt(), 1 << 16); i > 0; i--) {
				final String pool = DepotRules.readString(packet);
				final List<Slot> slots = new ArrayList<>();
				for (int j = limit(packet.readVarInt(), MAX_SLOTS * 16); j > 0; j--) {
					slots.add(Slot.read(packet, version));
				}
				if (!slots.isEmpty()) {
					unplaced.put(pool, slots);
				}
			}
			for (int i = limit(packet.readVarInt(), MAX_MOVES); i > 0; i--) {
				moves.add(new Move(packet.readLong(), packet.readLong(), packet.readLong(), DepotRules.readString(packet)));
			}
		}
	}

	/**
	 * A slot handed on to another siding of its pool, or left unplaced (to 0), as its siding was gone or changed pool; from
	 * 0 when put back from unplaced.
	 */
	public static final class Move {

		public final long slotId;
		public final long fromSidingId;
		public final long toSidingId;
		public final String pool;

		public Move(long slotId, long fromSidingId, long toSidingId, String pool) {
			this.slotId = slotId;
			this.fromSidingId = fromSidingId;
			this.toSidingId = toSidingId;
			this.pool = pool;
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
			sidingPools.clear();
			unplaced.clear();
			moves.clear();
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

		/**
		 * The duty's own number, which stays the same wherever its siding and place in the list (P2-D); 0 until given one.
		 */
		public long id;
		/**
		 * Where its train comes from (S8): {@link #START_PREFER} its siding, else another siding of its pool with a train
		 * ready; {@link #START_ONLY} its siding alone; {@link #START_ANY} whichever siding of its pool has a train ready and
		 * the quickest way out to its first stop.
		 */
		public int start = START_PREFER;
		public final List<DayDuty> days = new ArrayList<>();

		public static final int START_PREFER = 0;
		public static final int START_ONLY = 1;
		public static final int START_ANY = 2;

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
			packet.writeLong(id);
			packet.writeByte(start);
			packet.writeVarInt(days.size());
			days.forEach(day -> {
				packet.writeByte(day.weekdays);
				packet.writeBoolean(day.stayOnLine);
				packet.writeVarInt(day.nextSlot + 1);
				packet.writeLong(day.nextDutyId);
				packet.writeLong(day.returnSidingId);
				packet.writeBoolean(day.fromOvernight);
				packet.writeVarInt(day.segments.size());
				day.segments.forEach(segment -> {
					packet.writeLong(segment.routeId);
					packet.writeVarInt(segment.service);
					packet.writeVarInt(segment.fromStop);
					packet.writeVarInt(segment.toStop + 1);
				});
				day.writeItems(packet);
			});
		}

		public static Slot read(BinaryReader packet) {
			return read(packet, FORMAT_VERSION);
		}

		/**
		 * @param version 1 for data saved before overnight trains could pick their next duty, 2 before duties had numbers, 3
	 *                before they said where their train comes from, 4 before they said where trains wait and when they
	 *                leave the siding
		 */
		public static Slot read(BinaryReader packet, int version) {
			final boolean hasNextSlot = version >= 2;
			final Slot slot = new Slot();
			if (version >= 3) {
				slot.id = packet.readLong();
			}
			if (version >= 4) {
				final int start = packet.readUnsignedByte();
				slot.start = start <= START_ANY ? start : START_PREFER;
			}
			final int dayCount = limit(packet.readVarInt(), MAX_DAYS);
			int usedDays = 0;
			for (int i = 0; i < dayCount; i++) {
				final DayDuty day = new DayDuty();
				// A day belongs to one duty of the slot, the first that has it
				day.weekdays = packet.readByte() & ALL_WEEKDAYS & ~usedDays;
				usedDays |= day.weekdays;
				day.stayOnLine = packet.readBoolean();
				day.nextSlot = hasNextSlot ? Math.min(packet.readVarInt(), MAX_SLOTS) - 1 : -1;
				if (version >= 3) {
					day.nextDutyId = packet.readLong();
					day.returnSidingId = packet.readLong();
					day.fromOvernight = packet.readBoolean();
				}
				final int segmentCount = limit(packet.readVarInt(), MAX_SEGMENTS);
				for (int j = 0; j < segmentCount; j++) {
					day.segments.add(new Segment(packet.readLong(), packet.readVarInt(), packet.readVarInt(), packet.readVarInt() - 1));
				}
				if (version >= 5) {
					day.readItems(packet, version);
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
		/**
		 * For a train staying on the line, the duty it runs the next day by its number, which may be of another siding of the
		 * depot; 0 to go by {@link #nextSlot} (P2-D).
		 */
		public long nextDutyId;
		/**
		 * The siding the train goes back to after the last segment (A 出 B 入), one of its pool; 0 for the one its pool needs a
		 * train in next, as at runtime (P1-B).
		 */
		public long returnSidingId;
		/**
		 * Run by a train that stayed on the line overnight from another siding's duty ({@link #nextDutyId}), so no train
		 * leaves the siding for it and it starts on the line.
		 */
		public boolean fromOvernight;
		public final List<Segment> segments = new ArrayList<>();
		/**
		 * Where the train waits after each segment but the last for the next, as the planner had it ({@code WAIT_*}, 运用补完
		 * step 1); empty, or {@link #WAIT_ANY} for one, where the runtime decides as before.
		 */
		public final List<Integer> waits = new ArrayList<>();
		/**
		 * How long before the first service leaves its first stop the train leaves the siding, as planned; -1 for the
		 * runtime's own (the way out's time and a margin), as for duties planned before.
		 */
		public long outLeadMillis = -1;
		/**
		 * The station the train goes out by and the one it comes back by (出入口), for a depot reaching the line at more
		 * than one; 0 for the way out or back found as before.
		 */
		public long outEntrance;
		public long backEntrance;
		/**
		 * The named deadhead paths out and back ({@link DepotRules.DeadheadPath#id}), or 0 for the way found by the station
		 * nearest the depot (运用补完 step 2).
		 */
		public long outPathId;
		public long backPathId;
		/**
		 * Which of what the duty says were set for it alone ({@code EXCEPTION_*}), kept as they are when the duties are
		 * planned again; the rest follows the depot's rules.
		 */
		public int exceptions;

		public static final int EXCEPTION_OUT_LEAD = 1;
		public static final int EXCEPTION_OUT_PATH = 2;
		public static final int EXCEPTION_BACK_SIDING = 4;
		public static final int EXCEPTION_BACK_PATH = 8;
		public static final int EXCEPTION_WAITS = 16;
		public static final int EXCEPTION_ENTRANCES = 32;
		public static final int EXCEPTION_ALL = 63;

		/**
		 * The runtime decides, as before (and between the parts of a service run through).
		 */
		public static final int WAIT_ANY = 0;
		/**
		 * At the platform the segment ends at, which the next starts from.
		 */
		public static final int WAIT_PLATFORM = 1;
		/**
		 * On a turn back rail beyond the platform (混合折返), clear of the platform.
		 */
		public static final int WAIT_TURN_BACK_RAIL = 2;
		/**
		 * At the platform it arrived at, then over to another platform of the station for the next segment.
		 */
		public static final int WAIT_CHANGE_PLATFORM = 3;
		private static final int MAX_WAIT_KIND = 3;

		/**
		 * @return where the train waits after a segment, {@link #WAIT_ANY} where not planned
		 */
		public int getWait(int segment) {
			return segment >= 0 && segment < waits.size() ? waits.get(segment) : WAIT_ANY;
		}

		/**
		 * @return whether it says anything more than duties planned before did
		 */
		public boolean hasItems() {
			return outLeadMillis >= 0 || outEntrance != 0 || backEntrance != 0 || outPathId != 0 || backPathId != 0 || exceptions != 0 || waits.stream().anyMatch(wait -> wait != WAIT_ANY);
		}

		private void writeItems(BinaryWriter packet) {
			packet.writeVarInt(waits.size());
			waits.forEach(packet::writeByte);
			packet.writeLong(outLeadMillis + 1);
			packet.writeLong(outEntrance);
			packet.writeLong(backEntrance);
			packet.writeLong(outPathId);
			packet.writeLong(backPathId);
			packet.writeByte(exceptions);
		}

		private void readItems(BinaryReader packet, int version) {
			for (int i = limit(packet.readVarInt(), MAX_SEGMENTS); i > 0; i--) {
				final int wait = packet.readUnsignedByte();
				waits.add(wait <= MAX_WAIT_KIND ? wait : WAIT_ANY);
			}
			outLeadMillis = Math.max(-1, packet.readLong() - 1);
			outEntrance = packet.readLong();
			backEntrance = packet.readLong();
			if (version >= 6) {
				outPathId = packet.readLong();
				backPathId = packet.readLong();
				exceptions = packet.readUnsignedByte() & EXCEPTION_ALL;
			}
		}

		/**
		 * Keeps what another duty set as exceptions, in place of what this one has, where the other has as many services.
		 */
		public void keepExceptions(DayDuty other) {
			exceptions |= other.exceptions;
			if ((other.exceptions & EXCEPTION_OUT_LEAD) != 0) {
				outLeadMillis = other.outLeadMillis;
			}
			if ((other.exceptions & EXCEPTION_OUT_PATH) != 0) {
				outPathId = other.outPathId;
			}
			if ((other.exceptions & EXCEPTION_BACK_SIDING) != 0) {
				returnSidingId = other.returnSidingId;
			}
			if ((other.exceptions & EXCEPTION_BACK_PATH) != 0) {
				backPathId = other.backPathId;
			}
			if ((other.exceptions & EXCEPTION_ENTRANCES) != 0) {
				outEntrance = other.outEntrance;
				backEntrance = other.backEntrance;
			}
			if ((other.exceptions & EXCEPTION_WAITS) != 0) {
				if (other.waits.size() == waits.size()) {
					waits.clear();
					waits.addAll(other.waits);
				} else {
					exceptions &= ~EXCEPTION_WAITS;
				}
			}
		}

		/**
		 * @return whether another day's duty is the same in all but its weekdays
		 */
		public boolean sameAs(DayDuty other) {
			return stayOnLine == other.stayOnLine && segments.equals(other.segments) && waits.equals(other.waits) && outLeadMillis == other.outLeadMillis && outEntrance == other.outEntrance && backEntrance == other.backEntrance && outPathId == other.outPathId && backPathId == other.backPathId && exceptions == other.exceptions;
		}

		/**
		 * @return a copy that later edits of the duties don't change, sharing the segments, which never change
		 */
		public DayDuty copy() {
			final DayDuty copy = new DayDuty();
			copy.weekdays = weekdays;
			copy.stayOnLine = stayOnLine;
			copy.nextSlot = nextSlot;
			copy.nextDutyId = nextDutyId;
			copy.returnSidingId = returnSidingId;
			copy.fromOvernight = fromOvernight;
			copy.segments.addAll(segments);
			copy.waits.addAll(waits);
			copy.outLeadMillis = outLeadMillis;
			copy.outEntrance = outEntrance;
			copy.backEntrance = backEntrance;
			copy.outPathId = outPathId;
			copy.backPathId = backPathId;
			copy.exceptions = exceptions;
			return copy;
		}

		/**
		 * @return the duty as text, for a train to keep the duty it took with it when saved (decoupling document, step D)
		 */
		public String toText() {
			final StringBuilder builder = new StringBuilder().append(weekdays).append(',').append(stayOnLine ? 1 : 0).append(',').append(nextSlot).append(',').append(nextDutyId).append(',').append(returnSidingId).append(',').append(fromOvernight ? 1 : 0);
			// Where it waits and when it leaves, only when planned, so duties as before write as before
			if (hasItems()) {
				builder.append(',').append(outLeadMillis).append(',').append(outEntrance).append(',').append(backEntrance).append(',').append('w');
				waits.forEach(builder::append);
				builder.append(',').append(outPathId).append(',').append(backPathId).append(',').append(exceptions);
			}
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
				// Written before duties had numbers: none
				if (head.length >= 6) {
					day.nextDutyId = Long.parseLong(head[3]);
					day.returnSidingId = Long.parseLong(head[4]);
					day.fromOvernight = Integer.parseInt(head[5]) != 0;
				}
				if (head.length >= 10) {
					day.outLeadMillis = Long.parseLong(head[6]);
					day.outEntrance = Long.parseLong(head[7]);
					day.backEntrance = Long.parseLong(head[8]);
					for (int i = 1; i < head[9].length(); i++) {
						final int wait = head[9].charAt(i) - '0';
						day.waits.add(wait >= 0 && wait <= MAX_WAIT_KIND ? wait : WAIT_ANY);
					}
				}
				if (head.length >= 13) {
					day.outPathId = Long.parseLong(head[10]);
					day.backPathId = Long.parseLong(head[11]);
					day.exceptions = Integer.parseInt(head[12]) & EXCEPTION_ALL;
				}
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
