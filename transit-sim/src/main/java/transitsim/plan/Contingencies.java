package transitsim.plan;

import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;
import transitsim.io.ByteArrayBinary;

import java.util.*;

/**
 * A depot's {@link Contingency} plans, which of those started by a dispatcher are on, and what a dispatcher set for single
 * trains there and then (D2). The plans are set up in the web editor like the depot's other settings; the rest is the
 * state of the running, kept in the save until a dispatcher ends it.
 */
public final class Contingencies {

	public static final int MAX_PLANS = 64;
	public static final int MAX_ADHOC = 256;
	private static final int VERSION = 1;

	/**
	 * By id, the lowest first.
	 */
	public final Map<Long, Contingency> plans = new TreeMap<>();
	/**
	 * The plans started by a dispatcher, which are on until ended.
	 */
	public final Set<Long> active = new TreeSet<>();
	/**
	 * Train → what a dispatcher set for it, on until ended.
	 */
	public final Map<Long, Contingency> adhoc = new TreeMap<>();

	public boolean isEmpty() {
		return plans.isEmpty() && active.isEmpty() && adhoc.isEmpty();
	}

	public void writePlans(BinaryWriter writer) {
		writer.writeVarInt(VERSION);
		writer.writeVarInt(plans.size());
		plans.values().forEach(plan -> plan.write(writer));
	}

	public void readPlans(BinaryReader reader) {
		plans.clear();
		reader.readVarInt();
		final int count = reader.readVarInt();
		if (count < 0 || count > MAX_PLANS) {
			throw new IllegalArgumentException("Too many plans: " + count);
		}
		for (int i = 0; i < count; i++) {
			final Contingency plan = Contingency.read(reader);
			plans.put(plan.id, plan);
		}
		// A plan deleted can't be on
		active.retainAll(plans.keySet());
	}

	public void writeState(BinaryWriter writer) {
		writer.writeVarInt(VERSION);
		writer.writeVarInt(active.size());
		active.forEach(writer::writeLong);
		writer.writeVarInt(adhoc.size());
		adhoc.forEach((trainId, contingency) -> {
			writer.writeLong(trainId);
			contingency.write(writer);
		});
	}

	public void readState(BinaryReader reader) {
		active.clear();
		adhoc.clear();
		reader.readVarInt();
		final int activeCount = reader.readVarInt();
		if (activeCount < 0 || activeCount > MAX_PLANS) {
			throw new IllegalArgumentException("Too many on: " + activeCount);
		}
		for (int i = 0; i < activeCount; i++) {
			active.add(reader.readLong());
		}
		final int adhocCount = reader.readVarInt();
		if (adhocCount < 0 || adhocCount > MAX_ADHOC) {
			throw new IllegalArgumentException("Too many trains: " + adhocCount);
		}
		for (int i = 0; i < adhocCount; i++) {
			final long trainId = reader.readLong();
			adhoc.put(trainId, Contingency.read(reader));
		}
		active.retainAll(plans.keySet());
	}

	public byte[] toBytes() {
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		writePlans(writer);
		writeState(writer);
		return writer.toBytes();
	}

	/**
	 * Reads what {@link #toBytes} wrote, leaving everything empty if it can't.
	 */
	public void fromBytes(byte[] bytes) {
		plans.clear();
		active.clear();
		adhoc.clear();
		if (bytes == null || bytes.length == 0) {
			return;
		}
		try {
			final ByteArrayBinary.Reader reader = new ByteArrayBinary.Reader(bytes);
			readPlans(reader);
			readState(reader);
		} catch (Exception e) {
			e.printStackTrace();
			plans.clear();
			active.clear();
			adhoc.clear();
		}
	}

	public Contingencies copy() {
		final Contingencies copy = new Contingencies();
		copy.fromBytes(toBytes());
		return copy;
	}
}
