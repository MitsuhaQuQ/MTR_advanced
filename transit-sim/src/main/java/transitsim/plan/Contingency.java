package transitsim.plan;

import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A depot's plan for trains running late or stuck (故障预案, D2): which of its routes it is for, what makes a train fall
 * under it, whether it starts by itself or waits for a dispatcher to start it, and what is done with such a train: turn
 * it back early at the nearest station that can or at a given one, pass some stations without stopping, and have a train
 * from the depot take over the rest of its duty. The same is used for what a dispatcher sets for one train there and then.
 */
public final class Contingency {

	public static final int TURN_NONE = 0;
	public static final int TURN_NEAREST = 1;
	public static final int TURN_STATION = 2;
	/**
	 * After turning back, empty, the train runs on its duty again from the other platform.
	 */
	public static final int AFTER_RESUME = 0;
	/**
	 * After turning back, empty, the train runs back to its depot out of service.
	 */
	public static final int AFTER_DEADHEAD = 1;
	public static final int MAX_ROUTES = 64;
	public static final int MAX_STATIONS = 64;
	public static final int MAX_NAME = 64;
	public static final int MAX_MINUTES = 24 * 60;
	private static final int VERSION = 1;
	private static final long MINUTE = 60000;

	public long id;
	public String name = "";
	/**
	 * The routes it is for, or none for every route of the depot.
	 */
	public final Set<Long> routeIds = new TreeSet<>();
	/**
	 * Late by this many minutes or more, or 0 not to go by lateness.
	 */
	public int lateMinutes = 5;
	/**
	 * Waiting this many minutes or more for its way on, or 0 not to go by waiting.
	 */
	public int waitMinutes = 10;
	/**
	 * In a circle of trains waiting on each other.
	 */
	public boolean deadlock = true;
	/**
	 * Waits for a dispatcher to start it, only telling them; otherwise it starts by itself.
	 */
	public boolean manual;
	public int turn = TURN_NONE;
	public long turnStationId;
	public int afterTurn = AFTER_RESUME;
	/**
	 * Stations passed without stopping.
	 */
	public final Set<Long> skipStationIds = new TreeSet<>();
	/**
	 * A train from the depot takes over the rest of the duty.
	 */
	public boolean takeover;

	public boolean appliesTo(long routeId) {
		return routeIds.isEmpty() || routeIds.contains(routeId);
	}

	/**
	 * @return what makes a train with these fall under this plan, the gravest first, or null if nothing does
	 */
	public Reason getReason(long lateMillis, long waitingMillis, boolean deadlocked) {
		if (deadlock && deadlocked) {
			return Reason.DEADLOCK;
		}
		if (waitMinutes > 0 && waitingMillis >= waitMinutes * MINUTE) {
			return Reason.WAITING;
		}
		if (lateMinutes > 0 && lateMillis >= lateMinutes * MINUTE) {
			return Reason.LATE;
		}
		return null;
	}

	public void write(BinaryWriter writer) {
		writer.writeVarInt(VERSION);
		writer.writeLong(id);
		final byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		writer.writeVarInt(nameBytes.length);
		for (final byte b : nameBytes) {
			writer.writeByte(b);
		}
		writeIds(writer, routeIds);
		writer.writeVarInt(lateMinutes);
		writer.writeVarInt(waitMinutes);
		writer.writeBoolean(deadlock);
		writer.writeBoolean(manual);
		writer.writeVarInt(turn);
		writer.writeLong(turnStationId);
		writer.writeVarInt(afterTurn);
		writeIds(writer, skipStationIds);
		writer.writeBoolean(takeover);
	}

	public static Contingency read(BinaryReader reader) {
		final Contingency contingency = new Contingency();
		reader.readVarInt();
		contingency.id = reader.readLong();
		final int nameLength = reader.readVarInt();
		if (nameLength < 0 || nameLength > MAX_NAME * 4) {
			throw new IllegalArgumentException("Name too long: " + nameLength);
		}
		final byte[] nameBytes = new byte[nameLength];
		for (int i = 0; i < nameLength; i++) {
			nameBytes[i] = reader.readByte();
		}
		contingency.name = limitName(new String(nameBytes, StandardCharsets.UTF_8));
		readIds(reader, contingency.routeIds, MAX_ROUTES);
		contingency.lateMinutes = clamp(reader.readVarInt(), 0, MAX_MINUTES);
		contingency.waitMinutes = clamp(reader.readVarInt(), 0, MAX_MINUTES);
		contingency.deadlock = reader.readBoolean();
		contingency.manual = reader.readBoolean();
		contingency.turn = clamp(reader.readVarInt(), TURN_NONE, TURN_STATION);
		contingency.turnStationId = reader.readLong();
		contingency.afterTurn = clamp(reader.readVarInt(), AFTER_RESUME, AFTER_DEADHEAD);
		readIds(reader, contingency.skipStationIds, MAX_STATIONS);
		contingency.takeover = reader.readBoolean();
		return contingency;
	}

	public Contingency copy() {
		final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
		write(writer);
		return read(new transitsim.io.ByteArrayBinary.Reader(writer.toBytes()));
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof Contingency)) {
			return false;
		}
		final transitsim.io.ByteArrayBinary.Writer a = new transitsim.io.ByteArrayBinary.Writer();
		final transitsim.io.ByteArrayBinary.Writer b = new transitsim.io.ByteArrayBinary.Writer();
		write(a);
		((Contingency) other).write(b);
		return Arrays.equals(a.toBytes(), b.toBytes());
	}

	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}

	public static String limitName(String name) {
		return name == null ? "" : name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static void writeIds(BinaryWriter writer, Set<Long> ids) {
		writer.writeVarInt(ids.size());
		ids.forEach(writer::writeLong);
	}

	private static void readIds(BinaryReader reader, Set<Long> ids, int max) {
		final int count = reader.readVarInt();
		if (count < 0 || count > max) {
			throw new IllegalArgumentException("Too many: " + count);
		}
		for (int i = 0; i < count; i++) {
			ids.add(reader.readLong());
		}
	}

	/**
	 * What makes a train fall under a plan, or be reported.
	 */
	public enum Reason {
		LATE, WAITING, DEADLOCK
	}
}
