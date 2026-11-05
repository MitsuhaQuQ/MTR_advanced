package mtr.packet;

import mtr.advanced.PlanAdapters;
import transitsim.plan.TimetableConflicts;
import transitsim.plan.RouteTimetable;
import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.SecurityConfig;
import mtr.advanced.RouteLayouts;
import mtr.advanced.StationLayouts;
import transitsim.plan.TimetableBoundaries;
import mtr.block.*;
import mtr.compat.Compat;
import mtr.data.*;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.Text;
import mtr.mappings.Utilities;
import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import mtr.path.PathFailure;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.Score;
import transitsim.network.RailGraph;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The server's side of most packets: sends data to players ({@code ...S2C}) and receives their changes
 * ({@code receive...C2S}). Changes are checked for permission and by {@link EditGuard}, made to {@link RailwayData}, and
 * passed on to the other players in the dimension.
 */
public class PacketTrainDataGuiServer extends PacketTrainDataBase {

	private static final int MAX_LIST_SIZE = 4096;
	private static final int MAX_STRING_LIST_SIZE = 256;
	private static final int MAX_EDIT_DISTANCE = 64;
	private static final int TICKET_MACHINE_DISTANCE = 6;

	private static final int PACKET_CHUNK_SIZE = (int) Math.pow(2, 14); // 16384
	private static final Set<UUID> PLAYERS_CHECKING_CONFLICTS = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, Long> WAITING_CONFLICT_CHECKS = new ConcurrentHashMap<>();

	public static void versionCheckS2C(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeUtf(IPacket.getNetworkVersion());
		Registry.sendToPlayer(player, PACKET_VERSION_CHECK, packet);
	}

	public static void lineWordsS2C(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeByteArray(LineWords.getWordsJson().getBytes(StandardCharsets.UTF_8));
		Registry.sendToPlayer(player, PACKET_LINE_WORDS, packet);
	}

	public static void timetableConfigS2C(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeUtf(TimetableConfig.getTimeZoneId());
		packet.writeInt(TimetableConfig.getDayStartHour());
		Registry.sendToPlayer(player, PACKET_TIMETABLE_CONFIG, packet);
	}

	/**
	 * The server's time, so the client goes by it when it works out where trains are and shows times (G2).
	 */
	public static void serverTimeS2C(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(System.currentTimeMillis());
		Registry.sendToPlayer(player, PACKET_SERVER_TIME, packet);
	}

	/**
	 * Which rules move the trains, so the client follows them the same way ({@link MotionConfig}).
	 */
	public static void motionConfigS2C(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBoolean(MotionConfig.useLegacyMotion(false));
		packet.writeInt(MotionConfig.getComponentDrive(false));
		Registry.sendToPlayer(player, PACKET_MOTION_CONFIG, packet);
	}

	/**
	 * The client only knows the game mode, so this tells it whether the permission level the server asks for lets it edit,
	 * as the server ignores changes it isn't allowed to make without saying so.
	 */
	public static void editPermissionS2C(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBoolean(player.hasPermissions(SecurityConfig.editPermissionLevel));
		Registry.sendToPlayer(player, PACKET_EDIT_PERMISSION, packet);
	}

	public static void openDashboardScreenS2C(ServerPlayer player, TransportMode transportMode, boolean useTimeAndWindSync) {
		// The permission level can change while playing
		editPermissionS2C(player);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeUtf(transportMode.toString());
		packet.writeBoolean(useTimeAndWindSync);
		Registry.sendToPlayer(player, PACKET_OPEN_DASHBOARD_SCREEN, packet);
	}

	public static void openRailwaySignScreenS2C(ServerPlayer player, BlockPos signPos) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(signPos);
		Registry.sendToPlayer(player, PACKET_OPEN_RAILWAY_SIGN_SCREEN, packet);
	}

	public static void openTicketMachineScreenS2C(Level world, ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(TicketSystem.getPlayerScore(world, player, TicketSystem.BALANCE_OBJECTIVE).getScore());
		Registry.sendToPlayer(player, PACKET_OPEN_TICKET_MACHINE_SCREEN, packet);
	}

	public static void openTrainSensorScreenS2C(ServerPlayer player, BlockPos blockPos) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(blockPos);
		Registry.sendToPlayer(player, PACKET_OPEN_TRAIN_SENSOR_SCREEN, packet);
	}

	public static void openNodeAngleScreenS2C(ServerPlayer player, BlockPos blockPos) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(blockPos);
		Registry.sendToPlayer(player, PACKET_OPEN_NODE_ANGLE_SCREEN, packet);
	}

	public static void openLiftTrackFloorScreenS2C(ServerPlayer player, BlockPos blockPos) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(blockPos);
		Registry.sendToPlayer(player, PACKET_OPEN_LIFT_TRACK_FLOOR_SCREEN, packet);
	}

	public static void openLiftCustomizationScreenS2C(ServerPlayer player, long id) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		Registry.sendToPlayer(player, PACKET_OPEN_LIFT_CUSTOMIZATION_SCREEN, packet);
	}

	public static void openPIDSConfigScreenS2C(ServerPlayer player, BlockPos pos1, BlockPos pos2, int maxArrivals, int linesPerArrival) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos1);
		packet.writeBlockPos(pos2);
		packet.writeInt(maxArrivals);
		packet.writeInt(linesPerArrival);
		Registry.sendToPlayer(player, PACKET_OPEN_PIDS_CONFIG_SCREEN, packet);
	}

	public static void openArrivalProjectorConfigScreenS2C(ServerPlayer player, BlockPos pos) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		Registry.sendToPlayer(player, PACKET_OPEN_ARRIVAL_PROJECTOR_CONFIG_SCREEN, packet);
	}

	public static void openResourcePackCreatorScreenS2C(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		Registry.sendToPlayer(player, PACKET_OPEN_RESOURCE_PACK_CREATOR_SCREEN, packet);
	}

	/**
	 * @param soundString what to play, as the announcer keeps it ({@link mtr.audio.AnnouncementSound})
	 */
	public static void announceS2C(ServerPlayer player, String message, String soundString) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeUtf(message);
		packet.writeUtf(soundString);
		Registry.sendToPlayer(player, PACKET_ANNOUNCE, packet);
	}

	public static void createRailS2C(Level world, TransportMode transportMode, BlockPos pos1, BlockPos pos2, Rail rail1, Rail rail2, long savedRailId) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeUtf(transportMode.toString());
		packet.writeBlockPos(pos1);
		packet.writeBlockPos(pos2);
		rail1.writePacket(packet);
		rail2.writePacket(packet);
		packet.writeLong(savedRailId);
		world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_CREATE_RAIL, packet));
	}

	public static void createSignalS2C(Level world, long id, DyeColor dyeColor, UUID rail) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeInt(dyeColor.ordinal());
		packet.writeUUID(rail);
		world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_CREATE_SIGNAL, packet));
	}

	public static void updateRailActionsS2C(Level world, List<Rail.RailActions> railActions) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(railActions.size());
		railActions.forEach(railAction -> railAction.writePacket(packet));
		world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_UPDATE_RAIL_ACTIONS, packet));
	}

	public static void setKeyNodeS2C(Level world, BlockPos pos, boolean isKeyNode) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		packet.writeBoolean(isKeyNode);
		world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_SET_KEY_NODE, packet));
	}

	public static void sendStaleDepotsS2C(ServerPlayer player, Set<Long> depotIds) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(depotIds.size());
		depotIds.forEach(packet::writeLong);
		Registry.sendToPlayer(player, PACKET_STALE_DEPOTS, packet);
	}

	public static void removeNodeS2C(Level world, BlockPos pos) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_REMOVE_NODE, packet));
	}

	public static void removeLiftFloorTrackS2C(Level world, BlockPos pos) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_REMOVE_LIFT_FLOOR_TRACK, packet));
	}

	public static void removeRailConnectionS2C(Level world, BlockPos pos1, BlockPos pos2) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos1);
		packet.writeBlockPos(pos2);
		world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_REMOVE_RAIL, packet));
	}

	public static void removeSignalS2C(Level world, long id, DyeColor dyeColor, UUID rail) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(1);
		packet.writeLong(id);
		packet.writeInt(dyeColor.ordinal());
		packet.writeUUID(rail);
		world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_REMOVE_SIGNALS, packet));
	}

	public static void sendAllInChunks(ServerPlayer player, Set<Station> stations, Set<Platform> platforms, Set<Siding> sidings, Set<Route> routes, Set<Depot> depots, Set<LiftServer> lifts, SignalBlocks signalBlocks, Set<BlockPos> keyNodes) {
		final long tempPacketId = new Random().nextLong();
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());

		serializeData(packet, stations);
		serializeData(packet, platforms);
		serializeData(packet, sidings);
		serializeData(packet, routes);
		serializeData(packet, depots);
		serializeData(packet, lifts);
		serializeData(packet, signalBlocks.signalBlocks);
		packet.writeInt(keyNodes.size());
		keyNodes.forEach(packet::writeBlockPos);

		int i = 0;
		while (!sendChunk(player, packet, tempPacketId, i)) {
			i++;
		}
	}

	/**
	 * Deletes a route, then tells the player, and the log, what still refers to it: the depots that ran it drop it by
	 * themselves, but duties and other routes' through links that use it stay as they were and no longer run that part,
	 * so they can be changed by hand.
	 */
	public static void reportRouteReferences(ServerPlayer player, long routeId, Runnable delete) {
		final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
		final Route route = railwayData == null || RailwayData.hasNoPermission(player) ? null : railwayData.dataCache.routeIdMap.get(routeId);
		final List<String> depots = new ArrayList<>();
		final List<String> duties = new ArrayList<>();
		final List<String> links = new ArrayList<>();
		if (route != null) {
			railwayData.depots.forEach(depot -> {
				if (depot.routeIds.contains(routeId)) {
					depots.add(IGui.formatStationName(depot.name));
				}
				if (depot.duties.sidings.values().stream().anyMatch(slots -> slots.stream().anyMatch(slot -> slot.days.stream().anyMatch(day -> day.segments.stream().anyMatch(segment -> segment.routeId == routeId))))) {
					duties.add(IGui.formatStationName(depot.name));
				}
			});
			railwayData.routes.forEach(otherRoute -> {
				if (otherRoute.id != routeId && otherRoute.timetable.getTables().stream().anyMatch(table -> table.links.values().stream().anyMatch(link -> link.routeId == routeId))) {
					links.add(IGui.formatStationName(otherRoute.name));
				}
			});
		}
		delete.run();
		if (route == null || railwayData.dataCache.routeIdMap.containsKey(routeId)) {
			return;
		}
		final String routeName = IGui.formatStationName(route.name);
		if (!depots.isEmpty()) {
			player.displayClientMessage(Text.translatable("gui.mtr.route_deleted_depots", routeName, String.join(", ", depots)), false);
		}
		if (!duties.isEmpty()) {
			player.displayClientMessage(Text.translatable("gui.mtr.route_deleted_duties", routeName, String.join(", ", duties)), false);
		}
		if (!links.isEmpty()) {
			player.displayClientMessage(Text.translatable("gui.mtr.route_deleted_links", routeName, String.join(", ", links)), false);
		}
		if (!depots.isEmpty() || !duties.isEmpty() || !links.isEmpty()) {
			System.out.println("Minecraft Transit Railway route " + routeName + " deleted by " + player.getGameProfile().getName() + "; depots that ran it: " + depots + ", depots with duties using it: " + duties + ", routes with through links to it: " + links);
		}
	}

	public static <T extends NameColorDataBase> void receiveUpdateOrDeleteC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet, ResourceLocation packetId, Function<RailwayData, Set<T>> dataSet, Function<RailwayData, Map<Long, T>> cacheMap, BiFunction<Long, TransportMode, T> createDataWithId, boolean isDelete) {
		if (RailwayData.hasNoPermission(player)) {
			return;
		}

		// An ID of 0 would be randomised separately by the server and every client
		if (!isDelete && packet.getLong(packet.readerIndex()) == 0) {
			return;
		}

		final Level world = Compat.getLevel(player);
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData == null) {
			return;
		}

		final PacketCallback packetCallback = (updatePacket, fullPacket) -> {
			// Read before the packet is passed on, which can move where it reads from
			final long updatedId = fullPacket.getLong(fullPacket.readerIndex());
			world.players().forEach(worldPlayer -> {
				if (!worldPlayer.getUUID().equals(player.getUUID())) {
					Registry.sendToPlayer((ServerPlayer) worldPlayer, packetId, fullPacket);
				}
			});
			railwayData.dataCache.sync();

			// Links have to follow the timetable boundaries, which the game's screens and the editor already check
			if (packetId.equals(PACKET_UPDATE_ROUTE)) {
				final Route route = railwayData.dataCache.routeIdMap.get(updatedId);
				if (route != null && TimetableBoundaries.sanitizeLinks(route, railwayData.dataCache.routeIdMap::get, PlanAdapters.boundaryPlatforms(railwayData.dataCache.platformIdMap))) {
					route.setTimetable(correctedPacket -> world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_UPDATE_ROUTE, correctedPacket)));
				}
			}

			if (packetId.equals(PACKET_UPDATE_STATION) || packetId.equals(PACKET_DELETE_STATION) || packetId.equals(PACKET_UPDATE_DEPOT) || packetId.equals(PACKET_DELETE_DEPOT)) {
				try {
					UpdateDynmap.updateDynmap(world, railwayData);
				} catch (NoClassDefFoundError | Exception ignored) {
				}
				try {
					UpdateBlueMap.updateBlueMap(world, railwayData);
				} catch (NoClassDefFoundError | Exception ignored) {
				}
				try {
					UpdateSquaremap.updateSquaremap(world, railwayData);
				} catch (NoClassDefFoundError | Exception ignored) {
				}
			}
		};

		if (isDelete) {
			deleteData(dataSet.apply(railwayData), cacheMap.apply(railwayData), minecraftServer, packet, packetCallback, data -> railwayData.railwayDataLoggingModule.addEvent(player, data.getClass(), data.id, data.name, RailwayDataLoggingModule.getData(data), new ArrayList<>()));
		} else {
			updateData(dataSet.apply(railwayData), cacheMap.apply(railwayData), minecraftServer, packet, packetCallback, createDataWithId, (data, oldData) -> railwayData.railwayDataLoggingModule.addEvent(player, data.getClass(), data.id, data.name, oldData, RailwayDataLoggingModule.getData(data)));
		}
	}

	public static void generatePathS2C(Level world, long depotId, int successfulSegments) {
		generatePathS2C(world, depotId, successfulSegments, PathFailure.NONE);
	}

	public static void generatePathS2C(Level world, long depotId, int successfulSegments, PathFailure failure) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(depotId);
		packet.writeInt(successfulSegments);
		failure.writePacket(packet);
		world.players().forEach(player -> Registry.sendToPlayer((ServerPlayer) player, PACKET_GENERATE_PATH, packet));
	}

	public static void generatePathC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		if (RailwayData.hasNoPermission(player)) {
			return;
		}

		final Level world = Compat.getLevel(player);
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData != null) {
			final long depotId = packet.readLong();
			PacketGuard.execute(minecraftServer, () -> railwayData.railwayDataPathGenerationModule.generatePath(minecraftServer, depotId));
		}
	}

	public static void clearTrainsC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		if (RailwayData.hasNoPermission(player)) {
			return;
		}

		final Level world = Compat.getLevel(player);
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData != null) {
			final long depotId = packet.readLong();
			final int sidingCount = PacketGuard.readCount(packet, Long.BYTES, MAX_LIST_SIZE);
			final Set<Long> sidingIds = new HashSet<>();
			for (int i = 0; i < sidingCount; i++) {
				sidingIds.add(packet.readLong());
			}
			PacketGuard.execute(minecraftServer, () -> {
				final Depot depot = railwayData.dataCache.depotIdMap.get(depotId);
				if (depot != null) {
					railwayData.resetTrainDelays(depot);
				}
				sidingIds.forEach(sidingId -> {
					final Siding siding = railwayData.dataCache.sidingIdMap.get(sidingId);
					if (siding != null) {
						siding.clearTrains();
					}
				});
			});
		}
	}

	public static void receiveTrainSensorC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		final Set<Long> filterIds = new HashSet<>();
		final int filterLength = PacketGuard.readCount(packet, Long.BYTES, MAX_LIST_SIZE);
		for (int i = 0; i < filterLength; i++) {
			filterIds.add(packet.readLong());
		}
		final boolean stoppedOnly = packet.readBoolean();
		final boolean movingOnly = packet.readBoolean();
		final int number = packet.readInt();
		final int stringCount = PacketGuard.readCount(packet, 1, MAX_STRING_LIST_SIZE);
		final String[] strings = new String[stringCount];
		for (int i = 0; i < stringCount; i++) {
			strings[i] = packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
		}
		PacketGuard.execute(minecraftServer, () -> {
			if (!canEditBlock(player, pos)) {
				return;
			}
			final BlockEntity entity = Compat.getLevel(player).getBlockEntity(pos);
			if (entity instanceof BlockTrainSensorBase.TileEntityTrainSensorBase) {
				setTileEntityDataAndWriteUpdate(player, entity2 -> entity2.setData(filterIds, stoppedOnly, movingOnly, number, strings), (BlockTrainSensorBase.TileEntityTrainSensorBase) entity);
			}
		});
	}

	public static void receiveRequestMapRailsC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null) {
				railwayData.sendMapRails(player);
			}
		});
	}

	public static void receiveRequestMapRouteC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final long routeId = packet.readLong();
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null) {
				railwayData.sendMapRoute(player, routeId);
			}
		});
	}

	public static void receiveMapWantedC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int kind = packet.readByte();
		final boolean isLast = packet.readBoolean();
		final int count = PacketGuard.readCount(packet, Long.BYTES, Short.MAX_VALUE);
		final long[] keys = new long[count];
		for (int i = 0; i < count; i++) {
			keys[i] = packet.readLong();
		}
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null) {
				railwayData.sendMapEntries(player, kind, isLast, keys);
			}
		});
	}

	/**
	 * Sends the fastest time at each stop of a route. A route can be part of a depot's path, or run by no depot at all, as
	 * with the way back after a turn back, and then its path is found just for this.
	 */
	/**
	 * The rails around a station as their real shape, for the advanced editor's track diagram ({@link StationLayouts}).
	 */
	public static void receiveStationLayoutC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int[] box = StationLayouts.clampBox(packet.readInt(), packet.readInt(), packet.readInt(), packet.readInt());
		final int mode = packet.readInt();
		final long[] seeds = StationLayouts.readSeeds(packet);
		if (mode < 0 || mode >= TransportMode.values().length || !StationLayouts.allowRequest(player.getUUID())) {
			return;
		}
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null) {
				final FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
				StationLayouts.write(railwayData.getRails(), box, TransportMode.values()[mode], seeds, response);
				Registry.sendToPlayer(player, PACKET_STATION_LAYOUT, response);
			}
		});
	}

	/**
	 * The straightened track diagram of a route, for the advanced editor ({@link RouteLayouts}).
	 */
	public static void receiveRouteLayoutC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final long routeId = packet.readLong();
		final int halfWidth = Mth.clamp(packet.readInt(), RouteLayouts.MIN_HALF_WIDTH, RouteLayouts.MAX_HALF_WIDTH);
		if (!RouteLayouts.allowRequest(player.getUUID())) {
			return;
		}
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null) {
				railwayData.sendRouteLayout(player, routeId, halfWidth);
			}
		});
	}

	/**
	 * Puts a depot's trains where its timetable has them now, instead of sending them out one by one (see
	 * {@link RailwayData#placeTrainsByTimetable}).
	 */
	public static void receivePlaceTrainsC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final long depotId = packet.readLong();
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null && !RailwayData.hasNoPermission(player)) {
				railwayData.placeTrainsByTimetable(player, depotId);
			}
		});
	}

	public static void receiveTimetableTimesC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final long routeId = packet.readLong();
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			final Route route = railwayData == null ? null : railwayData.dataCache.routeIdMap.get(routeId);
			if (route != null) {
				final Map<Long, Set<PathTiming.Vehicle>> vehicles = RoutePaths.getVehicles(railwayData);
				final long[][] times = getKnownTimes(railwayData, route, vehicles);
				if (times == null) {
					calculateTimetableTimes(minecraftServer, player, railwayData, route, vehicles);
				} else {
					sendTimetableTimes(player, routeId, times);
				}
			}
		});
	}

	/**
	 * Looks for conflicts between a route and the timetables on its platforms. A player can only run one check at a time,
	 * as finding the times of routes outside a depot takes a while.
	 */
	public static void receiveTimetableConflictsC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final long routeId = packet.readLong();
		PacketGuard.execute(minecraftServer, () -> checkTimetableConflicts(minecraftServer, player, routeId));
	}

	/**
	 * A check asked for during another one runs after it, as the times may have changed since that one started.
	 */
	private static void checkTimetableConflicts(MinecraftServer minecraftServer, ServerPlayer player, long routeId) {
		final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
		final Route route = railwayData == null ? null : railwayData.dataCache.routeIdMap.get(routeId);
		if (route == null) {
			return;
		}
		if (PLAYERS_CHECKING_CONFLICTS.contains(player.getUUID())) {
			WAITING_CONFLICT_CHECKS.put(player.getUUID(), routeId);
			return;
		}

		final Set<Long> platformIds = new HashSet<>();
		route.platformIds.forEach(routePlatform -> platformIds.add(routePlatform.platformId));
		// Routes on the same turn back rail matter too, and the route before it may have no platform in common
		final Map<Long, Set<TimetableConflicts.TurnBack>> turnBacks = getTurnBacks(railwayData);
		final Set<UUID> turnBackRails = new HashSet<>();
		turnBacks.forEach((testRouteId, routeTurnBacks) -> routeTurnBacks.forEach(turnBack -> {
			if (testRouteId == routeId || turnBack.nextRouteId == routeId) {
				turnBackRails.addAll(turnBack.getCandidates());
			}
		}));
		final Set<Long> turnBackRouteIds = new HashSet<>();
		turnBacks.forEach((testRouteId, routeTurnBacks) -> routeTurnBacks.forEach(turnBack -> {
			if (turnBack.getCandidates().stream().anyMatch(turnBackRails::contains)) {
				turnBackRouteIds.add(testRouteId);
				turnBackRouteIds.add(turnBack.nextRouteId);
			}
		}));

		final List<Route> routes = new ArrayList<>();
		routes.add(route);
		railwayData.routes.forEach(testRoute -> {
			if (testRoute != route && testRoute.timetable.isActive() && (turnBackRouteIds.contains(testRoute.id) || testRoute.platformIds.stream().anyMatch(routePlatform -> platformIds.contains(routePlatform.platformId)))) {
				routes.add(testRoute);
			}
		});

		final Map<Long, Long> nextRouteIds = getNextRouteIds(railwayData);
		final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRails();
		final Map<Route, long[][]> knownTimes = new HashMap<>();
		final Map<Route, Supplier<long[][]>> timeFinders = new HashMap<>();
		final Map<Long, Set<PathTiming.Vehicle>> vehicles = RoutePaths.getVehicles(railwayData);
		final RailGraph<Rail>[] graph = new RailGraph[1];
		final TimeSearch[] search = new TimeSearch[1];
		routes.forEach(testRoute -> {
			final long[][] times = getKnownTimes(railwayData, testRoute, vehicles);
			if (times == null) {
				if (graph[0] == null && canUseRailGraph(route) && !usesRoutePath(testRoute)) {
					graph[0] = DeterministicPathFinder.compile(rails, railwayData.pathBoundaries);
				}
				if (search[0] == null && usesRoutePath(testRoute)) {
					search[0] = new TimeSearch(railwayData, vehicles);
				}
				final Supplier<long[][]> timeFinder = getTimeFinder(railwayData, testRoute, rails, graph[0], search[0]);
				if (timeFinder != null) {
					timeFinders.put(testRoute, timeFinder);
				}
			} else {
				knownTimes.put(testRoute, times);
			}
		});

		PLAYERS_CHECKING_CONFLICTS.add(player.getUUID());
		new Thread(() -> {
			try {
				final List<TimetableConflicts.RouteTimes> routeTimes = new ArrayList<>();
				int uncheckedRoutes = 0;
				for (final Route testRoute : routes) {
					final Supplier<long[][]> timeFinder = timeFinders.get(testRoute);
					final long[][] times = timeFinder == null ? knownTimes.get(testRoute) : timeFinder.get();
					if (times == null || times.length != testRoute.platformIds.size()) {
						uncheckedRoutes++;
					} else {
						// Stops the path doesn't reach are left out of the check, so the route only counts as partly checked
						if (Arrays.stream(times).anyMatch(stopTime -> stopTime[0] < 0)) {
							uncheckedRoutes++;
						}
						final TimetableConflicts.RouteTimes newRouteTimes = new TimetableConflicts.RouteTimes(testRoute.id, testRoute.platformIds);
						for (int i = 0; i < testRoute.timetable.getTableCount(); i++) {
							final RouteTimetable table = testRoute.timetable.getTable(i);
							newRouteTimes.addTable(i, table.weekdays, table.services, table.marginPercent, times, table.exceptions);
						}
						newRouteTimes.nextRouteId = nextRouteIds.getOrDefault(testRoute.id, 0L);
						turnBacks.getOrDefault(testRoute.id, Collections.emptySet()).forEach(newRouteTimes::addTurnBack);
						routeTimes.add(newRouteTimes);
					}
				}
				final List<TimetableConflicts.Conflict> conflicts = TimetableConflicts.check(routeId, routeTimes);
				final int finalUncheckedRoutes = uncheckedRoutes;
				minecraftServer.execute(() -> sendTimetableConflicts(player, routeId, conflicts, finalUncheckedRoutes));
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				PLAYERS_CHECKING_CONFLICTS.remove(player.getUUID());
				final Long waitingRouteId = WAITING_CONFLICT_CHECKS.remove(player.getUUID());
				if (waitingRouteId != null) {
					minecraftServer.execute(() -> checkTimetableConflicts(minecraftServer, player, waitingRouteId));
				}
			}
		}).start();
	}

	/**
	 * A train runs the routes of its depot in order, and the first one again when the last one ends where the first one starts.
	 */
	private static Map<Long, Long> getNextRouteIds(RailwayData railwayData) {
		final Map<Long, Long> nextRouteIds = new HashMap<>();
		railwayData.sidings.forEach(siding -> {
			final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
			final List<Route> routes = depot == null ? Collections.emptyList() : depot.getRoutes(siding.getTrainId(), railwayData.dataCache);
			for (int i = 0; i < routes.size(); i++) {
				final Route route = routes.get(i);
				final Route nextRoute = routes.get((i + 1) % routes.size());
				if (route.getLastPlatformId() == nextRoute.getFirstPlatformId()) {
					nextRouteIds.putIfAbsent(route.id, nextRoute.id);
				}
			}
		});
		return nextRouteIds;
	}

	private static Map<Long, Set<TimetableConflicts.TurnBack>> getTurnBacks(RailwayData railwayData) {
		final Map<Long, Set<TimetableConflicts.TurnBack>> turnBacks = new HashMap<>();
		railwayData.sidings.forEach(siding -> {
			final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
			if (depot != null && !siding.getStopTimes().isEmpty()) {
				siding.getStopTimes().getTurnBacks(depot.getRoutes(siding.getTrainId(), railwayData.dataCache)).forEach(turnBack -> {
					turnBack.setCandidates(siding.getTurnBackCandidates(turnBack.rail));
					turnBacks.computeIfAbsent(turnBack.routeId, key -> new HashSet<>()).add(turnBack);
				});
			}
		});
		return turnBacks;
	}

	/**
	 * A route's fastest times come from its own path ({@link RoutePaths}), for the slowest vehicle running it, the same
	 * as the depots' duties are worked out from (decoupling document 5.4, L1). Routes with a path drawn by hand, airplanes
	 * and continuous movement are timed along a depot's path, or a path found for them here, as before.
	 */
	private static boolean usesRoutePath(Route route) {
		return RoutePaths.timesByPath(route);
	}

	/**
	 * @return the route's fastest times if they are known without finding a path, or null
	 */
	private static long[][] getKnownTimes(RailwayData railwayData, Route route, Map<Long, Set<PathTiming.Vehicle>> vehicles) {
		if (usesRoutePath(route)) {
			final RoutePaths.RoutePath routePath = railwayData.routePaths.getFound(route, railwayData.dataCache, railwayData.getRails());
			return routePath == null ? null : routePath.getFastestTimes(route, RoutePaths.getVehicles(vehicles, route));
		}
		return getDepotTimes(railwayData, route);
	}

	/**
	 * Route paths to find away from the server thread, on a copy of the rails and data made here.
	 */
	private static final class TimeSearch {

		private final RoutePaths.Search search;
		private final DataCache dataCache;

		private TimeSearch(RailwayData railwayData, Map<Long, Set<PathTiming.Vehicle>> vehicles) {
			search = new RoutePaths.Search(railwayData.routePaths, DataCache.copyRails(railwayData.getRails()), null, railwayData.pathBoundaries, vehicles);
			dataCache = railwayData.dataCache.copyIdMaps();
		}

		private long[][] getFastestTimes(Route route) {
			final long[][] times = search.getFastestTimes(route, dataCache);
			return times == null ? new long[0][] : times;
		}

		/**
		 * @return why the route has no path, or {@link PathFailure#NONE}, found by now with {@link #getFastestTimes}
		 */
		private PathFailure getFailure(Route route) {
			final RoutePaths.RoutePath routePath = search.get(route, dataCache);
			return routePath == null ? PathFailure.NONE : routePath.failure;
		}
	}

	/**
	 * @return the fastest times of a route from the path of a depot, or null if no depot has a path with this route or the
	 * depot runs on speed curves
	 */
	private static long[][] getDepotTimes(RailwayData railwayData, Route route) {
		for (final Siding siding : railwayData.sidings) {
			final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
			final List<Route> routes = depot == null ? Collections.emptyList() : depot.getRoutes(siding.getTrainId(), railwayData.dataCache);
			if (routes.contains(route) && !siding.getStopTimes().isEmpty()) {
				// On speed curves the times follow from the route's own path, so they don't wait for the depot's path to be refreshed
				return depot.speedCurves ? null : siding.getStopTimes().getFastestTimes(route, routes);
			}
		}
		return null;
	}

	private static boolean canUseRailGraph(Route route) {
		return route.transportMode != TransportMode.AIRPLANE && !route.transportMode.continuousMovement;
	}

	private static void calculateTimetableTimes(MinecraftServer minecraftServer, ServerPlayer player, RailwayData railwayData, Route route, Map<Long, Set<PathTiming.Vehicle>> vehicles) {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRails();
		final boolean usesRoutePath = usesRoutePath(route);
		final TimeSearch search = usesRoutePath ? new TimeSearch(railwayData, vehicles) : null;
		final Supplier<long[][]> timeFinder = getTimeFinder(railwayData, route, rails, canUseRailGraph(route) && !usesRoutePath ? DeterministicPathFinder.compile(rails, railwayData.pathBoundaries) : null, search);

		if (timeFinder == null) {
			sendTimetableTimes(player, route.id, new long[0][]);
			return;
		}

		new Thread(() -> {
			try {
				final long[][] times = timeFinder.get();
				// The editor shows why a route has no times when its path can't be found
				final PathFailure failure = search == null ? PathFailure.NONE : search.getFailure(route);
				minecraftServer.execute(() -> sendTimetableTimes(player, route.id, times, failure));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}).start();
	}

	/**
	 * Gets a route's path ready to be found away from the server thread.
	 *
	 * @return what finds the fastest times, or null if the route has nothing to find a path through
	 */
	private static Supplier<long[][]> getTimeFinder(RailwayData railwayData, Route route, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, TimeSearch search) {
		if (usesRoutePath(route)) {
			return route.platformIds.size() < 2 || search == null ? null : () -> search.getFastestTimes(route);
		}
		final List<SavedRailBase> platforms = new ArrayList<>();
		final List<List<BlockPos>> waypoints = new ArrayList<>();
		final List<Route.RoutePlatform> stopSettings = new ArrayList<>();
		route.platformIds.forEach(routePlatform -> {
			final Platform platform = railwayData.dataCache.platformIdMap.get(routePlatform.platformId);
			if (platform != null) {
				platforms.add(platform);
				waypoints.add(new ArrayList<>(routePlatform.waypoints));
				stopSettings.add(routePlatform);
			}
		});

		// The train running the other direction of the line is the best guess for the train running this one
		final Siding siding = railwayData.sidings.stream().filter(testSiding -> {
			final Depot depot = railwayData.dataCache.sidingIdToDepot.get(testSiding.id);
			return depot != null && depot.getRoutes(railwayData.dataCache).stream().anyMatch(route::isSameLine);
		}).findFirst().orElseGet(() -> railwayData.sidings.stream().filter(testSiding -> testSiding.isTransportMode(route.transportMode)).findFirst().orElse(null));

		if (siding == null || platforms.size() < 2) {
			return null;
		}

		return () -> {
			final List<PathData> path = new ArrayList<>();
			PathFinder.findPath(path, rails, graph, platforms, waypoints, 1, Depot.DEFAULT_CRUISING_ALTITUDE, false);
			PathFinder.applyStopSettings(path, stopSettings);
			// The route's own path starts standing at its first stop, so the times count from leaving it
			return path.isEmpty() ? new long[0][] : siding.calculateStopTimes(path).getFastestTimesFromFirstStop(route);
		};
	}

	private static void sendTimetableConflicts(ServerPlayer player, long routeId, List<TimetableConflicts.Conflict> conflicts, int uncheckedRoutes) {
		final FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
		response.writeLong(routeId);
		response.writeInt(uncheckedRoutes);
		response.writeInt(conflicts.size());
		conflicts.forEach(conflict -> {
			response.writeInt(conflict.table);
			response.writeInt(conflict.stopIndex);
			response.writeInt(conflict.service);
			response.writeLong(conflict.otherRouteId);
			response.writeInt(conflict.otherService);
			response.writeLong(conflict.platformId);
		});
		Registry.sendToPlayer(player, PACKET_TIMETABLE_CONFLICTS, response);
	}

	private static void sendTimetableTimes(ServerPlayer player, long routeId, long[][] times) {
		sendTimetableTimes(player, routeId, times, PathFailure.NONE);
	}

	private static void sendTimetableTimes(ServerPlayer player, long routeId, long[][] times, PathFailure failure) {
		final FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
		response.writeLong(routeId);
		response.writeInt(times.length);
		for (final long[] stopTime : times) {
			response.writeLong(stopTime[0]);
			response.writeLong(stopTime[1]);
		}
		failure.writePacket(response);
		Registry.sendToPlayer(player, PACKET_TIMETABLE_TIMES, response);
	}

	public static void receiveNodeAngleC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		final float angle = packet.readFloat();
		final boolean isKeyNode = packet.readBoolean();
		final int turnBackDwell = packet.readInt();
		PacketGuard.execute(minecraftServer, () -> {
			if (!canEditBlock(player, pos) || !Float.isFinite(angle)) {
				return;
			}
			final Level world = Compat.getLevel(player);
			final BlockState state = world.getBlockState(pos);
			final RailwayData railwayData = RailwayData.getInstance(world);
			if (railwayData != null && state.getBlock() instanceof BlockNode.BlockFreeAngleNode) {
				railwayData.setKeyNode(pos, isKeyNode);
				// Setting only the key node option must not touch the rails
				final RailAngle newAngle = RailAngle.fromAngle(angle);
				final String error = railwayData.isNodeFacing(pos, newAngle) ? null : railwayData.rotateNodeRails(player, pos, newAngle);
				if (error == null) {
					BlockNode.setCustomAngle(world, pos, angle);
					player.displayClientMessage(Text.literal(BlockNode.getAngleText(angle)), true);
				} else {
					// The node renderer rewrites the action bar every frame, so this has to go to chat to be seen
					player.displayClientMessage(Text.translatable(error), false);
				}
				if (turnBackDwell >= 0) {
					railwayData.setTurnBackDwell(pos, Math.min(turnBackDwell, Rail.MAX_TURN_BACK_DWELL));
				}
			}
		});
	}

	/**
	 * Sets or clears a pathfinding boundary on a node, or on the rail from it to another node when the second position
	 * is sent.
	 */
	public static void receivePathBoundaryC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final BlockPos pos1 = packet.readBlockPos();
		final BlockPos pos2 = packet.readBoolean() ? packet.readBlockPos() : null;
		final boolean isBoundary = packet.readBoolean();
		PacketGuard.execute(minecraftServer, () -> {
			if (!canEditBlock(player, pos1) || RailwayData.hasNoPermission(player)) {
				return;
			}
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null) {
				railwayData.setPathBoundary(player, pos1, pos2, isBoundary);
			}
		});
	}

	public static void receiveLiftTrackFloorC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		final String floorNumber = packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
		final String floorDescription = packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
		final boolean shouldDing = packet.readBoolean();
		PacketGuard.execute(minecraftServer, () -> {
			if (!canEditBlock(player, pos)) {
				return;
			}
			final BlockEntity entity = Compat.getLevel(player).getBlockEntity(pos);
			if (entity instanceof BlockLiftTrackFloor.TileEntityLiftTrackFloor) {
				setTileEntityDataAndWriteUpdate(player, entity2 -> entity2.setData(floorNumber, floorDescription, shouldDing), (BlockLiftTrackFloor.TileEntityLiftTrackFloor) entity);
			}
		});
	}

	public static void receiveSignIdsC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final BlockPos signPos = packet.readBlockPos();
		final int selectedIdsLength = PacketGuard.readCount(packet, Long.BYTES, MAX_LIST_SIZE);
		final Set<Long> selectedIds = new HashSet<>();
		for (int i = 0; i < selectedIdsLength; i++) {
			selectedIds.add(packet.readLong());
		}
		final int signLength = PacketGuard.readCount(packet, 1, MAX_STRING_LIST_SIZE);
		final String[] signIds = new String[signLength];
		for (int i = 0; i < signLength; i++) {
			final String signId = packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
			signIds[i] = signId.isEmpty() ? null : signId;
		}

		PacketGuard.execute(minecraftServer, () -> {
			if (!canEditBlock(player, signPos)) {
				return;
			}
			final BlockEntity entity = Compat.getLevel(player).getBlockEntity(signPos);
			if (entity instanceof BlockRailwaySign.TileEntityRailwaySign) {
				setTileEntityDataAndWriteUpdate(player, entity2 -> entity2.setData(selectedIds, signIds), (BlockRailwaySign.TileEntityRailwaySign) entity);
			} else if (entity instanceof BlockRouteSignBase.TileEntityRouteSignBase) {
				final long platformId = selectedIds.isEmpty() ? 0 : (long) selectedIds.toArray()[0];
				final BlockEntity entityAbove = Compat.getLevel(player).getBlockEntity(signPos.above());
				if (entityAbove instanceof BlockRouteSignBase.TileEntityRouteSignBase) {
					setTileEntityDataAndWriteUpdate(player, entity2 -> entity2.setPlatformId(platformId), ((BlockRouteSignBase.TileEntityRouteSignBase) entityAbove), (BlockRouteSignBase.TileEntityRouteSignBase) entity);
				} else {
					setTileEntityDataAndWriteUpdate(player, entity2 -> entity2.setPlatformId(platformId), (BlockRouteSignBase.TileEntityRouteSignBase) entity);
				}
			}
		});
	}

	public static void receiveDriveTrainC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
		if (railwayData != null && Train.isHoldingKey(player)) {
			final boolean pressingAccelerate = packet.readBoolean();
			final boolean pressingBrake = packet.readBoolean();
			final boolean pressingDoors = packet.readBoolean();
			PacketGuard.execute(minecraftServer, () -> railwayData.railwayDataDriveTrainModule.drive(player, pressingAccelerate, pressingBrake, pressingDoors));
		}
	}

	public static void receivePressLiftButtonC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
		if (railwayData != null) {
			final long id = packet.readLong();
			final int floor = packet.readInt();

			PacketGuard.execute(minecraftServer, () -> {
				final Lift lift = railwayData.dataCache.liftsServerIdMap.get(id);
				if (lift != null) {
					lift.pressButton(floor);
				}
			});
		}
	}

	public static void receiveAddBalanceC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		packet.readInt(); // The amount is calculated by the server
		final int emeralds = packet.readInt();
		final int index = Integer.numberOfTrailingZeros(emeralds);

		if (emeralds <= 0 || Integer.bitCount(emeralds) != 1 || index >= TicketSystem.ADD_BALANCE_OPTIONS) {
			return;
		}

		PacketGuard.execute(minecraftServer, () -> {
			final Level world = Compat.getLevel(player);

			if (!isNearTicketMachine(world, player.blockPosition()) || Utilities.getInventory(player).countItem(Items.EMERALD) < emeralds) {
				return;
			}

			ContainerHelper.clearOrCountMatchingItems(Utilities.getInventory(player), itemStack -> itemStack.getItem() == Items.EMERALD, emeralds, false);
			TicketSystem.addObjectivesIfMissing(world);
			final Score balanceScore = TicketSystem.getPlayerScore(world, player, TicketSystem.BALANCE_OBJECTIVE);
			balanceScore.setScore((int) Math.min(Integer.MAX_VALUE, (long) balanceScore.getScore() + TicketSystem.getAddAmount(index)));
			world.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 1, 1);
		});
	}

	public static void receivePIDSMessageC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final BlockPos pos1 = packet.readBlockPos();
		final BlockPos pos2 = packet.readBlockPos();
		final int maxMessages = PacketGuard.readCount(packet, 1, MAX_STRING_LIST_SIZE);
		final String[] messages = new String[maxMessages];
		for (int i = 0; i < maxMessages; i++) {
			messages[i] = packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
		}
		final int maxArrivals = PacketGuard.readCount(packet, 1, MAX_STRING_LIST_SIZE);
		final boolean[] hideArrivals = new boolean[maxArrivals];
		for (int i = 0; i < maxArrivals; i++) {
			hideArrivals[i] = packet.readBoolean();
		}
		final int platformIdCount = PacketGuard.readCount(packet, Long.BYTES, MAX_LIST_SIZE);
		final Set<Long> platformIds = new HashSet<>();
		for (int i = 0; i < platformIdCount; i++) {
			platformIds.add(packet.readLong());
		}
		final int arrivalPage = packet.readInt();
		final boolean showPassingTrains = packet.readBoolean();
		PacketGuard.execute(minecraftServer, () -> {
			if (!canEditBlock(player, pos1) || !canEditBlock(player, pos2)) {
				return;
			}

			final List<IPIDS.TileEntityPIDS> entities = new ArrayList<>();

			final BlockEntity entity1 = Compat.getLevel(player).getBlockEntity(pos1);
			if (entity1 instanceof IPIDS.TileEntityPIDS) {
				entities.add((IPIDS.TileEntityPIDS) entity1);
			}

			final BlockEntity entity2 = Compat.getLevel(player).getBlockEntity(pos2);
			if (entity2 instanceof IPIDS.TileEntityPIDS) {
				entities.add((IPIDS.TileEntityPIDS) entity2);
			}

			setTileEntityDataAndWriteUpdate(player, entity -> entity.setData(messages, hideArrivals, platformIds, arrivalPage, showPassingTrains), entities.toArray(new IPIDS.TileEntityPIDS[0]));
		});
	}

	public static void receiveArrivalProjectorMessageC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final BlockPos pos = packet.readBlockPos();
		final int platformIdCount = PacketGuard.readCount(packet, Long.BYTES, MAX_LIST_SIZE);
		final Set<Long> platformIds = new HashSet<>();
		for (int i = 0; i < platformIdCount; i++) {
			platformIds.add(packet.readLong());
		}
		final int displayPage = packet.readInt();
		PacketGuard.execute(minecraftServer, () -> {
			if (!canEditBlock(player, pos)) {
				return;
			}
			final BlockEntity entity = Compat.getLevel(player).getBlockEntity(pos);
			if (entity instanceof BlockArrivalProjectorBase.TileEntityArrivalProjectorBase) {
				setTileEntityDataAndWriteUpdate(player, entity2 -> entity2.setData(platformIds, displayPage), ((BlockArrivalProjectorBase.TileEntityArrivalProjectorBase) entity));
			}
		});
	}

	public static void receiveUseTimeAndWindSyncC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		if (RailwayData.hasNoPermission(player) || !player.hasPermissions(1)) {
			return;
		}

		final Level world = Compat.getLevel(player);
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData != null) {
			final boolean useTimeAndWindSync = packet.readBoolean();
			PacketGuard.execute(minecraftServer, () -> {
				final boolean useTimeAndWindSyncOld = railwayData.getUseTimeAndWindSync();
				railwayData.setUseTimeAndWindSync(useTimeAndWindSync);
				final String key = "\"use_time_and_wind_sync\":";
				railwayData.railwayDataLoggingModule.addEvent(player, RailwayData.class, Collections.singletonList(key + useTimeAndWindSyncOld), Collections.singletonList(key + useTimeAndWindSync));
			});
		}
	}

	/**
	 * Switches every train on the server between the new and the old rules, for comparing them (decoupling document 5.2, S2).
	 */
	public static void receiveUseLegacyMotionC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		if (RailwayData.hasNoPermission(player) || !player.hasPermissions(1)) {
			return;
		}

		final Level world = Compat.getLevel(player);
		final RailwayData railwayData = RailwayData.getInstance(world);
		final boolean useLegacyMotion = packet.readBoolean();
		final int componentDrive = packet.readInt();
		PacketGuard.execute(minecraftServer, () -> {
			final boolean useLegacyMotionOld = MotionConfig.useLegacyMotion(false);
			final int componentDriveOld = MotionConfig.getComponentDrive(false);
			if (useLegacyMotionOld == useLegacyMotion && componentDriveOld == componentDrive) {
				return;
			}
			MotionConfig.set(useLegacyMotion, componentDrive);
			if (useLegacyMotionOld != useLegacyMotion) {
				System.out.println("Minecraft Transit Railway: " + player.getName().getString() + " switched trains to the " + (useLegacyMotion ? "old" : "new") + " motion rules");
			}
			if (componentDriveOld != MotionConfig.getComponentDrive(false)) {
				System.out.println("Minecraft Transit Railway: " + player.getName().getString() + " set the component's simulator to " + new String[]{"off", "compare", "run trains"}[MotionConfig.getComponentDrive(false)]);
			}
			if (railwayData != null) {
				final String key = "\"use_legacy_motion\":";
				final String driveKey = "\"component_drive\":";
				railwayData.railwayDataLoggingModule.addEvent(player, RailwayData.class, Collections.singletonList(key + useLegacyMotionOld + "," + driveKey + componentDriveOld), Collections.singletonList(key + useLegacyMotion + "," + driveKey + MotionConfig.getComponentDrive(false)));
			}
			minecraftServer.getPlayerList().getPlayers().forEach(PacketTrainDataGuiServer::motionConfigS2C);
		});
	}

	public static void receiveRemoveRailAction(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		if (RailwayData.hasNoPermission(player)) {
			return;
		}

		final Level world = Compat.getLevel(player);
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData != null) {
			final long id = packet.readLong();
			PacketGuard.execute(minecraftServer, () -> railwayData.railwayDataRailActionsModule.removeRailAction(id));
		}
	}

	public static void receiveUpdateTrainPassengerPosition(MinecraftServer minecraftServer, Player player, FriendlyByteBuf packet) {
		relayPassengerPosition(minecraftServer, player, packet, PACKET_UPDATE_TRAIN_PASSENGER_POSITION);
	}

	public static void receiveUpdateLiftPassengerPosition(MinecraftServer minecraftServer, Player player, FriendlyByteBuf packet) {
		relayPassengerPosition(minecraftServer, player, packet, PACKET_UPDATE_LIFT_PASSENGER_POSITION);
	}

	public static void receiveUpdateEntitySeatPassengerPosition(MinecraftServer minecraftServer, Player player, FriendlyByteBuf packet) {
		final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
		if (railwayData != null) {
			final double x = packet.readDouble();
			final double y = packet.readDouble();
			final double z = packet.readDouble();
			if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
				PacketGuard.execute(minecraftServer, () -> railwayData.railwayDataCoolDownModule.moveSeat(player, x, y, z));
			}
		}
	}

	private static void relayPassengerPosition(MinecraftServer minecraftServer, Player player, FriendlyByteBuf packet, ResourceLocation packetId) {
		final long id = packet.readLong();
		final float percentageX = packet.readFloat();
		final float percentageZ = packet.readFloat();
		final UUID uuid = packet.readUUID();

		// The forwarded packet is rebuilt so that clients only ever receive well-formed data about the sender
		if (uuid.equals(player.getUUID()) && Float.isFinite(percentageX) && Float.isFinite(percentageZ)) {
			final FriendlyByteBuf newPacket = new FriendlyByteBuf(Unpooled.buffer());
			newPacket.writeLong(id);
			newPacket.writeFloat(percentageX);
			newPacket.writeFloat(percentageZ);
			newPacket.writeUUID(uuid);
			PacketGuard.execute(minecraftServer, () -> Compat.getLevel(player).players().forEach(sendPlayer -> {
				if (sendPlayer != player) {
					Registry.sendToPlayer((ServerPlayer) sendPlayer, packetId, newPacket);
				}
			}));
		}
	}

	private static boolean canEditBlock(ServerPlayer player, BlockPos pos) {
		return !RailwayData.hasNoPermission(player) && Compat.getLevel(player).hasChunkAt(pos) && player.blockPosition().distSqr(pos) <= MAX_EDIT_DISTANCE * MAX_EDIT_DISTANCE;
	}

	private static boolean isNearTicketMachine(Level world, BlockPos pos) {
		for (final BlockPos checkPos : BlockPos.betweenClosed(pos.offset(-TICKET_MACHINE_DISTANCE, -TICKET_MACHINE_DISTANCE, -TICKET_MACHINE_DISTANCE), pos.offset(TICKET_MACHINE_DISTANCE, TICKET_MACHINE_DISTANCE, TICKET_MACHINE_DISTANCE))) {
			if (world.getBlockState(checkPos).getBlock() instanceof BlockTicketMachine) {
				return true;
			}
		}
		return false;
	}

	private static <T extends SerializedDataBase> void serializeData(FriendlyByteBuf packet, Collection<T> objects) {
		packet.writeInt(objects.size());
		objects.forEach(object -> object.writePacket(packet));
	}

	private static boolean sendChunk(ServerPlayer player, FriendlyByteBuf packet, long tempPacketId, int chunk) {
		final FriendlyByteBuf packetChunk = new FriendlyByteBuf(Unpooled.buffer());
		packetChunk.writeLong(tempPacketId);
		packetChunk.writeInt(chunk);

		final boolean success = chunk * PACKET_CHUNK_SIZE > packet.readableBytes();
		packetChunk.writeBoolean(success);
		if (!success) {
			packetChunk.writeBytes(packet.copy(chunk * PACKET_CHUNK_SIZE, Math.min(PACKET_CHUNK_SIZE, packet.readableBytes() - chunk * PACKET_CHUNK_SIZE)));
		}

		try {
			Registry.sendToPlayer(player, PACKET_CHUNK_S2C, packetChunk);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return success;
	}

	@SafeVarargs
	private static <T extends BlockEntityMapper> void setTileEntityDataAndWriteUpdate(ServerPlayer player, Consumer<T> setData, T... entities) {
		final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));

		if (railwayData != null && entities.length > 0) {
			final CompoundTag compoundTagOld = new CompoundTag();
			entities[0].writeCompoundTag(compoundTagOld);

			BlockPos blockPos = null;
			long posLong = 0;
			for (final T entity : entities) {
				setData.accept(entity);
				final BlockPos entityPos = entity.getBlockPos();
				if (blockPos == null || entityPos.asLong() > posLong) {
					blockPos = entityPos;
					posLong = entityPos.asLong();
				}
			}

			final CompoundTag compoundTagNew = new CompoundTag();
			entities[0].writeCompoundTag(compoundTagNew);

			railwayData.railwayDataLoggingModule.addEvent(player, entities[0].getClass(), RailwayDataLoggingModule.getData(compoundTagOld), RailwayDataLoggingModule.getData(compoundTagNew), blockPos);
		}
	}
}
