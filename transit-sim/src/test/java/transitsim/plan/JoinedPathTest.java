package transitsim.plan;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class JoinedPathTest {
	@Test void copiesAndSlicesKeepOffsetsWithoutCopyingTheirElements() {
		JoinedPath<Integer> path = new JoinedPath<>();
		for (int i = 0; i < 100; i++) path.addAll(Arrays.asList(1, 2, 3));
		path.set(4, 9);
		JoinedPath<Integer> copy = new JoinedPath<>(path.subList(2, 8));
		assertEquals(Arrays.asList(3,1,9,3,1,2), copy);
		path.set(4, 8);
		assertEquals(9, copy.get(2).intValue());
		copy.addAll(Arrays.asList(7, 8)); assertEquals(8, copy.size());
		assertEquals(300, path.size());
	}
}
