package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.advanced.SandboxRuns;
import mtr.client.ClientData;
import mtr.data.Depot;
import mtr.data.Route;
import mtr.packet.IPacket;
import net.minecraft.network.FriendlyByteBuf;
import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.sim.DayRun;
import transitsim.sim.SimDataset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The editor's simulated day (S5): sends the page's draft to the server, which simulates the day with it, and gives the
 * page what came back. Only the latest request counts.
 */
public final class EditorSimulation implements IPacket {

	private static int requestId;
	private static String state = "idle";
	private static int notSimulated;
	private static int ignoredDrafts;
	private static int missingDutyPaths;
	/**
	 * How many depots the server simulated, the routes' own part of the world, and how many it has.
	 */
	private static int scopeDepots, allDepots;
	private static byte[][] parts;
	private static JsonObject result;

	private EditorSimulation() {
	}

	/**
	 * @param request {@code {weekday, fromNow, routes: [id], operations}}, the operations as the page applies them; with
	 *                {@code fromNow} on today, the day is simulated on from the trains as they are now (G0)
	 * @return {@code {request}}, or {@code {error}} if it can't be sent
	 */
	public static JsonObject start(JsonObject request) {
		final JsonObject response = new JsonObject();
		if (!ClientData.hasPermission()) {
			response.addProperty("error", "permission");
			return response;
		}
		final List<byte[]> draftRoutes = new ArrayList<>();
		final Map<Long, byte[]> draftDuties = new HashMap<>();
		int skipped = 0;
		final JsonArray operations = request.has("operations") ? request.getAsJsonArray("operations") : new JsonArray();
		for (final JsonElement operationElement : operations) {
			final JsonObject operation = operationElement.getAsJsonObject();
			final String type = operation.has("type") ? operation.get("type").getAsString() : "";
			try {
				if ("timetable".equals(type)) {
					final byte[] draft = draftRoute(operation);
					if (draft == null) {
						skipped++;
					} else if (draftRoutes.size() < SandboxRuns.MAX_DRAFTS) {
						draftRoutes.add(draft);
					}
				} else if ("duties".equals(type)) {
					final Depot depot = EditorDuties.getDepot(operation);
					final DepotDuties duties = new DepotDuties();
					if (depot == null || EditorDuties.read(depot, operation, duties) != null) {
						skipped++;
					} else if (draftDuties.size() < SandboxRuns.MAX_DRAFTS) {
						draftDuties.put(depot.id, duties.toBytes());
					}
				} else if (!"managed".equals(type)) {
					// Changing stations, adding or deleting types needs paths the server works out on applying
					skipped++;
				}
			} catch (Exception e) {
				skipped++;
			}
		}

		final Set<Long> reportRoutes = new LinkedHashSet<>();
		if (request.has("routes")) {
			request.getAsJsonArray("routes").forEach(id -> {
				if (reportRoutes.size() < SandboxRuns.MAX_REPORT_ROUTES) {
					reportRoutes.add(Long.parseLong(id.getAsString()));
				}
			});
		}

		requestId++;
		state = "running";
		notSimulated = skipped;
		ignoredDrafts = 0;
		missingDutyPaths = 0;
		scopeDepots = 0;
		allDepots = 0;
		parts = null;
		result = null;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(requestId);
		packet.writeInt(request.has("weekday") ? request.get("weekday").getAsInt() : 0);
		packet.writeInt(reportRoutes.size());
		reportRoutes.forEach(packet::writeLong);
		packet.writeInt(draftRoutes.size());
		draftRoutes.forEach(packet::writeByteArray);
		packet.writeInt(draftDuties.size());
		draftDuties.forEach((depotId, bytes) -> {
			packet.writeLong(depotId);
			packet.writeByteArray(bytes);
		});
		packet.writeBoolean(request.has("fromNow") && request.get("fromNow").getAsBoolean());
		RegistryClient.sendToServer(PACKET_SIMULATE, packet);
		response.addProperty("request", requestId);
		return response;
	}

	/**
	 * A draft of a route's timetable on a copy of the route, so nothing changes until the draft is applied.
	 *
	 * @return the route with the draft, or null if the draft can't be applied
	 */
	static byte[] draftRoute(JsonObject operation) {
		final Route route = ClientData.DATA_CACHE.routeIdMap.get(Long.parseLong(operation.get("route").getAsString()));
		if (route == null) {
			return null;
		}
		final Route copy = copyRoute(route);
		if (EditorActions.readTimetable(copy, operation.getAsJsonObject("timetable")) != null) {
			return null;
		}
		copy.timetable.sanitize();
		return new SimDataset.RouteData(copy).toBytes();
	}

	static Route copyRoute(Route route) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		route.writePacket(packet);
		return new Route(packet);
	}

	/**
	 * @return {@code {request, state, notSimulated, ignored, missingPaths, scopeDepots, allDepots, result}}; the state is idle, running, done, busy, permission or
	 * error
	 */
	public static JsonObject getStatus() {
		final JsonObject status = new JsonObject();
		status.addProperty("request", requestId);
		status.addProperty("state", state);
		status.addProperty("notSimulated", notSimulated);
		status.addProperty("ignored", ignoredDrafts);
		status.addProperty("missingPaths", missingDutyPaths);
		status.addProperty("scopeDepots", scopeDepots);
		status.addProperty("allDepots", allDepots);
		if (result != null) {
			status.add("result", result);
		}
		return status;
	}

	/**
	 * @param missing how many shapes of the draft's duties have no path along the rails, so their trains don't run
	 */
	public static void receive(int receivedRequestId, int status, int ignored, int missing, int part, int partCount, byte[] bytes) {
		receive(receivedRequestId, status, ignored, missing, 0, 0, part, partCount, bytes);
	}

	/**
	 * @param depots    how many depots the server simulated, and of how many, or 0 if it didn't run
	 */
	public static void receive(int receivedRequestId, int status, int ignored, int missing, int depots, int allDepots, int part, int partCount, byte[] bytes) {
		if (receivedRequestId != requestId) {
			return;
		}
		ignoredDrafts = ignored;
		missingDutyPaths = missing;
		if (depots > 0) {
			scopeDepots = depots;
			EditorSimulation.allDepots = allDepots;
		}
		if (status != SandboxRuns.STATUS_RESULT) {
			state = status == SandboxRuns.STATUS_BUSY ? "busy" : status == SandboxRuns.STATUS_NO_PERMISSION ? "permission" : status == SandboxRuns.STATUS_DISABLED ? "disabled" : "error";
			return;
		}
		if (parts == null || parts.length != partCount) {
			parts = new byte[Math.max(1, Math.min(partCount, 256))][];
		}
		if (part >= 0 && part < parts.length) {
			parts[part] = bytes;
		}
		for (final byte[] eachPart : parts) {
			if (eachPart == null) {
				return;
			}
		}
		try {
			result = toJson(DayRun.fromBytes(SandboxRuns.join(parts)));
			state = "done";
		} catch (Exception e) {
			e.printStackTrace();
			state = "error";
		}
		parts = null;
	}

	/**
	 * Times as the page has them, in milliseconds into the operating day, or -1; services as the timetable has them.
	 */
	static JsonObject toJson(DayRun dayRun) {
		final JsonObject resultObject = new JsonObject();
		final JsonArray tripsArray = new JsonArray();
		dayRun.trips.forEach(trip -> {
			final JsonObject tripObject = new JsonObject();
			tripObject.addProperty("route", String.valueOf(trip.routeId));
			tripObject.addProperty("service", Math.floorMod(trip.serviceMillis, (long) OperatingDay.MILLIS_PER_DAY));
			tripObject.addProperty("train", trip.trainId);
			final JsonArray stopsArray = new JsonArray();
			trip.stops.forEach(stop -> {
				final JsonArray stopArray = new JsonArray();
				stopArray.add(stop.stopIndex);
				stopArray.add(toDayTime(dayRun, stop.arrivalMillis));
				stopArray.add(toDayTime(dayRun, stop.departureMillis));
				stopArray.add(toDayTime(dayRun, stop.plannedArrivalMillis));
				stopArray.add(toDayTime(dayRun, stop.plannedDepartureMillis));
				stopArray.add(stop.passed ? 1 : 0);
				stopsArray.add(stopArray);
			});
			tripObject.add("stops", stopsArray);
			tripsArray.add(tripObject);
		});
		resultObject.add("trips", tripsArray);
		final JsonArray problemsArray = new JsonArray();
		dayRun.problems.forEach(problem -> {
			final JsonObject problemObject = new JsonObject();
			problemObject.addProperty("kind", problem.kind.name());
			problemObject.addProperty("time", toDayTime(dayRun, problem.time));
			problemObject.addProperty("duration", problem.durationMillis);
			problemObject.addProperty("route", String.valueOf(problem.routeId));
			problemObject.addProperty("service", Math.floorMod(problem.serviceMillis, (long) OperatingDay.MILLIS_PER_DAY));
			problemObject.addProperty("train", problem.trainId);
			problemObject.addProperty("stop", problem.stopIndex);
			problemObject.addProperty("platform", String.valueOf(problem.platformId));
			problemObject.addProperty("detail", problem.detail);
			problemsArray.add(problemObject);
		});
		resultObject.add("problems", problemsArray);
		resultObject.addProperty("dayStart", dayRun.dayStart);
		resultObject.addProperty("from", dayRun.fromMillis - dayRun.dayStart);
		return resultObject;
	}

	private static long toDayTime(DayRun dayRun, long millis) {
		return millis < 0 ? -1 : millis - dayRun.dayStart;
	}
}
