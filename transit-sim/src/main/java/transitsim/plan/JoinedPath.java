package transitsim.plan;

import java.util.*;

/** Append-oriented path assembly: append segment references instead of copying all prior rails. */
public final class JoinedPath<T> extends AbstractList<T> implements RandomAccess {
	private final List<List<T>> parts = new ArrayList<>();
	private final List<Integer> ends = new ArrayList<>();
	private final Map<Integer, T> changed = new HashMap<>();
	private int count;
	public JoinedPath() { }
	public JoinedPath(Collection<? extends T> source) { addAll(source); }
	@Override public int size() { return count; }
	/** The number of pieces the path refers to; each addAll adds at most one per piece of its source. */
	public int pieceCount() { return parts.size(); }
	private int part(int index) {
		if (index < 0 || index >= count) throw new IndexOutOfBoundsException(String.valueOf(index));
		int at = Collections.binarySearch(ends, index + 1);
		return at >= 0 ? at : -at - 1;
	}
	@Override public T get(int index) {
		int p = part(index);
		return changed.containsKey(index) ? changed.get(index) : parts.get(p).get(index - (p == 0 ? 0 : ends.get(p - 1)));
	}
	@Override public T set(int index, T value) { T old = get(index); changed.put(index, value); return old; }
	@Override public boolean add(T value) { return addAll(Collections.singletonList(value)); }
	@Override public boolean addAll(Collection<? extends T> source) {
		if (source.isEmpty()) return false;
		if (source == this) source = new JoinedPath<>(this);
		if (source instanceof Slice) {
			Slice<? extends T> slice = (Slice<? extends T>) source;
			int base = count;
			for (int i = 0; i < slice.owner.parts.size(); i++) {
				int start = i == 0 ? 0 : slice.owner.ends.get(i - 1), end = slice.owner.ends.get(i);
				int from = Math.max(start, slice.from), to = Math.min(end, slice.to);
				if (from >= to) continue;
				@SuppressWarnings("unchecked") List<T> part = (List<T>) slice.owner.parts.get(i).subList(from - start, to - start);
				parts.add(part); count = Math.addExact(count, part.size()); ends.add(count);
			}
			slice.owner.changed.forEach((index, rail) -> { if (index >= slice.from && index < slice.to) changed.put(base + index - slice.from, rail); });
		} else if (source instanceof JoinedPath) {
			JoinedPath<? extends T> joined = (JoinedPath<? extends T>) source;
			int base = count;
			for (List<? extends T> p : joined.parts) {
				@SuppressWarnings("unchecked") List<T> kept = (List<T>) p;
				parts.add(kept); count = Math.addExact(count, p.size()); ends.add(count);
			}
			joined.changed.forEach((index, rail) -> changed.put(base + index, rail));
		} else {
			List<T> kept = Collections.unmodifiableList(new ArrayList<>(source));
			parts.add(kept); count = Math.addExact(count, kept.size()); ends.add(count);
		}
		modCount++; return true;
	}
	@Override public List<T> subList(int from, int to) {
		if (from < 0 || to < from || to > size()) throw new IndexOutOfBoundsException();
		return new Slice<>(this, from, to);
	}
	private static final class Slice<T> extends AbstractList<T> implements RandomAccess {
		final JoinedPath<T> owner; final int from, to;
		Slice(JoinedPath<T> owner, int from, int to) { this.owner = owner; this.from = from; this.to = to; }
		@Override public int size() { return to - from; }
		@Override public T get(int index) { if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(); return owner.get(from + index); }
		@Override public T set(int index, T value) { get(index); return owner.set(from + index, value); }
	}

	@Override public void clear() { parts.clear(); ends.clear(); changed.clear(); count = 0; modCount++; }
}
