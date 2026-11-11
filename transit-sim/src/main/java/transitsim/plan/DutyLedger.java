package transitsim.plan;

import java.lang.ref.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable versions of the full connection ledger; live trains keep only a reference and segment cursor. */
public final class DutyLedger {
	public static final String PREFIX = "@duty:";
	private static final Map<String, Ref> entries = new HashMap<>();
	private static final ReferenceQueue<Entry> queue = new ReferenceQueue<>();
	private DutyLedger() { }
	private static final class Ref extends WeakReference<Entry> {
		final String key;
		Ref(Entry entry) { super(entry, queue); key = entry.key; }
	}
	public static final class Entry {
		public final String key, text;
		private final DepotDuties.DayDuty day;
		private long clockKey = Long.MIN_VALUE;
		private long[] starts;
		private boolean ordered;
		private Entry(String key, String text, DepotDuties.DayDuty day) { this.key = key; this.text = text; this.day = day; }
		/** Internal runtime snapshot; callers must not modify it. */
		public DepotDuties.DayDuty day() { return day; }
		public synchronized int segmentAt(long dayStart, long now, int first) {
			if (day.segments.isEmpty()) return -1;
			long base = DutyShapes.getServiceMillis(dayStart, 0);
			if (starts == null || clockKey != base) {
				clockKey = base; starts = new long[day.segments.size()]; ordered = true;
				for (int i = 0; i < starts.length; i++) {
					starts[i] = DutyShapes.getServiceMillis(dayStart, day.segments.get(i).service);
					if (i > 0 && starts[i] < starts[i - 1]) ordered = false;
				}
			}
			first = Math.max(0, Math.min(first, starts.length - 1));
			if (!ordered) { for (int i = starts.length - 1; i > first; i--) if (starts[i] <= now) return i; return first; }
			int low = first, high = starts.length;
			while (low < high) { int mid = (low + high) >>> 1; if (starts[mid] <= now) low = mid + 1; else high = mid; }
			return Math.max(first, low - 1);
		}
	}
	private static void clean() { Ref ref; while ((ref = (Ref) queue.poll()) != null) entries.remove(ref.key, ref); }
	private static String key(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(PREFIX);
			for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
			return result.toString();
		} catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
	public static Entry intern(DepotDuties.DayDuty day) { return day == null ? null : read(day.toText()); }
	public static synchronized Entry read(String value) {
		clean();
		if (value == null || value.isEmpty()) return null;
		String key = value.startsWith(PREFIX) ? value : key(value);
		Ref ref = entries.get(key); Entry result = ref == null ? null : ref.get();
		if (result != null) return result;
		if (value.startsWith(PREFIX)) throw new IllegalArgumentException("Missing duty ledger " + value);
		DepotDuties.DayDuty day = DepotDuties.DayDuty.fromText(value);
		if (day == null) return null;
		result = new Entry(key, value, day); entries.put(key, new Ref(result)); return result;
	}
	public static Entry load(String key, String text) {
		if (!key.equals(key(text))) throw new IllegalArgumentException("Duty ledger digest mismatch");
		return read(text);
	}
	/** The binary train state may also come from old saves, which embedded the whole ledger. */
	public static Entry capture(byte[] service) {
		if (service.length == 0) return null;
		transitsim.io.BinaryKeyed.Reader reader = new transitsim.io.BinaryKeyed.Reader(new transitsim.io.ByteArrayBinary.Reader(service));
		return read(reader.getString("duty_day", ""));
	}
}
