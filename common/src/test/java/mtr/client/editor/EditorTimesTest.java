package mtr.client.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.client.ClientData;
import mtr.data.Route;
import mtr.data.TransportMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditorTimesTest {
	@Test
	void aDwellDragUsesTheDisplayedBaselineAndDoesNotMoveTheArrival() {
		Route route = new Route(987654, TransportMode.TRAIN);
		for (int i = 1; i <= 3; i++) route.platformIds.add(new Route.RoutePlatform(i));
		ClientData.DATA_CACHE.routeIdMap.put(route.id, route);
		long[][] baseline = {{0, 0}, {60000, 90000}, {150000, 150000}};
		ClientData.TIMETABLE_TIMES.put(route.id, baseline);
		EditorTimes.SAVED_TIMES.remember(route);
		try {
			JsonObject request = new JsonParser().parse("{routes:[{id:'987654',timetable:{pass:[false,false,false],tables:[{marginPercent:0,stops:[{arrival:-1,departure:-1,overtake:false},{arrival:-1,departure:-1,overtake:false},{arrival:-1,departure:-1,overtake:false}],exceptions:[{service:0,stops:[{stop:1,run:-1,dwell:60,pass:-1,overtake:-1}]}]}]}}]}").getAsJsonObject();
			java.util.concurrent.CompletableFuture<JsonObject> future = EditorTimes.fromDraftAsync(request);
			assertTrue(future.isDone(), "Dwell-only editing must not launch another path timing request");
			com.google.gson.JsonArray times = future.join().getAsJsonArray("routes").get(0).getAsJsonObject().getAsJsonArray("tables").get(0).getAsJsonObject().getAsJsonArray("exceptions").get(0).getAsJsonObject().getAsJsonArray("times");
			assertEquals(60000, times.get(1).getAsJsonArray().get(0).getAsLong());
			assertEquals(120000, times.get(1).getAsJsonArray().get(1).getAsLong());
			assertEquals(180000, times.get(2).getAsJsonArray().get(0).getAsLong());
			assertSame(baseline, EditorTimes.getFastest(route));
		} finally {
			ClientData.DATA_CACHE.routeIdMap.remove(route.id);
			EditorTimes.clearTimingCache();
		}
	}
}
