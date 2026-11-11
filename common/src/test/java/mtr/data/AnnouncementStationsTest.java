package mtr.data;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnnouncementStationsTest {
	@Test void anOldAheadByOnePathStillReportsThePhysicalPlatformOnAClosedRoute() {
		DataCache cache=new DataCache(new HashSet<>(),new HashSet<>(),new HashSet<>(),new HashSet<>(),new HashSet<>(),new HashSet<>());
		Route route=new Route(1,TransportMode.TRAIN);
		for(long id:new long[]{10,20,30,10}) route.platformIds.add(new Route.RoutePlatform(id));
		cache.routeIdMap.put(1L,route);
		for(long id:new long[]{10,20,30}) { Station station=new Station(id);station.name=id==10?"A":id==20?"B":"C";cache.platformIdToStation.put(id,station); }
		long[] platforms={10,20,30,10};
		String[] names={"A","B","C","A"},next={"B","C","A",null};
		for(int i=0;i<platforms.length;i++) {
			final int visit=i;
			assertTrue(RailwayData.useRoutesAndStationsFromIndex(i+1,platforms[i],Collections.singletonList(1L),cache,(index,current,following,here,there,last)->{
				assertEquals(visit,index);assertEquals(names[visit],here.name);assertEquals(next[visit],there==null?null:there.name);
			}));
		}
		assertTrue(RailwayData.useRoutesAndStationsFromIndex(4,10,Arrays.asList(1L,1L),cache,(index,current,following,here,there,last)->{
			assertEquals(0,index);assertEquals("A",here.name);assertEquals("B",there.name);
		}));
	}
}
