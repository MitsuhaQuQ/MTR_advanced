package transitsim.plan;

import java.util.*;

/** Cumulative distances using a template-local index plus one prefix per occurrence. */
public final class PathDistances extends AbstractList<Double> implements RandomAccess {
	private IndexedPath<?> indexed;
	private double[] flat = new double[0];
	private double[] prefixes = new double[0];
	private List<double[]> ends = Collections.emptyList();
	public void reset(List<? extends PlanTrack> path) {
		if (!(path instanceof IndexedPath)) {
			indexed = null; ends = Collections.emptyList(); prefixes = new double[0];
			flat = cumulative(path); return;
		}
		indexed = (IndexedPath<?>) path; flat = new double[0];
		List<? extends IndexedPath.Segment<?>> parts = indexed.segments();
		prefixes = new double[parts.size()]; ends = new ArrayList<>();
		Map<List<?>, double[]> templates = new HashMap<>();
		double sum = 0;
		for (int i = 0; i < parts.size(); i++) {
			List<? extends PlanTrack> template = parts.get(i).template;
			double[] local = templates.get(template);
			if (local == null) { local = cumulative(template); templates.put(template, local); }
			prefixes[i] = sum; sum += local[local.length - 1]; ends.add(local);
		}
	}
	private static double[] cumulative(List<? extends PlanTrack> path) {
		double[] values = new double[path.size()]; double sum = 0;
		for (int i = 0; i < values.length; i++) { sum += path.get(i).getTrackLength(); values[i] = sum; }
		return values;
	}
	@Override public int size() { return indexed == null ? flat.length : indexed.size(); }
	@Override public Double get(int rail) {
		if (indexed == null) return flat[rail];
		int part = indexed.segmentAt(rail);
		return prefixes[part] + ends.get(part)[rail - indexed.segmentStart(part)];
	}
}
