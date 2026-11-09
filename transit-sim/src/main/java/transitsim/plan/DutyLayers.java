package transitsim.plan;

import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;
import transitsim.io.ByteArrayBinary;

import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongFunction;

/**
 * Duties as three layers referring to each other by content (duty groups, 运用组): the body of a duty, which services a
 * train runs in a day and whether it stays on the line, with no depot or siding in it; a group, the bodies one pool of
 * vehicles runs on the weekdays of one timetable, in order; and the assignment of a body of a group to a slot of a siding for some weekdays, with all that
 * depends on the depot: which duty number it is, where its train comes from and goes back to, and which duty it runs
 * the next day after staying out.
 * <p>
 * Bodies and groups are keyed by a hash of their content, so the same body or group is kept once however many depots,
 * sidings or weekdays use it: two timetables with the same duties are one group, and a depot running another's duties
 * as a template only adds assignments. The layers turn back into {@link DepotDuties} exactly as they were made from it.
 */
public final class DutyLayers {

	public boolean diagramMode;
	/**
	 * Each body and group by its key.
	 */
	public final Map<Long, Body> bodies = new TreeMap<>();
	public final Map<Long, Group> groups = new TreeMap<>();
	/**
	 * In the order of the duties: by siding, by slot, by the slot's weekday groups.
	 */
	public final List<Assignment> assignments = new ArrayList<>();

	private static final int FORMAT_VERSION = 3;

	/**
	 * Splits duties into layers.
	 *
	 * @param poolOf the pool of vehicles of a siding, which a group belongs to; sidings of a pool share their groups
	 */
	public static DutyLayers of(DepotDuties duties, LongFunction<String> poolOf) {
		final DutyLayers layers = new DutyLayers();
		layers.diagramMode = duties.diagramMode;
		// The bodies of each pool on each set of weekdays in the order they come, and where each duty is among them
		final Map<String, List<Long>> groupBodies = new TreeMap<>();
		final Map<String, String> groupPools = new HashMap<>();
		final List<Object[]> placed = new ArrayList<>();
		duties.sidings.forEach((sidingId, slots) -> {
			final String pool = poolOf.apply(sidingId);
			final String poolKey = pool == null ? "" : pool;
			for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
				final DepotDuties.Slot slot = slots.get(slotIndex);
				if (slot.days.isEmpty()) {
					// A slot with no duty keeps its place and number
					placed.add(new Object[]{sidingId, slotIndex, slot, null, null, -1});
				}
				for (final DepotDuties.DayDuty day : slot.days) {
					final Body body = new Body(day.stayOnLine, day.segments, day.waits);
					layers.bodies.putIfAbsent(body.key, body);
					final String groupName = poolKey + "|" + day.weekdays;
					groupPools.put(groupName, poolKey);
					final List<Long> list = groupBodies.computeIfAbsent(groupName, key -> new ArrayList<>());
					list.add(body.key);
					placed.add(new Object[]{sidingId, slotIndex, slot, day, groupName, list.size() - 1});
				}
			}
		});
		// The same bodies on other weekdays are the same group, kept once
		final Map<String, Group> namedGroups = new HashMap<>();
		groupBodies.forEach((name, list) -> {
			final Group group = new Group(groupPools.get(name), list);
			layers.groups.putIfAbsent(group.key, group);
			namedGroups.put(name, group);
		});
		for (final Object[] entry : placed) {
			final DepotDuties.Slot slot = (DepotDuties.Slot) entry[2];
			final DepotDuties.DayDuty day = (DepotDuties.DayDuty) entry[3];
			final Assignment assignment = new Assignment();
			assignment.sidingId = (Long) entry[0];
			assignment.slotIndex = (Integer) entry[1];
			assignment.dutyId = slot.id;
			assignment.start = slot.start;
			assignment.bodyIndex = (Integer) entry[5];
			layers.assignments.add(assignment);
			if (day == null) {
				continue;
			}
			assignment.weekdays = day.weekdays;
			assignment.groupKey = namedGroups.get((String) entry[4]).key;
			assignment.nextSlot = day.nextSlot;
			assignment.nextDutyId = day.nextDutyId;
			assignment.returnSidingId = day.returnSidingId;
			assignment.fromOvernight = day.fromOvernight;
			assignment.outLeadMillis = day.outLeadMillis;
			assignment.outEntrance = day.outEntrance;
			assignment.backEntrance = day.backEntrance;
			assignment.outPathId = day.outPathId;
			assignment.backPathId = day.backPathId;
			assignment.exceptions = day.exceptions;
		}
		return layers;
	}

	/**
	 * @return the duties the layers stand for, as they were made from; null if an assignment refers to something missing
	 */
	public DepotDuties toDuties() {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = diagramMode;
		for (final Assignment assignment : assignments) {
			final List<DepotDuties.Slot> slots = duties.sidings.computeIfAbsent(assignment.sidingId, key -> new ArrayList<>());
			while (slots.size() <= assignment.slotIndex) {
				slots.add(new DepotDuties.Slot());
			}
			final DepotDuties.Slot slot = slots.get(assignment.slotIndex);
			slot.id = assignment.dutyId;
			slot.start = assignment.start;
			if (assignment.isEmptySlot()) {
				continue;
			}
			final Group group = groups.get(assignment.groupKey);
			final Body body = group == null || assignment.bodyIndex >= group.bodies.size() ? null : bodies.get(group.bodies.get(assignment.bodyIndex));
			if (body == null) {
				return null;
			}
			final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
			day.weekdays = assignment.weekdays;
			day.stayOnLine = body.stayOnLine;
			day.nextSlot = assignment.nextSlot;
			day.nextDutyId = assignment.nextDutyId;
			day.returnSidingId = assignment.returnSidingId;
			day.fromOvernight = assignment.fromOvernight;
			day.segments.addAll(body.segments);
			day.waits.addAll(body.waits);
			day.outLeadMillis = assignment.outLeadMillis;
			day.outEntrance = assignment.outEntrance;
			day.backEntrance = assignment.backEntrance;
			day.outPathId = assignment.outPathId;
			day.backPathId = assignment.backPathId;
			day.exceptions = assignment.exceptions;
			slot.days.add(day);
		}
		return duties;
	}

	/**
	 * @return the groups the assignments of these sidings refer to, by key
	 */
	public Set<Long> getGroupKeys(Collection<Long> sidingIds) {
		final Set<Long> keys = new TreeSet<>();
		assignments.forEach(assignment -> {
			if (sidingIds.contains(assignment.sidingId)) {
				keys.add(assignment.groupKey);
			}
		});
		return keys;
	}

	public byte[] toBytes() {
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		writer.writeByte(FORMAT_VERSION);
		writer.writeBoolean(diagramMode);
		writer.writeVarInt(bodies.size());
		bodies.values().forEach(body -> body.write(writer));
		writer.writeVarInt(groups.size());
		groups.values().forEach(group -> group.write(writer));
		writer.writeVarInt(assignments.size());
		assignments.forEach(assignment -> assignment.write(writer));
		return writer.toBytes();
	}

	/**
	 * @return the layers {@link #toBytes} wrote, or null if they don't read or a key doesn't match its content
	 */
	public static DutyLayers fromBytes(byte[] bytes) {
		try {
			final ByteArrayBinary.Reader reader = new ByteArrayBinary.Reader(bytes);
			final int version = reader.readUnsignedByte();
			if (version < 1 || version > FORMAT_VERSION) {
				return null;
			}
			final DutyLayers layers = new DutyLayers();
			layers.diagramMode = reader.readBoolean();
			for (int i = limit(reader.readVarInt()); i > 0; i--) {
				final Body body = Body.read(reader, version);
				layers.bodies.put(body.key, body);
			}
			for (int i = limit(reader.readVarInt()); i > 0; i--) {
				final Group group = Group.read(reader);
				layers.groups.put(group.key, group);
			}
			for (int i = limit(reader.readVarInt()); i > 0; i--) {
				layers.assignments.add(Assignment.read(reader, version));
			}
			return layers;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void writeString(BinaryWriter writer, String text) {
		final byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		writer.writeVarInt(bytes.length);
		for (final byte value : bytes) {
			writer.writeByte(value);
		}
	}

	private static String readString(BinaryReader reader) {
		final int length = reader.readVarInt();
		if (length < 0 || length > 4096) {
			throw new IllegalArgumentException("Pool name too long: " + length);
		}
		final byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) {
			bytes[i] = reader.readByte();
		}
		return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
	}

	private static int limit(int count) {
		if (count < 0 || count > 1 << 20) {
			throw new IllegalArgumentException("Too many duty entries: " + count);
		}
		return count;
	}

	/**
	 * @return the first 8 bytes of the SHA-256 of what {@code write} writes
	 */
	static long keyOf(java.util.function.Consumer<BinaryWriter> write) {
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		write.accept(writer);
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(writer.toBytes());
			long key = 0;
			for (int i = 0; i < 8; i++) {
				key = key << 8 | digest[i] & 0xFF;
			}
			return key;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The services a train runs in a day, in order, where it waits between them, and whether it stays on the line after the
	 * last: no depot, siding or weekday in it.
	 */
	public static final class Body {

		public final long key;
		public final boolean stayOnLine;
		public final List<DepotDuties.Segment> segments;
		/**
		 * Where the train waits after each segment ({@link DepotDuties.DayDuty#waits}), empty where the runtime decides.
		 */
		public final List<Integer> waits;

		public Body(boolean stayOnLine, List<DepotDuties.Segment> segments) {
			this(stayOnLine, segments, Collections.emptyList());
		}

		public Body(boolean stayOnLine, List<DepotDuties.Segment> segments, List<Integer> waits) {
			this.stayOnLine = stayOnLine;
			this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
			this.waits = Collections.unmodifiableList(new ArrayList<>(waits));
			key = keyOf(this::writeContent);
		}

		/**
		 * The content the key is of: a body with no waits keys as it did before they were kept, so duties planned before
		 * are the same bodies.
		 */
		private void writeContent(BinaryWriter writer) {
			writeSegments(writer);
			if (!waits.isEmpty()) {
				writer.writeVarInt(waits.size());
				waits.forEach(writer::writeByte);
			}
		}

		private void writeSegments(BinaryWriter writer) {
			writer.writeBoolean(stayOnLine);
			writer.writeVarInt(segments.size());
			segments.forEach(segment -> {
				writer.writeLong(segment.routeId);
				writer.writeVarInt(segment.service);
				writer.writeVarInt(segment.fromStop);
				writer.writeVarInt(segment.toStop + 1);
			});
		}

		private void write(BinaryWriter writer) {
			writeSegments(writer);
			writer.writeVarInt(waits.size());
			waits.forEach(writer::writeByte);
		}

		/**
		 * @return the body as stored on its own, as in a table of bodies
		 */
		public byte[] toBytes() {
			final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
			write(writer);
			return writer.toBytes();
		}

		private static Body read(BinaryReader reader, int version) {
			final boolean stayOnLine = reader.readBoolean();
			final List<DepotDuties.Segment> segments = new ArrayList<>();
			for (int i = Math.min(reader.readVarInt(), DepotDuties.MAX_SEGMENTS); i > 0; i--) {
				segments.add(new DepotDuties.Segment(reader.readLong(), reader.readVarInt(), reader.readVarInt(), reader.readVarInt() - 1));
			}
			final List<Integer> waits = new ArrayList<>();
			if (version >= 2) {
				for (int i = Math.min(reader.readVarInt(), DepotDuties.MAX_SEGMENTS); i > 0; i--) {
					waits.add(reader.readUnsignedByte());
				}
			}
			return new Body(stayOnLine, segments, waits);
		}
	}

	/**
	 * The bodies of the duties of one pool of vehicles, in order, by key.
	 */
	public static final class Group {

		public final long key;
		public final String pool;
		public final List<Long> bodies;

		public Group(String pool, List<Long> bodies) {
			this.pool = pool;
			this.bodies = Collections.unmodifiableList(new ArrayList<>(bodies));
			key = keyOf(this::write);
		}

		private void write(BinaryWriter writer) {
			writeString(writer, pool);
			writer.writeVarInt(bodies.size());
			bodies.forEach(writer::writeLong);
		}

		/**
		 * @return the group as stored on its own, as in a table of groups
		 */
		public byte[] toBytes() {
			final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
			write(writer);
			return writer.toBytes();
		}

		private static Group read(BinaryReader reader) {
			final String pool = readString(reader);
			final List<Long> bodies = new ArrayList<>();
			for (int i = limit(reader.readVarInt()); i > 0; i--) {
				bodies.add(reader.readLong());
			}
			return new Group(pool, bodies);
		}
	}

	/**
	 * A body of a group run by a slot of a siding on some weekdays, with what depends on the depot.
	 */
	public static final class Assignment {

		public long sidingId;
		public int slotIndex;
		/**
		 * The slot's duty number ({@link DepotDuties.Slot#id}) and where its train comes from.
		 */
		public long dutyId;
		public int start;
		public int weekdays;
		public long groupKey;
		public int bodyIndex;
		public int nextSlot = -1;
		public long nextDutyId;
		public long returnSidingId;
		public boolean fromOvernight;
		/**
		 * When the train leaves the siding before its first service, and the stations it goes out and back by
		 * ({@link DepotDuties.DayDuty#outLeadMillis}): what depends on the depot of the way out and back.
		 */
		public long outLeadMillis = -1;
		public long outEntrance;
		public long backEntrance;
		/**
		 * The named deadhead paths out and back, and which of what depends on the depot was set for this duty alone
		 * ({@link DepotDuties.DayDuty#exceptions}).
		 */
		public long outPathId;
		public long backPathId;
		public int exceptions;

		/**
		 * @return whether it only keeps the place and number of a slot with no duty
		 */
		public boolean isEmptySlot() {
			return bodyIndex < 0;
		}

		private void write(BinaryWriter writer) {
			writer.writeLong(sidingId);
			writer.writeVarInt(slotIndex);
			writer.writeLong(dutyId);
			writer.writeByte(start);
			writer.writeByte(weekdays);
			writer.writeLong(groupKey);
			writer.writeVarInt(bodyIndex + 1);
			writer.writeVarInt(nextSlot + 1);
			writer.writeLong(nextDutyId);
			writer.writeLong(returnSidingId);
			writer.writeBoolean(fromOvernight);
			writer.writeLong(outLeadMillis + 1);
			writer.writeLong(outEntrance);
			writer.writeLong(backEntrance);
			writer.writeLong(outPathId);
			writer.writeLong(backPathId);
			writer.writeByte(exceptions);
		}

		/**
		 * @return the assignment as stored on its own, as in a table of assignments
		 */
		public byte[] toBytes() {
			final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
			write(writer);
			return writer.toBytes();
		}

		/**
		 * @return the key of the body it runs, or 0 for a slot with no duty or a group not among these
		 */
		public long getBodyKey(DutyLayers layers) {
			final Group group = layers.groups.get(groupKey);
			return isEmptySlot() || group == null || bodyIndex >= group.bodies.size() ? 0 : group.bodies.get(bodyIndex);
		}

		private static Assignment read(BinaryReader reader, int version) {
			final Assignment assignment = new Assignment();
			assignment.sidingId = reader.readLong();
			assignment.slotIndex = Math.min(reader.readVarInt(), DepotDuties.MAX_SLOTS - 1);
			assignment.dutyId = reader.readLong();
			assignment.start = reader.readUnsignedByte();
			assignment.weekdays = reader.readUnsignedByte() & DepotDuties.ALL_WEEKDAYS;
			assignment.groupKey = reader.readLong();
			assignment.bodyIndex = reader.readVarInt() - 1;
			assignment.nextSlot = Math.min(reader.readVarInt(), DepotDuties.MAX_SLOTS) - 1;
			assignment.nextDutyId = reader.readLong();
			assignment.returnSidingId = reader.readLong();
			assignment.fromOvernight = reader.readBoolean();
			if (version >= 2) {
				assignment.outLeadMillis = Math.max(-1, reader.readLong() - 1);
				assignment.outEntrance = reader.readLong();
				assignment.backEntrance = reader.readLong();
			}
			if (version >= 3) {
				assignment.outPathId = reader.readLong();
				assignment.backPathId = reader.readLong();
				assignment.exceptions = reader.readUnsignedByte() & DepotDuties.DayDuty.EXCEPTION_ALL;
			}
			return assignment;
		}
	}
}
