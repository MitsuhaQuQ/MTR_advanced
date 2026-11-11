package mtr.packet;

import transitsim.plan.TimetableConflicts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.advanced.RegionDataPackets;
import mtr.advanced.RouteLayouts;
import mtr.advanced.StationLayouts;
import mtr.block.BlockTrainAnnouncer;
import mtr.block.BlockTrainScheduleSensor;
import mtr.block.BlockTrainSensorBase;
import mtr.client.AnnouncementPlayer;
import mtr.client.ClientClock;
import mtr.client.ClientEditing;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.client.MapDataCache;
import mtr.client.editor.EditorLayout;
import mtr.client.editor.EditorSimulation;
import mtr.data.*;
import mtr.mappings.RegistryUtilities;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.path.PathFailure;
import mtr.screen.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import transitsim.network.RegionData;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

/**
 * The client's side of most packets: receives data from the server into {@link mtr.client.ClientData} and sends the
 * player's changes from the screens ({@code send...}).
 */
public class PacketTrainDataGuiClient extends PacketTrainDataBase {

	private static final int MAX_CHUNKS = 1 << 16;

	private static final Map<Integer, ByteBuf> TEMP_PACKETS_RECEIVER = new HashMap<>();
	private static long tempPacketId = 0;
	private static int expectedSize = 0;

	public static void receiveLineWordsS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final String words = new String(packet.readByteArray(), StandardCharsets.UTF_8);
		minecraftClient.execute(() -> LineWords.setWordsFromServer(words));
	}

	public static void receiveEditPermissionS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final boolean hasPermissionLevel = packet.readBoolean();
		final boolean editorSimulation = packet.isReadable() && packet.readBoolean();
		final int interlockColor = packet.readableBytes() >= 4 ? packet.readInt() : DyeColor.MAGENTA.ordinal();
		final boolean anteRunHooks = packet.isReadable() && packet.readBoolean();
		final int stablingColor = packet.readableBytes() >= 4 ? packet.readInt() : DyeColor.BROWN.ordinal();
		minecraftClient.execute(() -> {
			ClientData.stablingColor = stablingColor >= 0 && stablingColor < DyeColor.values().length ? DyeColor.values()[stablingColor] : DyeColor.BROWN;
			ClientData.hasEditPermissionLevel = hasPermissionLevel;
			ClientData.editorSimulation = editorSimulation;
			ClientData.anteRunHooks = anteRunHooks;
			ClientData.interlockColor = interlockColor >= 0 && interlockColor < DyeColor.values().length ? DyeColor.values()[interlockColor] : DyeColor.MAGENTA;
			warnIfAssetsMissing(minecraftClient);
		});
	}

	private static boolean assetsWarned;
	/**
	 * A texture of MTR's, in the assets mod (MTM_Assets) since the logic and the assets are two mods (2026-11-09).
	 */
	private static final ResourceLocation ASSETS_CHECK = new ResourceLocation("mtr", "textures/item/tunnel_wall_creator_6_9.png");

	/**
	 * Says once, on joining a world, when the assets mod isn't there: blocks, trains and signs would show without their
	 * textures, models and sounds. Not made a dependency of the logic mod, as servers don't need it.
	 */
	private static void warnIfAssetsMissing(Minecraft minecraftClient) {
		if (assetsWarned || UtilitiesClient.hasResource(ASSETS_CHECK)) {
			return;
		}
		assetsWarned = true;
		System.out.println("Minecraft Transit Railway: the assets mod MTM_Assets is missing; textures, models and sounds of MTR won't show");
		if (minecraftClient.player != null) {
			minecraftClient.player.displayClientMessage(Text.translatable("gui.mtr.assets_missing"), false);
		}
	}

	public static void receiveServerTimeS2C(FriendlyByteBuf packet) {
		// Taken as it arrives, as waiting for the client's thread would make it look later than it is
		ClientClock.INSTANCE.onServerTime(packet.readLong());
	}


	public static void receiveTimetableConfigS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final String timeZone = packet.readUtf();
		final int dayStartHour = packet.readInt();
		minecraftClient.execute(() -> {
			try {
				TimetableConfig.set(timeZone, dayStartHour);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	public static void openVersionCheckS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final String version = packet.readUtf();
		minecraftClient.execute(() -> {
			final String clientVersion = IPacket.getNetworkVersion();
			if (!clientVersion.equals(version)) {
				final ClientPacketListener connection = minecraftClient.getConnection();
				if (connection != null) {
					final int widthDifference1 = minecraftClient.font.width(Text.translatable("gui.mtr.mismatched_versions_your_version")) - minecraftClient.font.width(Text.translatable("gui.mtr.mismatched_versions_server_version"));
					final int widthDifference2 = minecraftClient.font.width(clientVersion) - minecraftClient.font.width(version);
					final int spaceWidth = minecraftClient.font.width(" ");

					final StringBuilder text = new StringBuilder();
					for (int i = 0; i < -widthDifference1 / spaceWidth; i++) {
						text.append(" ");
					}
					text.append(Text.translatable("gui.mtr.mismatched_versions_your_version", clientVersion).getString());
					for (int i = 0; i < -widthDifference2 / spaceWidth; i++) {
						text.append(" ");
					}
					text.append("\n");
					for (int i = 0; i < widthDifference1 / spaceWidth; i++) {
						text.append(" ");
					}
					text.append(Text.translatable("gui.mtr.mismatched_versions_server_version", version).getString());
					for (int i = 0; i < widthDifference2 / spaceWidth; i++) {
						text.append(" ");
					}
					text.append("\n\n");

					connection.getConnection().disconnect(Text.literal(text.toString()).append(Text.translatable("gui.mtr.mismatched_versions")));
				}
			}
		});
	}

	public static void openDashboardScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final TransportMode transportMode = EnumHelper.valueOf(TransportMode.TRAIN, packet.readUtf());
		final boolean useTimeAndWindSync = packet.readBoolean();
		minecraftClient.execute(() -> {
			if (!(minecraftClient.screen instanceof DashboardScreen)) {
				UtilitiesClient.setScreen(minecraftClient, new DashboardScreen(transportMode, useTimeAndWindSync));
			}
		});
	}

	public static void openStationAreaToolScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos1 = packet.readBlockPos();
		final BlockPos pos2 = packet.readBlockPos();
		minecraftClient.execute(() -> {
			if (ClientData.hasPermission()) {
				UtilitiesClient.setScreen(minecraftClient, new mtr.screen.StationAreaToolScreen(pos1, pos2));
			}
		});
	}

	public static void openRailwaySignScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		minecraftClient.execute(() -> {
			if (!(minecraftClient.screen instanceof RailwaySignScreen)) {
				UtilitiesClient.setScreen(minecraftClient, new RailwaySignScreen(pos));
			}
		});
	}

	public static void openTrainSensorScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		minecraftClient.execute(() -> {
			if (minecraftClient.level != null && !(minecraftClient.screen instanceof TrainSensorScreenBase)) {
				final BlockEntity entity = minecraftClient.level.getBlockEntity(pos);
				if (entity instanceof BlockTrainAnnouncer.TileEntityTrainAnnouncer) {
					UtilitiesClient.setScreen(minecraftClient, new TrainAnnouncerScreen(pos));
				} else if (entity instanceof BlockTrainScheduleSensor.TileEntityTrainScheduleSensor) {
					UtilitiesClient.setScreen(minecraftClient, new TrainScheduleSensorScreen(pos));
				} else if (entity instanceof BlockTrainSensorBase.TileEntityTrainSensorBase) {
					UtilitiesClient.setScreen(minecraftClient, new TrainBasicSensorScreen(pos));
				}
			}
		});
	}

	public static void openNodeAngleScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		minecraftClient.execute(() -> {
			if (!(minecraftClient.screen instanceof NodeAngleScreen)) {
				UtilitiesClient.setScreen(minecraftClient, new NodeAngleScreen(pos));
			}
		});
	}

	public static void requestMapRouteC2S(long routeId) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(routeId);
		RegistryClient.sendToServer(PACKET_REQUEST_MAP_ROUTE, packet);
	}

	public static void receiveStaleDepotsS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final Set<Long> depotIds = new HashSet<>();
		final int count = packet.readInt();
		for (int i = 0; i < count; i++) {
			depotIds.add(packet.readLong());
		}
		minecraftClient.execute(() -> {
			ClientData.STALE_DEPOTS.clear();
			ClientData.STALE_DEPOTS.addAll(depotIds);
		});
	}

	public static void requestMapRailsC2S() {
		RegistryClient.sendToServer(PACKET_REQUEST_MAP_RAILS, new FriendlyByteBuf(Unpooled.buffer()));
	}

	public static void receiveMapDigestS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final int kind = packet.readByte();
		final boolean isLast = packet.readBoolean();
		final int count = packet.readInt();
		final long[] keys = new long[count];
		final long[] hashes = new long[count];
		for (int i = 0; i < count; i++) {
			keys[i] = packet.readLong();
			hashes[i] = packet.readLong();
		}
		minecraftClient.execute(() -> MapDataCache.receiveDigest(kind, isLast, keys, hashes));
	}

	public static void sendMapWantedC2S(int kind, boolean isLast, List<Long> keys) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeByte(kind);
		packet.writeBoolean(isLast);
		packet.writeInt(keys.size());
		keys.forEach(packet::writeLong);
		RegistryClient.sendToServer(PACKET_MAP_WANTED, packet);
	}

	public static void receiveMapDataS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final int kind = packet.readByte();
		final long key = packet.readLong();
		final long hash = packet.readLong();
		final long[] payload = new long[packet.readInt()];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = packet.readLong();
		}
		minecraftClient.execute(() -> MapDataCache.receiveData(kind, key, hash, payload));
	}

	public static void setKeyNodeS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		final boolean isKeyNode = packet.readBoolean();
		minecraftClient.execute(() -> {
			if (isKeyNode) {
				ClientData.KEY_NODES.add(pos);
			} else {
				ClientData.KEY_NODES.remove(pos);
			}
		});
	}

	public static void receivePathRegionsS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final RegionData regionData = RegionDataPackets.read(packet);
		minecraftClient.execute(() -> ClientData.PATH_REGIONS = regionData);
	}

	public static void sendPathBoundaryC2S(BlockPos pos1, BlockPos pos2, boolean isBoundary) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos1);
		packet.writeBoolean(pos2 != null);
		if (pos2 != null) {
			packet.writeBlockPos(pos2);
		}
		packet.writeBoolean(isBoundary);
		RegistryClient.sendToServer(PACKET_UPDATE_PATH_BOUNDARY, packet);
	}

	public static void requestStationLayoutC2S(int[] box, TransportMode transportMode, long[] seeds) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		for (final int value : box) {
			packet.writeInt(value);
		}
		packet.writeInt(transportMode.ordinal());
		StationLayouts.writeSeeds(seeds, packet);
		RegistryClient.sendToServer(PACKET_STATION_LAYOUT, packet);
	}

	public static void receiveStationLayoutS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final StationLayouts.Layout layout = StationLayouts.read(packet);
		minecraftClient.execute(() -> EditorLayout.receive(layout));
	}

	public static void requestRouteLayoutC2S(long routeId, int halfWidth) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(routeId);
		packet.writeInt(halfWidth);
		RegistryClient.sendToServer(PACKET_ROUTE_LAYOUT, packet);
	}

	public static void receiveRouteLayoutS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final RouteLayouts.Layout layout = RouteLayouts.read(packet);
		minecraftClient.execute(() -> EditorLayout.receiveRoute(layout));
	}

	public static void receiveSimulateResultS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final int requestId = packet.readInt();
		final int status = packet.readInt();
		final int ignoredDrafts = packet.readInt();
		final int missingDutyPaths = packet.readInt();
		final int depots = packet.readInt();
		final int allDepots = packet.readInt();
		final int part = packet.readInt();
		final int partCount = packet.readInt();
		final byte[] bytes = packet.readByteArray();
		minecraftClient.execute(() -> EditorSimulation.receive(requestId, status, ignoredDrafts, missingDutyPaths, depots, allDepots, part, partCount, bytes));
	}

	public static void requestTimetableTimesC2S(long routeId) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(routeId);
		final Route route = ClientData.DATA_CACHE.routeIdMap.get(routeId);
		if (route == null) { packet.release(); return; }
		packet.writeInt(mtr.client.editor.EditorTimes.SAVED_TIMES.request(route));
		RegistryClient.sendToServer(PACKET_TIMETABLE_TIMES, packet);
	}

	public static void receiveTimetableTimesS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final long routeId = packet.readLong();
		final int stopCount = packet.readInt();
		final long[][] times = new long[stopCount][];
		for (int i = 0; i < stopCount; i++) {
			times[i] = new long[]{packet.readLong(), packet.readLong()};
		}
		final PathFailure failure = PathFailure.readPacket(packet);
		final int requestId = packet.isReadable() ? packet.readInt() : 0;
		final int columns = packet.isReadable() ? packet.readVarInt() : 2;
		if (columns < 2 || columns > transitsim.plan.RouteTimetable.MAX_TABLES * 2 || columns % 2 != 0) return;
		for (int i = 0; i < times.length; i++) {
			times[i] = java.util.Arrays.copyOf(times[i], columns);
			for (int column = 2; column < columns; column++) times[i][column] = packet.readLong();
		}
		minecraftClient.execute(() -> {
			if (requestId > 0) {
				mtr.client.editor.EditorTimes.receiveDraft(requestId, routeId, times);
				return;
			}
			final Route route = ClientData.DATA_CACHE.routeIdMap.get(routeId);
			if (route == null || !mtr.client.editor.EditorTimes.SAVED_TIMES.accept(route, requestId)) return;
			ClientData.TIMETABLE_TIMES.put(routeId, times);
			if (failure.type == PathFailure.Type.NONE) {
				ClientData.ROUTE_PATH_FAILURES.remove(routeId);
			} else {
				ClientData.ROUTE_PATH_FAILURES.put(routeId, failure);
			}
		});
	}

	public static void requestTimetableConflictsC2S(long routeId) {
		ClientData.TIMETABLE_CONFLICTS.remove(routeId);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(routeId);
		RegistryClient.sendToServer(PACKET_TIMETABLE_CONFLICTS, packet);
	}

	public static void receiveTimetableConflictsS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final long routeId = packet.readLong();
		final int uncheckedRoutes = packet.readInt();
		final int conflictCount = packet.readInt();
		final List<TimetableConflicts.Conflict> conflicts = new ArrayList<>();
		for (int i = 0; i < conflictCount; i++) {
			conflicts.add(new TimetableConflicts.Conflict(packet.readInt(), packet.readInt(), packet.readInt(), packet.readLong(), packet.readInt(), packet.readLong(), packet.readLong()));
		}
		minecraftClient.execute(() -> ClientData.TIMETABLE_CONFLICTS.put(routeId, new TimetableConflicts.Result(conflicts, uncheckedRoutes)));
	}

	public static void sendNodeAngleC2S(BlockPos pos, float angle, boolean isKeyNode, int turnBackDwell) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		packet.writeFloat(angle);
		packet.writeBoolean(isKeyNode);
		packet.writeInt(turnBackDwell);
		RegistryClient.sendToServer(PACKET_UPDATE_NODE_ANGLE, packet);
	}

	public static void openLiftTrackFloorS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		minecraftClient.execute(() -> {
			if (!(minecraftClient.screen instanceof LiftTrackFloorScreen)) {
				UtilitiesClient.setScreen(minecraftClient, new LiftTrackFloorScreen(pos));
			}
		});
	}

	public static void openLiftCustomizationS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final long id = packet.readLong();
		minecraftClient.execute(() -> {
			final LiftClient lift = ClientData.DATA_CACHE.liftsClientIdMap.get(id);
			if (!(minecraftClient.screen instanceof LiftCustomizationScreen) && lift != null) {
				UtilitiesClient.setScreen(minecraftClient, new LiftCustomizationScreen(lift));
			}
		});
	}

	public static void openTicketMachineScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final int balance = packet.readInt();
		minecraftClient.execute(() -> {
			if (!(minecraftClient.screen instanceof TicketMachineScreen)) {
				UtilitiesClient.setScreen(minecraftClient, new TicketMachineScreen(balance));
			}
		});
	}

	public static void openPIDSConfigScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos1 = packet.readBlockPos();
		final BlockPos pos2 = packet.readBlockPos();
		final int maxArrivals = packet.readInt();
		final int linesPerArrival = packet.readInt();
		minecraftClient.execute(() -> {
			if (!(minecraftClient.screen instanceof PIDSConfigScreen)) {
				UtilitiesClient.setScreen(minecraftClient, new PIDSConfigScreen(pos1, pos2, maxArrivals, linesPerArrival));
			}
		});
	}

	public static void openArrivalProjectorConfigScreenS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();

		minecraftClient.execute(() -> {
			if (!(minecraftClient.screen instanceof ArrivalProjectorConfigScreen)) {
				UtilitiesClient.setScreen(minecraftClient, new ArrivalProjectorConfigScreen(pos));
			}
		});
	}

	public static void openResourcePackCreatorScreen(Minecraft minecraftClient) {
		minecraftClient.execute(() -> {
			if (!(minecraftClient.screen instanceof ResourcePackCreatorScreen)) {
				UtilitiesClient.setScreen(minecraftClient, new ResourcePackCreatorScreen());
			}
		});
	}

	public static void announceS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final String message = packet.readUtf();
		final String soundString = packet.readUtf();
		minecraftClient.execute(() -> {
			IDrawing.narrateOrAnnounce(message);
			final LocalPlayer player = minecraftClient.player;
			if (player != null) {
				AnnouncementPlayer.play(soundString, player.blockPosition());
			}
		});
	}

	public static void createRailS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final TransportMode transportMode = EnumHelper.valueOf(TransportMode.TRAIN, packet.readUtf());
		final BlockPos pos1 = packet.readBlockPos();
		final BlockPos pos2 = packet.readBlockPos();
		final Rail rail1 = new Rail(packet);
		final Rail rail2 = new Rail(packet);
		final long savedRailId = packet.readLong();
		minecraftClient.execute(() -> {
			RailwayData.addRail(ClientData.RAILS, ClientData.PLATFORMS, ClientData.SIDINGS, transportMode, pos1, pos2, rail1, 0);
			RailwayData.addRail(ClientData.RAILS, ClientData.PLATFORMS, ClientData.SIDINGS, transportMode, pos2, pos1, rail2, savedRailId);
		});
	}

	public static void createSignalS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final long id = packet.readLong();
		final int dyeColorIndex = packet.readInt();
		final UUID rail = packet.readUUID();
		if (dyeColorIndex >= 0 && dyeColorIndex < DyeColor.values().length) {
			minecraftClient.execute(() -> ClientData.SIGNAL_BLOCKS.add(id, DyeColor.values()[dyeColorIndex], rail));
		}
	}

	public static void removeNodeS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		minecraftClient.execute(() -> RailwayData.removeNode(null, ClientData.RAILS, pos));
	}

	public static void removeLiftFloorTrackS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		minecraftClient.execute(() -> RailwayData.removeLiftFloorTrack(null, ClientData.LIFTS, pos));
	}

	public static void removeRailConnectionS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final BlockPos pos1 = packet.readBlockPos();
		final BlockPos pos2 = packet.readBlockPos();
		minecraftClient.execute(() -> RailwayData.removeRailConnection(null, ClientData.RAILS, pos1, pos2));
	}

	public static void removeSignalsS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final long removeCount = packet.readInt();
		final List<Long> ids = new ArrayList<>();
		final List<DyeColor> colors = new ArrayList<>();
		final List<UUID> rails = new ArrayList<>();
		for (int i = 0; i < removeCount; i++) {
			ids.add(packet.readLong());
			colors.add(DyeColor.values()[packet.readInt()]);
			rails.add(packet.readUUID());
		}
		minecraftClient.execute(() -> {
			for (int i = 0; i < removeCount; i++) {
				ClientData.SIGNAL_BLOCKS.remove(ids.get(i), colors.get(i), rails.get(i));
			}
		});
	}

	public static void receiveChunk(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final long id = packet.readLong();
		final int chunk = packet.readInt();
		final boolean complete = packet.readBoolean();

		if (chunk < 0 || chunk >= MAX_CHUNKS) {
			return;
		}

		if (tempPacketId != id) {
			TEMP_PACKETS_RECEIVER.clear();
			tempPacketId = id;
			expectedSize = Integer.MAX_VALUE;
		}

		if (complete) {
			expectedSize = chunk + 1;
		}

		TEMP_PACKETS_RECEIVER.put(chunk, packet.readBytes(packet.readableBytes()));

		if (TEMP_PACKETS_RECEIVER.size() == expectedSize) {
			final FriendlyByteBuf newPacket = new FriendlyByteBuf(Unpooled.buffer());
			for (int i = 0; i < expectedSize; i++) {
				if (!TEMP_PACKETS_RECEIVER.containsKey(i)) {
					TEMP_PACKETS_RECEIVER.clear();
					return;
				}
				newPacket.writeBytes(TEMP_PACKETS_RECEIVER.get(i));
			}
			TEMP_PACKETS_RECEIVER.clear();

			minecraftClient.execute(() -> {
				try {
					ClientData.receivePacket(newPacket);
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		}
	}

	public static <T extends NameColorDataBase> void receiveUpdateOrDeleteS2C(Minecraft minecraftClient, FriendlyByteBuf packet, Set<T> dataSet, Map<Long, T> cacheMap, BiFunction<Long, TransportMode, T> createDataWithId, boolean isDelete) {
		final PacketCallback packetCallback = (updatePacket, fullPacket) -> {
			ClientData.DATA_CACHE.sync();
			ClientData.DATA_CACHE.refreshDynamicResources();
		};
		if (isDelete) {
			deleteData(dataSet, cacheMap, minecraftClient, packet, packetCallback, null);
		} else {
			updateData(dataSet, cacheMap, minecraftClient, packet, packetCallback, createDataWithId, null);
		}
	}

	public static void sendUpdate(ResourceLocation packetId, FriendlyByteBuf packet) {
		// Changes of railway data go with the revision they were made from (multiplayer edit conflicts)
		if (!ClientEditing.send(packetId, packet)) {
			RegistryClient.sendToServer(packetId, packet);
		}
		ClientData.DATA_CACHE.sync();
		ClientData.DATA_CACHE.refreshDynamicResources();
	}

	public static void sendDeleteData(ResourceLocation packetId, long id) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		sendUpdate(packetId, packet);
	}

	public static void sendTrainSensorC2S(BlockPos pos, Set<Long> filterRouteIds, boolean stoppedOnly, boolean movingOnly, int number, String... strings) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		packet.writeInt(filterRouteIds.size());
		filterRouteIds.forEach(packet::writeLong);
		packet.writeBoolean(stoppedOnly);
		packet.writeBoolean(movingOnly);
		packet.writeInt(number);
		packet.writeInt(strings.length);
		for (final String string : strings) {
			packet.writeUtf(string);
		}
		RegistryClient.sendToServer(PACKET_UPDATE_TRAIN_SENSOR, packet);
	}

	public static void sendLiftTrackFloorC2S(BlockPos pos, String floorNumber, String floorDescription, boolean shouldDing) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		packet.writeUtf(floorNumber);
		packet.writeUtf(floorDescription);
		packet.writeBoolean(shouldDing);
		RegistryClient.sendToServer(PACKET_UPDATE_LIFT_TRACK_FLOOR, packet);
	}

	public static void generatePathS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final long depotId = packet.readLong();
		final int successfulSegments = packet.readInt();
		final PathFailure failure = PathFailure.readPacket(packet);
		final int failedCount = packet.readInt();
		final List<String> failedDuties = new ArrayList<>();
		for (int i = Math.min(packet.readInt(), 64); i > 0; i--) {
			failedDuties.add(packet.readUtf(256));
		}
		final int totalFailed = Math.max(failedCount, failedDuties.size());
		final Map<Long, DepotLegs> depotLegs = new HashMap<>();
		for (int i = Math.min(packet.readInt(), 1024); i > 0; i--) {
			final long sidingId = packet.readLong();
			depotLegs.put(sidingId, DepotLegs.readPacket(packet));
		}
		final List<String> detours = new ArrayList<>();
		for (int i = Math.min(packet.readInt(), 64); i > 0; i--) {
			detours.add(packet.readUtf(256));
		}
		// -1: this result measured none, and the depot keeps what it had
		final int turnCount = packet.readInt();
		final Map<Long, Map<Long, Long>> turnTimes = turnCount < 0 ? null : new TreeMap<>();
		for (int i = Math.min(turnCount, 4096); i > 0; i--) {
			final long from = packet.readLong();
			final long to = packet.readLong();
			turnTimes.computeIfAbsent(from, key -> new TreeMap<>()).put(to, packet.readLong());
		}
		minecraftClient.execute(() -> {
			final Depot depot = ClientData.DATA_CACHE.depotIdMap.get(depotId);
			if (depot != null) {
				depot.clientPathGenerationSuccessfulSegments = successfulSegments;
				depot.clientPathFailure = failure;
				depot.clientFailedDuties = failedDuties;
				depot.clientFailedDutyCount = totalFailed;
				depot.clientDetours = detours;
				if (turnTimes != null) {
					depot.clientTurnTimes = turnTimes;
					depot.turnTimesMeasured = true;
				}
				depotLegs.forEach((sidingId, legs) -> {
					final Siding siding = ClientData.DATA_CACHE.sidingIdMap.get(sidingId);
					if (siding != null) {
						siding.setDepotLegs(legs);
					}
				});
				ClientData.STALE_DEPOTS.remove(depotId);
			}
		});
	}

	public static void placeTrainsC2S(long depotId) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(depotId);
		RegistryClient.sendToServer(PACKET_PLACE_TRAINS, packet);
	}

	public static void replanDutiesC2S(long depotId) {
		replanDutiesC2S(depotId, "");
	}

	/**
	 * @param group the key of one duty group of the depot's planning unit to plan again, or empty for all (duty groups, step 4)
	 */
	public static void replanDutiesC2S(long depotId, String group) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(depotId);
		packet.writeUtf(group);
		RegistryClient.sendToServer(PACKET_REPLAN_DUTIES, packet);
	}

	public static void generatePathC2S(long sidingId) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(sidingId);
		RegistryClient.sendToServer(PACKET_GENERATE_PATH, packet);
	}

	public static void clearTrainsC2S(long depotId, Collection<Siding> sidings) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(depotId);
		packet.writeInt(sidings.size());
		sidings.forEach(siding -> packet.writeLong(siding.id));
		RegistryClient.sendToServer(PACKET_CLEAR_TRAINS, packet);
	}

	public static void sendUpdateEntitySeatPassengerPosition(double x, double y, double z) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeDouble(x);
		packet.writeDouble(y);
		packet.writeDouble(z);
		RegistryClient.sendToServer(PACKET_UPDATE_ENTITY_SEAT_POSITION, packet);
	}

	public static void sendSignIdsC2S(BlockPos signPos, Set<Long> selectedIds, String[] signIds) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(signPos);
		packet.writeInt(selectedIds.size());
		selectedIds.forEach(packet::writeLong);
		packet.writeInt(signIds.length);
		for (final String signType : signIds) {
			packet.writeUtf(signType == null ? "" : signType);
		}
		RegistryClient.sendToServer(PACKET_SIGN_TYPES, packet);
	}

	public static void sendDriveTrainC2S(boolean pressingAccelerate, boolean pressingBrake, boolean pressingDoors) {
		if (Train.isHoldingKey(Minecraft.getInstance().player) && (pressingAccelerate || pressingBrake || pressingDoors)) {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeBoolean(pressingAccelerate);
			packet.writeBoolean(pressingBrake);
			packet.writeBoolean(pressingDoors);
			RegistryClient.sendToServer(PACKET_DRIVE_TRAIN, packet);
		}
	}

	public static void sendPressLiftButtonC2S(long id, int floor) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeInt(floor);
		RegistryClient.sendToServer(PACKET_PRESS_LIFT_BUTTON, packet);
	}

	public static void addBalanceC2S(int addAmount, int emeralds) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(addAmount);
		packet.writeInt(emeralds);
		RegistryClient.sendToServer(PACKET_ADD_BALANCE, packet);
	}

	public static void sendPIDSConfigC2S(BlockPos pos1, BlockPos pos2, String[] messages, boolean[] hideArrival, Set<Long> filterPlatformIds, int displayPage, boolean showPassingTrains) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos1);
		packet.writeBlockPos(pos2);
		packet.writeInt(messages.length);
		for (String message : messages) {
			packet.writeUtf(message);
		}
		packet.writeInt(hideArrival.length);
		for (boolean b : hideArrival) {
			packet.writeBoolean(b);
		}
		packet.writeInt(filterPlatformIds.size());
		filterPlatformIds.forEach(packet::writeLong);
		packet.writeInt(displayPage);
		packet.writeBoolean(showPassingTrains);
		RegistryClient.sendToServer(PACKET_PIDS_UPDATE, packet);
	}

	public static void sendArrivalProjectorConfigC2S(BlockPos pos, Set<Long> filterPlatformIds, int displayPage) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		packet.writeInt(filterPlatformIds.size());
		filterPlatformIds.forEach(packet::writeLong);
		packet.writeInt(displayPage);
		RegistryClient.sendToServer(PACKET_ARRIVAL_PROJECTOR_UPDATE, packet);
	}

	public static void sendUseTimeAndWindSyncC2S(boolean useTimeAndWindSync) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBoolean(useTimeAndWindSync);
		RegistryClient.sendToServer(PACKET_USE_TIME_AND_WIND_SYNC, packet);
	}
}
