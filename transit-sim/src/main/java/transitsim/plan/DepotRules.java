package transitsim.plan;

import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;
import transitsim.io.ByteArrayBinary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A depot's rules for going out and back (出入库规则, 运用补完 step 2), set for some lines and weekdays: which station it
 * goes out and back by, how much earlier than its way out takes it leaves, which siding it goes back to, which named
 * deadhead path it takes, and how long a wait between services goes off the platform; and the depot's named deadhead
 * paths (回送路径). The planner plans by the rule each duty's first and last services match; a duty's own exceptions
 * ({@link DepotDuties.DayDuty#exceptions}) stay as they are.
 */
public final class DepotRules {

	public final List<Rule> rules = new ArrayList<>();
	public final List<DeadheadPath> paths = new ArrayList<>();

	public static final int MAX_RULES = 256;
	public static final int MAX_PATHS = 256;
	public static final int MAX_WAYPOINTS = 64;
	private static final int FORMAT_VERSION = 1;

	public boolean isEmpty() {
		return rules.isEmpty() && paths.isEmpty();
	}

	/**
	 * @param lineKey the line of the service, as {@code LineDuties.getLineKey} names it
	 * @param weekday 0 for Monday
	 * @return the rule a service of a line on a weekday goes by: one set for its line and the weekday first, then for its
	 * line, then for the weekday, then for every line and day; of those as particular, the first in the list; null if
	 * none
	 */
	public Rule match(String lineKey, int weekday) {
		Rule best = null;
		int bestRank = -1;
		for (final Rule rule : rules) {
			if (!rule.lineKeys.isEmpty() && !isForLine(rule, lineKey) || rule.weekdays != 0 && (rule.weekdays >> weekday & 1) == 0) {
				continue;
			}
			final int rank = (rule.lineKeys.isEmpty() ? 0 : 2) + (rule.weekdays == 0 ? 0 : 1);
			if (rank > bestRank) {
				best = rule;
				bestRank = rank;
			}
		}
		return best;
	}

	/**
	 * Whether a rule is for a line: one of its keys names the line, as {@link #sameLine} has it.
	 */
	public static boolean isForLine(Rule rule, String lineKey) {
		if (rule.lineKeys.contains(lineKey)) {
			return true;
		}
		for (final String key : rule.lineKeys) {
			if (sameLine(key, lineKey)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether two line keys ({@code <transport mode>|<line name in each language>}) name the same line: the same mode and
	 * the same name in at least one language. A line key follows the routes' names, so a rule set when the line was called
	 * "S1" is still for it once its routes are "S1各停|S1 Local", whose key is "TRAIN|s1|s1" (Q world, 2026-11-12).
	 */
	public static boolean sameLine(String key1, String key2) {
		if (key1.equals(key2)) {
			return true;
		}
		final String[] parts1 = key1.split("\\|", -1);
		final String[] parts2 = key2.split("\\|", -1);
		if (parts1.length < 2 || parts2.length < 2 || !parts1[0].equals(parts2[0])) {
			return false;
		}
		for (int i = 1; i < parts1.length; i++) {
			final String name = parts1[i].trim();
			if (name.isEmpty()) {
				continue;
			}
			for (int j = 1; j < parts2.length; j++) {
				if (name.equals(parts2[j].trim())) {
					return true;
				}
			}
		}
		return false;
	}

	public DeadheadPath getPath(long id) {
		for (final DeadheadPath path : paths) {
			if (path.id == id) {
				return path;
			}
		}
		return null;
	}

	public void write(BinaryWriter writer) {
		writer.writeByte(FORMAT_VERSION);
		writer.writeVarInt(rules.size());
		rules.forEach(rule -> rule.write(writer));
		writer.writeVarInt(paths.size());
		paths.forEach(path -> path.write(writer));
	}

	public void read(BinaryReader reader) {
		rules.clear();
		paths.clear();
		final int version = reader.readUnsignedByte();
		if (version < 1 || version > FORMAT_VERSION) {
			throw new IllegalArgumentException("Unknown depot rules version " + version);
		}
		for (int i = limit(reader.readVarInt(), MAX_RULES); i > 0; i--) {
			rules.add(Rule.read(reader));
		}
		for (int i = limit(reader.readVarInt(), MAX_PATHS); i > 0; i--) {
			paths.add(DeadheadPath.read(reader));
		}
	}

	public byte[] toBytes() {
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		write(writer);
		return writer.toBytes();
	}

	/**
	 * Reads what {@link #toBytes} wrote; none, or what doesn't read, leaves no rules.
	 */
	public void fromBytes(byte[] bytes) {
		rules.clear();
		paths.clear();
		if (bytes == null || bytes.length == 0) {
			return;
		}
		try {
			read(new ByteArrayBinary.Reader(bytes));
		} catch (RuntimeException e) {
			e.printStackTrace();
			rules.clear();
			paths.clear();
		}
	}

	private static int limit(int count, int max) {
		if (count < 0 || count > max) {
			throw new IllegalArgumentException("Too many depot rule entries: " + count);
		}
		return count;
	}

	static void writeString(BinaryWriter writer, String text) {
		final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		writer.writeVarInt(bytes.length);
		for (final byte value : bytes) {
			writer.writeByte(value);
		}
	}

	static String readString(BinaryReader reader) {
		final byte[] bytes = new byte[limit(reader.readVarInt(), 4096)];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = reader.readByte();
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * How the trains of some lines on some weekdays go out and back.
	 */
	public static final class Rule {

		public long id;
		/**
		 * The lines it is for, by key; none for every line of the depot.
		 */
		public final Set<String> lineKeys = new TreeSet<>();
		/**
		 * The weekdays it is for (bit 0 is Monday); 0 for every day.
		 */
		public int weekdays;
		/**
		 * The stations the trains go out and come back by (出入口), or 0 where the depot has only one.
		 */
		public long outEntrance;
		public long backEntrance;
		/**
		 * How much earlier than its way out takes a train leaves the siding.
		 */
		public long extraLeadMillis;
		/**
		 * The siding the trains go back to, or 0 for the one their pool needs a train in next.
		 */
		public long backSiding;
		/**
		 * The named deadhead paths out and back ({@link DeadheadPath#id}), or 0 for the way found by the station nearest the
		 * depot.
		 */
		public long outPath;
		public long backPath;
		/**
		 * From how long a wait between services the train waits off the platform, on a turn back rail, or -1 for the
		 * station's or the network's.
		 */
		public long waitThresholdMillis = -1;

		private void write(BinaryWriter writer) {
			writer.writeLong(id);
			writer.writeVarInt(lineKeys.size());
			lineKeys.forEach(key -> writeString(writer, key));
			writer.writeByte(weekdays);
			writer.writeLong(outEntrance);
			writer.writeLong(backEntrance);
			writer.writeLong(extraLeadMillis);
			writer.writeLong(backSiding);
			writer.writeLong(outPath);
			writer.writeLong(backPath);
			writer.writeLong(waitThresholdMillis + 1);
		}

		private static Rule read(BinaryReader reader) {
			final Rule rule = new Rule();
			rule.id = reader.readLong();
			for (int i = limit(reader.readVarInt(), 1024); i > 0; i--) {
				rule.lineKeys.add(readString(reader));
			}
			rule.weekdays = reader.readUnsignedByte() & DepotDuties.ALL_WEEKDAYS;
			rule.outEntrance = reader.readLong();
			rule.backEntrance = reader.readLong();
			rule.extraLeadMillis = Math.max(0, reader.readLong());
			rule.backSiding = reader.readLong();
			rule.outPath = reader.readLong();
			rule.backPath = reader.readLong();
			rule.waitThresholdMillis = Math.max(-1, reader.readLong() - 1);
			return rule;
		}
	}

	/**
	 * A named way between a siding and a platform, or two platforms, through the nodes or platforms listed in order.
	 */
	public static final class DeadheadPath {

		public static final int OUT = 0;
		public static final int BACK = 1;
		public static final int SHUNT = 2;

		public long id;
		public String name = "";
		public int kind;
		/**
		 * A siding or a platform at each end, by ID.
		 */
		public long from;
		public long to;
		/**
		 * The nodes it goes through in order, as positions; a platform's is its middle.
		 */
		public long[] waypoints = new long[0];

		private void write(BinaryWriter writer) {
			writer.writeLong(id);
			writeString(writer, name);
			writer.writeByte(kind);
			writer.writeLong(from);
			writer.writeLong(to);
			writer.writeVarInt(waypoints.length);
			for (final long waypoint : waypoints) {
				writer.writeLong(waypoint);
			}
		}

		private static DeadheadPath read(BinaryReader reader) {
			final DeadheadPath path = new DeadheadPath();
			path.id = reader.readLong();
			path.name = readString(reader);
			final int kind = reader.readUnsignedByte();
			path.kind = kind <= SHUNT ? kind : OUT;
			path.from = reader.readLong();
			path.to = reader.readLong();
			path.waypoints = new long[limit(reader.readVarInt(), MAX_WAYPOINTS)];
			for (int i = 0; i < path.waypoints.length; i++) {
				path.waypoints[i] = reader.readLong();
			}
			return path;
		}
	}
}
