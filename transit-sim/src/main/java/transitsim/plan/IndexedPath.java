package transitsim.plan;

import java.util.*;
import java.util.function.BiFunction;

/** A virtual rail list backed by shared segment templates and per-occurrence stop offsets. */
public final class IndexedPath<T extends PlanTrack> extends AbstractList<T> implements RandomAccess {
	public static final int CACHED_SEGMENTS = 8;
	public static final class Segment<T extends PlanTrack> {
		public final List<T> template;
		public final int stopOffset;
		public Segment(List<T> template, int stopOffset) {
			if (template.isEmpty()) throw new IllegalArgumentException("Empty segment");
			this.template = Collections.unmodifiableList(template);
			this.stopOffset = stopOffset;
		}
	}
	private final List<Segment<T>> segments;
	private final int[] ends;
	private final BiFunction<T, Integer, T> offset;
	private final java.util.function.Function<List<T>, String> signature;
	private final Map<Integer, List<T>> cache = new LinkedHashMap<Integer, List<T>>(16, 0.75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<Integer, List<T>> eldest) { return size() > CACHED_SEGMENTS; }
	};
	private final Map<Integer, T> replacements = new HashMap<>();
	private final Map<Object, Object> compiled = new LinkedHashMap<Object, Object>(16, .75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<Object, Object> entry) { return size() > 16; }
	};
	@SuppressWarnings("unchecked")
	public synchronized <V> V compiled(Object key, java.util.function.Supplier<V> create) {
		Object value = compiled.get(key);
		if (value == null) { value = create.get(); compiled.put(key, value); }
		return (V) value;
	}

	public IndexedPath(List<Segment<T>> segments, BiFunction<T, Integer, T> offset) {
		this(segments, offset, null);
	}
	public IndexedPath(List<Segment<T>> segments, BiFunction<T, Integer, T> offset, java.util.function.Function<List<T>, String> signature) {
		this.signature = signature;
		this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
		this.offset = offset;
		ends = new int[segments.size()];
		int count = 0;
		for (int i = 0; i < ends.length; i++) { count = Math.addExact(count, segments.get(i).template.size()); ends[i] = count; }
	}
	public synchronized List<Segment<T>> segments() {
		if (replacements.isEmpty()) return segments;
		List<Segment<T>> result = new ArrayList<>(segments);
		Map<Integer, List<T>> changed = new HashMap<>();
		replacements.forEach((index, rail) -> {
			int part = segmentAt(index);
			List<T> template = changed.computeIfAbsent(part, ignored -> new ArrayList<>(segments.get(part).template));
			template.set(index - segmentStart(part), offset.apply(rail, -segments.get(part).stopOffset));
		});
		changed.forEach((part, template) -> result.set(part, new Segment<>(template, segments.get(part).stopOffset)));
		return Collections.unmodifiableList(result);
	}
	public int segmentStart(int segment) { return segment == 0 ? 0 : ends[segment - 1]; }
	public int segmentAt(int index) {
		if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(String.valueOf(index));
		int at = Arrays.binarySearch(ends, index + 1);
		return at >= 0 ? at : -at - 1;
	}
	@Override public int size() { return ends.length == 0 ? 0 : ends[ends.length - 1]; }
	@Override public synchronized T get(int index) {
		int part = segmentAt(index);
		if (replacements.containsKey(index)) return replacements.get(index);
		List<T> rails = cache.get(part);
		if (rails == null) {
			Segment<T> segment = segments.get(part);
			rails = new ArrayList<>(segment.template.size());
			for (T rail : segment.template) rails.add(offset.apply(rail, segment.stopOffset));
			cache.put(part, rails);
		}
		return rails.get(index - segmentStart(part));
	}
	@Override public synchronized T set(int index, T value) {
		T previous = get(index);
		replacements.put(index, value);
		compiled.clear();
		return previous;
	}
	public synchronized void updateTemplates(java.util.function.Consumer<T> change) {
		Set<List<T>> seen = new HashSet<>();
		for (Segment<T> part : segments) if (seen.add(part.template)) part.template.forEach(change);
		replacements.values().forEach(change);
		cache.clear();
		compiled.clear();
	}
	public synchronized <U extends PlanTrack> IndexedPath<U> map(java.util.function.Function<T, U> mapper, BiFunction<U, Integer, U> shift) {
		return map(mapper, shift, null);
	}
	public synchronized <U extends PlanTrack> IndexedPath<U> map(java.util.function.Function<T, U> mapper, BiFunction<U, Integer, U> shift, java.util.function.Function<List<U>, String> key) {
		Map<List<T>, List<U>> mapped = new HashMap<>();
		List<Segment<U>> parts = new ArrayList<>();
		for (Segment<T> part : segments) {
			List<U> template = mapped.get(part.template);
			if (template == null) {
				template = new ArrayList<>();
				for (T rail : part.template) template.add(mapper.apply(rail));
				mapped.put(part.template, template);
			}
			parts.add(new Segment<>(template, part.stopOffset));
		}
		IndexedPath<U> result = new IndexedPath<>(parts, shift, key);
		replacements.forEach((index, rail) -> result.replacements.put(index, mapper.apply(rail)));
		return result;
	}

	/** Re-index a temporary edited view, retaining unchanged templates by their exact host encoding. */
	public List<T> rebuild(List<T> source, int[] boundaries) {
		if (signature == null) return copyOf(source);
		SortedSet<Integer> cuts = new TreeSet<>(); cuts.add(0); cuts.add(source.size());
		if (boundaries != null) for (int index : boundaries) if (index > 0 && index < source.size()) cuts.add(index);
		Map<String, List<T>> templates = new HashMap<>();
		for (Segment<T> part : segments) templates.putIfAbsent(signature.apply(part.template), part.template);
		List<Integer> positions = new ArrayList<>(cuts);
		List<Segment<T>> result = new ArrayList<>();
		for (int i = 1; i < positions.size(); i++) {
			int from = positions.get(i - 1), to = positions.get(i), stopBase = source.get(from).getStopIndex();
			List<T> local = new ArrayList<>();
			for (int rail = from; rail < to; rail++) local.add(offset.apply(source.get(rail), -stopBase));
			String key = signature.apply(local);
			List<T> template = templates.get(key);
			if (template == null) { template = local; templates.put(key, template); }
			result.add(new Segment<>(template, stopBase));
		}
		return new IndexedPath<>(result, offset, signature);
	}

	public synchronized int materializedSegments() { return cache.size(); }
	public static <T extends PlanTrack> List<T> copyOf(List<T> source) {
		return source instanceof IndexedPath ? source : new ArrayList<>(source);
	}
}
