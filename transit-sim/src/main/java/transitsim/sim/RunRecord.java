package transitsim.sim;

import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;
import transitsim.io.ByteArrayBinary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The running trains and depots as the game saves them (G0, contracts document 6.4): what a simulation needs to carry on
 * from a save, or to recover from one after a long stop.
 */
public final class RunRecord {

	/**
	 * When it was recorded.
	 */
	public final long time;
	public final List<TrainRecord> trains = new ArrayList<>();
	/**
	 * Each depot's last service sent out on each first route, and the days each slot's duty ran, by the depot's first siding.
	 */
	public final Map<Long, Map<Long, Long>> lastDeployedTrips = new TreeMap<>();
	public final Map<Long, Map<String, Long>> dutyRunDays = new TreeMap<>();
	/**
	 * When each depot last sent a train out, and which of its sidings it takes next when sending trains out by frequency,
	 * by the depot's first siding (G1).
	 */
	public final Map<Long, Long> lastDeployedMillis = new TreeMap<>();
	public final Map<Long, Integer> deployIndices = new TreeMap<>();
	/**
	 * The trains kept in each siding's shed (P2-C), oldest first.
	 */
	public final Map<Long, List<Long>> sheds = new TreeMap<>();
	/**
	 * The duties each depot has a spare train take over (D5), as {@link DepotDispatcher#takeovers}, by its first siding.
	 */
	public final Map<Long, List<String>> takeovers = new TreeMap<>();

	/**
	 * Version 2 adds the depots sending trains out by frequency, version 3 the sidings' sheds, version 4 the duties taken
	 * over by spares (D5); older records are read without them.
	 */
	private static final int VERSION = 4;

	public RunRecord(long time) {
		this.time = time;
	}

	public byte[] toBytes() {
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		writer.writeInt(VERSION);
		writer.writeLong(time);
		writer.writeVarInt(trains.size());
		trains.forEach(train -> train.write(writer));
		writer.writeVarInt(lastDeployedTrips.size());
		lastDeployedTrips.forEach((sidingId, trips) -> {
			writer.writeLong(sidingId);
			writer.writeVarInt(trips.size());
			trips.forEach((routeId, tripMillis) -> {
				writer.writeLong(routeId);
				writer.writeLong(tripMillis);
			});
		});
		writer.writeVarInt(dutyRunDays.size());
		dutyRunDays.forEach((sidingId, days) -> {
			writer.writeLong(sidingId);
			writer.writeVarInt(days.size());
			days.forEach((key, day) -> {
				writeString(writer, key);
				writer.writeLong(day);
			});
		});
		writer.writeVarInt(lastDeployedMillis.size());
		lastDeployedMillis.forEach((sidingId, millis) -> {
			writer.writeLong(sidingId);
			writer.writeLong(millis);
		});
		writer.writeVarInt(deployIndices.size());
		deployIndices.forEach((sidingId, index) -> {
			writer.writeLong(sidingId);
			writer.writeInt(index);
		});
		writer.writeVarInt(sheds.size());
		sheds.forEach((sidingId, ids) -> {
			writer.writeLong(sidingId);
			writer.writeVarInt(ids.size());
			ids.forEach(writer::writeLong);
		});
		writer.writeVarInt(takeovers.size());
		takeovers.forEach((sidingId, list) -> {
			writer.writeLong(sidingId);
			writer.writeVarInt(list.size());
			list.forEach(takeover -> writeString(writer, takeover));
		});
		return writer.toBytes();
	}

	public static RunRecord fromBytes(byte[] bytes) {
		final ByteArrayBinary.Reader reader = new ByteArrayBinary.Reader(bytes);
		final int version = reader.readInt();
		if (version < 1 || version > VERSION) {
			throw new IllegalArgumentException("Unknown run record version " + version);
		}
		final RunRecord record = new RunRecord(reader.readLong());
		for (int i = reader.readVarInt(); i > 0; i--) {
			record.trains.add(TrainRecord.read(reader));
		}
		for (int i = reader.readVarInt(); i > 0; i--) {
			final Map<Long, Long> trips = record.lastDeployedTrips.computeIfAbsent(reader.readLong(), key -> new TreeMap<>());
			for (int j = reader.readVarInt(); j > 0; j--) {
				trips.put(reader.readLong(), reader.readLong());
			}
		}
		for (int i = reader.readVarInt(); i > 0; i--) {
			final Map<String, Long> days = record.dutyRunDays.computeIfAbsent(reader.readLong(), key -> new TreeMap<>());
			for (int j = reader.readVarInt(); j > 0; j--) {
				days.put(readString(reader), reader.readLong());
			}
		}
		if (version >= 2) {
			for (int i = reader.readVarInt(); i > 0; i--) {
				record.lastDeployedMillis.put(reader.readLong(), reader.readLong());
			}
			for (int i = reader.readVarInt(); i > 0; i--) {
				record.deployIndices.put(reader.readLong(), reader.readInt());
			}
		}
		if (version >= 3) {
			for (int i = reader.readVarInt(); i > 0; i--) {
				final List<Long> ids = record.sheds.computeIfAbsent(reader.readLong(), key -> new ArrayList<>());
				for (int j = reader.readVarInt(); j > 0; j--) {
					ids.add(reader.readLong());
				}
			}
		}
		if (version >= 4) {
			for (int i = reader.readVarInt(); i > 0; i--) {
				final List<String> list = record.takeovers.computeIfAbsent(reader.readLong(), key -> new ArrayList<>());
				for (int j = reader.readVarInt(); j > 0; j--) {
					list.add(readString(reader));
				}
			}
		}
		return record;
	}

	/**
	 * One train, with the fields the game's train saves, and its service as {@link TrainService#write} has it.
	 */
	public static final class TrainRecord {

		public final long sidingId;
		public final long trainId;
		public final float speed;
		public final double railProgress;
		public final float elapsedDwellTicks;
		public final int nextStoppingIndex;
		public final int nextPlatformIndex;
		public final boolean reversed;
		public final boolean onRoute;
		public final byte[] service;
		/**
		 * The cruising cap the train set off with (see {@link TrainRules#getCruiseCap}), which the game doesn't save yet,
		 * or NaN if it wasn't saved. Worked out again from where the train is, it comes out different for a train stopping
		 * short of a taken rail, which then gets to its stop at another time.
		 */
		public final float cruiseCap;

		public TrainRecord(long sidingId, long trainId, float speed, double railProgress, float elapsedDwellTicks, int nextStoppingIndex, int nextPlatformIndex, boolean reversed, boolean onRoute, byte[] service, float cruiseCap) {
			this.sidingId = sidingId;
			this.trainId = trainId;
			this.speed = speed;
			this.railProgress = railProgress;
			this.elapsedDwellTicks = elapsedDwellTicks;
			this.nextStoppingIndex = nextStoppingIndex;
			this.nextPlatformIndex = nextPlatformIndex;
			this.reversed = reversed;
			this.onRoute = onRoute;
			this.service = service;
			this.cruiseCap = cruiseCap;
		}

		private void write(BinaryWriter writer) {
			writer.writeLong(sidingId);
			writer.writeLong(trainId);
			writer.writeInt(Float.floatToIntBits(speed));
			writer.writeLong(Double.doubleToLongBits(railProgress));
			writer.writeInt(Float.floatToIntBits(elapsedDwellTicks));
			writer.writeInt(nextStoppingIndex);
			writer.writeInt(nextPlatformIndex);
			writer.writeBoolean(reversed);
			writer.writeBoolean(onRoute);
			writer.writeVarInt(service.length);
			for (final byte value : service) {
				writer.writeByte(value);
			}
			writer.writeInt(Float.floatToIntBits(cruiseCap));
		}

		private static TrainRecord read(BinaryReader reader) {
			final long sidingId = reader.readLong();
			final long trainId = reader.readLong();
			final float speed = Float.intBitsToFloat(reader.readInt());
			final double railProgress = Double.longBitsToDouble(reader.readLong());
			final float elapsedDwellTicks = Float.intBitsToFloat(reader.readInt());
			final int nextStoppingIndex = reader.readInt();
			final int nextPlatformIndex = reader.readInt();
			final boolean reversed = reader.readBoolean();
			final boolean onRoute = reader.readBoolean();
			final byte[] service = new byte[reader.readVarInt()];
			for (int i = 0; i < service.length; i++) {
				service[i] = reader.readByte();
			}
			return new TrainRecord(sidingId, trainId, speed, railProgress, elapsedDwellTicks, nextStoppingIndex, nextPlatformIndex, reversed, onRoute, service, Float.intBitsToFloat(reader.readInt()));
		}
	}

	private static void writeString(BinaryWriter writer, String text) {
		final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		writer.writeVarInt(bytes.length);
		for (final byte value : bytes) {
			writer.writeByte(value);
		}
	}

	private static String readString(BinaryReader reader) {
		final byte[] bytes = new byte[reader.readVarInt()];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = reader.readByte();
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
