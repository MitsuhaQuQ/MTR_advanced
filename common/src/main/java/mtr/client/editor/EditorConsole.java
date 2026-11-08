package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.client.ClientData;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Route;
import mtr.data.SignalBlocks;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.packet.DispatchConsole;
import transitsim.sim.TrainService;

import java.util.*;

/**
 * The web editor's dispatcher's console (D6): the trains on duties of the dimension, each at the signal block its head is
 * in, and the blocks taken, from the last snapshot the server sent; and the dispatcher's commands, sent at once.
 */
public final class EditorConsole {

	private EditorConsole() {
	}

	/**
	 * Asks the server for a new snapshot, and gives the last one.
	 */
	public static JsonObject build() {
		DispatchConsole.sendRequestC2S();
		final JsonObject console = new JsonObject();
		final DispatchConsole.Snapshot snapshot = DispatchConsole.clientSnapshot;
		console.addProperty("available", snapshot != null);
		if (snapshot == null) {
			return console;
		}
		console.addProperty("age", Math.max(0, System.currentTimeMillis() - snapshot.clockOffset - snapshot.serverMillis) / 1000);
		final long serverNow = System.currentTimeMillis() - snapshot.clockOffset;

		// The block of each rail, the first by colour as the game shows them
		final Map<UUID, SignalBlocks.SignalBlock> railBlocks = new HashMap<>();
		ClientData.SIGNAL_BLOCKS.signalBlocks.stream().sorted(Comparator.comparingInt(block -> block.color.ordinal())).forEach(block -> block.getRails().forEach(rail -> railBlocks.putIfAbsent(rail, block)));
		final Map<Long, SignalBlocks.SignalBlock> shownBlocks = new TreeMap<>();
		ClientData.SIGNAL_BLOCKS.signalBlocks.forEach(block -> {
			if (snapshot.occupiedBlocks.contains(block.id)) {
				shownBlocks.put(block.id, block);
			}
		});

		final JsonArray trainsArray = new JsonArray();
		snapshot.trains.forEach(train -> {
			final JsonObject trainObject = new JsonObject();
			trainObject.addProperty("id", String.valueOf(train.trainId));
			final Siding siding = ClientData.DATA_CACHE.sidingIdMap.get(train.sidingId);
			final Depot depot = ClientData.DATA_CACHE.sidingIdToDepot.get(train.sidingId);
			trainObject.addProperty("siding", String.valueOf(train.sidingId));
			trainObject.addProperty("sidingName", siding == null ? "" : siding.name);
			trainObject.addProperty("depot", depot == null ? "" : String.valueOf(depot.id));
			trainObject.addProperty("depotName", depot == null ? "" : depot.name);
			trainObject.addProperty("route", String.valueOf(train.routeId));
			final Route route = ClientData.DATA_CACHE.routeIdMap.get(train.routeId);
			trainObject.addProperty("routeName", route == null ? "" : route.name);
			trainObject.addProperty("slot", train.slot);
			final JsonArray headArray = new JsonArray();
			headArray.add(String.valueOf(train.headRail.getMostSignificantBits()));
			headArray.add(String.valueOf(train.headRail.getLeastSignificantBits()));
			trainObject.add("head", headArray);
			final SignalBlocks.SignalBlock block = railBlocks.get(train.headRail);
			if (block != null) {
				trainObject.addProperty("block", String.valueOf(block.id));
				shownBlocks.put(block.id, block);
			}
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(train.nextStopId);
			final Station station = platform == null ? null : ClientData.DATA_CACHE.platformIdToStation.get(platform.id);
			trainObject.addProperty("next", station == null ? "" : station.name);
			// Where it is by stations, for the run monitor: the station before and after it, and whether it stands at one
			trainObject.addProperty("nextStation", station == null ? "" : String.valueOf(station.id));
			final Station lastStation = ClientData.DATA_CACHE.platformIdToStation.get(train.lastStopId);
			trainObject.addProperty("lastStation", lastStation == null ? "" : String.valueOf(lastStation.id));
			trainObject.addProperty("lastName", lastStation == null ? "" : lastStation.name);
			trainObject.addProperty("stopState", train.stopState);
			trainObject.addProperty("late", train.lateSince == 0 ? 0 : Math.max(0, serverNow - train.lateSince) / 1000);
			trainObject.addProperty("waiting", train.waitingSince == 0 ? 0 : Math.max(0, serverNow - train.waitingSince) / 1000);
			trainObject.addProperty("skipping", (train.flags & 1) != 0);
			trainObject.addProperty("outOfService", (train.flags & 2) != 0);
			trainObject.addProperty("turn", train.turnState);
			trainObject.addProperty("takeover", train.takeoverState);
			trainObject.addProperty("dispatch", train.dispatchState);
			trainsArray.add(trainObject);
		});
		console.add("trains", trainsArray);

		final JsonArray blocksArray = new JsonArray();
		shownBlocks.values().forEach(block -> {
			final JsonObject blockObject = new JsonObject();
			blockObject.addProperty("id", String.valueOf(block.id));
			blockObject.addProperty("color", block.color.toString());
			blockObject.addProperty("occupied", snapshot.occupiedBlocks.contains(block.id));
			final JsonArray railsArray = new JsonArray();
			block.getRails().forEach(rail -> {
				final JsonArray railArray = new JsonArray();
				railArray.add(String.valueOf(rail.getMostSignificantBits()));
				railArray.add(String.valueOf(rail.getLeastSignificantBits()));
				railsArray.add(railArray);
			});
			blockObject.add("rails", railsArray);
			blocksArray.add(blockObject);
		});
		console.add("blocks", blocksArray);
		return console;
	}

	/**
	 * A dispatcher's command for a train: {@code train}, {@code command} (hold, release, extend, turn, clearOut, return,
	 * cancel), {@code seconds} for standing longer, {@code station} for turning back.
	 *
	 * @return an error, or null
	 */
	public static String applyCommand(JsonObject operation) {
		final long trainId;
		try {
			trainId = Long.parseLong(operation.get("train").getAsString());
		} catch (Exception e) {
			return mtr.data.TranslatedMessage.get("gui.mtr.editor_bad_change");
		}
		final String command = operation.has("command") ? operation.get("command").getAsString() : "";
		final List<Long> platformIds = new ArrayList<>();
		long value = 0;
		final int code;
		switch (command) {
			case "hold":
				code = TrainService.COMMAND_HOLD;
				break;
			case "release":
				code = TrainService.COMMAND_RELEASE;
				break;
			case "extend":
				code = TrainService.COMMAND_EXTEND_DWELL;
				value = Math.max(0, Math.min(TrainService.MAX_EXTRA_DWELL_MILLIS / 1000, operation.has("seconds") ? operation.get("seconds").getAsLong() : 0)) * 1000;
				break;
			case "turn":
				code = TrainService.COMMAND_TURN_AT;
				final long stationId;
				try {
					stationId = Long.parseLong(operation.get("station").getAsString());
				} catch (Exception e) {
					return mtr.data.TranslatedMessage.get("gui.mtr.editor_bad_change");
				}
				ClientData.DATA_CACHE.platformIdToStation.forEach((platformId, station) -> {
					if (station.id == stationId) {
						platformIds.add(platformId);
					}
				});
				if (platformIds.isEmpty()) {
					return mtr.data.TranslatedMessage.get("gui.mtr.editor_bad_change");
				}
				break;
			case "clearOut":
				code = TrainService.COMMAND_CLEAR_OUT;
				break;
			case "return":
				code = TrainService.COMMAND_RETURN;
				break;
			case "cancel":
				code = TrainService.COMMAND_CANCEL;
				break;
			default:
				return mtr.data.TranslatedMessage.get("gui.mtr.editor_bad_change");
		}
		DispatchConsole.sendCommandC2S(trainId, code, value, platformIds);
		return null;
	}
}
