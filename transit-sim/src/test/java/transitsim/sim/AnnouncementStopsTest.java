package transitsim.sim;

import transitsim.plan.AnnouncementStops;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnnouncementStopsTest {
	@Test void correctsAnAheadByOneIndexWithoutCollapsingTheLastLoopVisit() {
		List<long[]> routes=Collections.singletonList(new long[]{10,20,30,10});
		assertEquals(0,AnnouncementStops.nearest(1,10,routes,new boolean[1]));
		assertEquals(1,AnnouncementStops.nearest(2,20,routes,new boolean[1]));
		assertEquals(2,AnnouncementStops.nearest(3,30,routes,new boolean[1]));
		assertEquals(3,AnnouncementStops.nearest(4,10,routes,new boolean[1]));
		assertEquals(0,AnnouncementStops.nearest(0,10,routes,new boolean[1]));
	}
	@Test void sharedTerminalsAndRepeatedLapsKeepTheirOwnOccurrences() {
		List<long[]> routes=Arrays.asList(new long[]{10,20,30,10},new long[]{10,20,30,10});
		assertEquals(3,AnnouncementStops.nearest(4,10,routes,new boolean[]{true,false}));
		assertEquals(4,AnnouncementStops.nearest(5,20,routes,new boolean[]{true,false}));
		assertEquals(6,AnnouncementStops.nearest(7,10,routes,new boolean[]{true,false}));
		assertEquals(9,AnnouncementStops.nearest(9,99,routes,new boolean[]{true,false}));
	}
	@Test void depotLeadDoesNotCountAsTheFirstPassengerStop() {
		List<TestTrack> path=Arrays.asList(
			new TestTrack(0,10,true,99,0,0),new TestTrack(10,20,false,0,0,0),
			new TestTrack(20,30,false,10,40,0),new TestTrack(30,40,false,0,0,0),
			new TestTrack(40,50,false,20,0,0),new TestTrack(50,60,false,0,0,0),
			new TestTrack(60,70,false,30,40,0),new TestTrack(70,80,false,0,0,0),
			new TestTrack(80,90,false,10,40,0));
		AnnouncementStops.numberDuty(path,2);
		assertArrayEquals(new int[]{0,0,1,1,2,2,3,3,4},path.stream().mapToInt(TestTrack::getStopIndex).toArray());
	}
	@Test void adjacentPartsOfOnePlatformAreOneVisitButAnotherPlatformCounts() {
		List<TestTrack> path=Arrays.asList(new TestTrack(0,10,false,10,0,0),new TestTrack(10,20,false,10,0,0),new TestTrack(20,30,false,20,0,0));
		AnnouncementStops.numberDuty(path,0);
		assertArrayEquals(new int[]{1,1,2},path.stream().mapToInt(TestTrack::getStopIndex).toArray());
	}
}
