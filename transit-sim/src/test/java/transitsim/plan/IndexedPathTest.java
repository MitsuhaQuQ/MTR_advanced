package transitsim.plan;

import org.junit.jupiter.api.Test;
import transitsim.sim.SimDataset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexedPathTest {
	private SimDataset.DataTrack rail(int index) { return new SimDataset.DataTrack(1, 2, 10, true, false, 3, 40, index, 1, true, true, false); }
	@Test
	void aThousandTripsUseOneTemplateAndABoundedQueryCache() {
		List<SimDataset.DataTrack> template = Arrays.asList(rail(0), rail(1));
		List<IndexedPath.Segment<SimDataset.DataTrack>> parts = new ArrayList<>();
		for (int i = 0; i < 1000; i++) parts.add(new IndexedPath.Segment<>(template, i * 2));
		IndexedPath<SimDataset.DataTrack> path = new IndexedPath<>(parts, SimDataset.DataTrack::withStopIndexOffset);
		assertEquals(0, path.materializedSegments());
		PathDistances distances = new PathDistances(); distances.reset(path);
		assertEquals(0, path.materializedSegments());
		for (int i = 0; i < path.size(); i++) { assertEquals(i, path.get(i).getStopIndex()); assertEquals((i + 1) * 10, distances.get(i), 1E-8); }
		assertTrue(path.materializedSegments() <= 8);
		assertEquals(0, path.get(0).getStopIndex());
		assertEquals(0, template.get(0).getStopIndex());
		path.set(0, rail(77));
		for (int i = 0; i < path.size(); i += 2) path.get(i);
		assertEquals(77, path.get(0).getStopIndex());
		assertEquals(77, path.segments().get(0).template.get(0).getStopIndex());
		assertEquals(0, template.get(0).getStopIndex());
	}
}
