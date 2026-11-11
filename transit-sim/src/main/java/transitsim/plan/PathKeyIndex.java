package transitsim.plan;

import java.lang.ref.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compact references to full duty-path keys, including skip/return variants. */
public final class PathKeyIndex {
	public static final String PREFIX = "@path:";
	private static final Map<String, String> byText = new WeakHashMap<>();
	private static final Map<String, Ref> byKey = new HashMap<>();
	private static final ReferenceQueue<String> queue = new ReferenceQueue<>();
	private PathKeyIndex() { }
	private static final class Ref extends WeakReference<String> {
		final String key;
		Ref(String key, String text) { super(text, queue); this.key = key; }
	}
	public static final class Entry {
		public final String key, text;
		private Entry(String key, String text) { this.key = key; this.text = text; }
	}
	private static String hash(String text) {
		try {
			StringBuilder key = new StringBuilder(PREFIX);
			for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
			return key.toString();
		} catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
	public static synchronized String reference(String text) {
		if (text == null || text.isEmpty()) return "";
		Ref ref; while ((ref = (Ref) queue.poll()) != null) byKey.remove(ref.key, ref);
		String key = byText.get(text);
		if (key == null) { key = hash(text); byText.put(text, key); }
		Ref current = byKey.get(key);
		if (current == null || current.get() == null) byKey.put(key, new Ref(key, text));
		return key;
	}
	public static synchronized String resolve(String value) {
		if (value == null || value.isEmpty()) return "";
		if (!value.startsWith(PREFIX)) { reference(value); return value; }
		Ref ref = byKey.get(value); String text = ref == null ? null : ref.get();
		if (text == null) throw new IllegalArgumentException("Missing duty path reference " + value);
		return text;
	}
	public static synchronized Entry entry(String text) {
		if (text == null || text.isEmpty()) return null;
		String key = reference(text);
		return new Entry(key, resolve(key));
	}
	public static Entry load(String key, String text) {
		if (!key.equals(hash(text))) throw new IllegalArgumentException("Duty path digest mismatch");
		return entry(text);
	}
	public static Entry capture(byte[] service) {
		if (service.length == 0) return null;
		transitsim.io.BinaryKeyed.Reader reader = new transitsim.io.BinaryKeyed.Reader(new transitsim.io.ByteArrayBinary.Reader(service));
		return entry(resolve(reader.getString("trip_path", "")));
	}
}
