package mtr.render;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.Items;
import mtr.block.BlockArrivalProjectorBase;
import mtr.block.BlockTrainScheduleSensor;
import mtr.client.ClientData;
import mtr.client.Config;
import mtr.data.IGui;
import mtr.data.IPIDS;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.ScheduleEntry;
import mtr.data.StationCall;
import mtr.compat.GuiCompat;
import mtr.data.TrainClient;
import mtr.mappings.Text;
import mtr.packet.TrainDebugPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The debug display (0.65.31): holding the brush and looking at a train, its planned and expected times, how late it is,
 * how long it has stood and until the doors close, why it is held and how fast it may go, as the server has them
 * ({@link TrainDebugPackets}), at the top right of the screen.
 */
public final class RenderTrainDebug {

	private static final double MAX_DISTANCE = 96;
	private static final double MAX_AIM_DISTANCE = 3;
	private static final long STALE_MILLIS = 3000;
	private static final int FIND_EVERY_FRAMES = 4;
	private static final int PADDING = 4;
	private static final int LINE_HEIGHT = 10;

	private static int frames;
	private static long aimedTrain;

	private RenderTrainDebug() {
	}

	public static void render(PoseStack matrices) {
		final Minecraft client = Minecraft.getInstance();
		final LocalPlayer player = client.player;
		final Window window = client.getWindow();
		if (player == null || window == null || client.options.hideGui || !player.isHolding(Items.BRUSH.get()) && !Config.showDebugInfo()) {
			aimedTrain = 0;
			return;
		}
		if (frames++ % FIND_EVERY_FRAMES == 0) {
			aimedTrain = findAimedTrain(player.getEyePosition(1), player.getViewVector(1));
		}
		// A passenger display, arrival projector or schedule sensor looked at, when no train is or it is close (0.66.4)
		final HitResult hit = client.hitResult;
		if (hit instanceof BlockHitResult && hit.getType() == HitResult.Type.BLOCK && client.level != null) {
			final BlockPos pos = ((BlockHitResult) hit).getBlockPos();
			final List<String> blockTexts = blockLines(client.level.getBlockEntity(pos), pos);
			if (blockTexts != null && (aimedTrain == 0 || hit.getLocation().distanceTo(player.getEyePosition(1)) < 4)) {
				draw(matrices, client, window, blockTexts);
				return;
			}
		}
		if (aimedTrain == 0) {
			return;
		}
		TrainDebugPackets.sendRequestC2S(aimedTrain);
		if (TrainDebugPackets.clientTrainId != aimedTrain || System.currentTimeMillis() - TrainDebugPackets.clientReceivedMillis > STALE_MILLIS) {
			return;
		}

		final List<String> texts = new ArrayList<>();
		texts.add(Text.translatable("gui.mtr.train_debug_title").getString());
		final List<String[]> lines = TrainDebugPackets.clientLines;
		if (lines.isEmpty()) {
			texts.add(Text.translatable("gui.mtr.train_debug_value_gone").getString());
		}
		lines.forEach(line -> texts.add(Text.translatable("gui.mtr.train_debug_" + line[0]).getString() + ": " + value(line[1])));
		draw(matrices, client, window, texts);
	}

	private static void draw(PoseStack matrices, Minecraft client, Window window, List<String> texts) {
		int width = 0;
		for (final String text : texts) {
			width = Math.max(width, client.font.width(text));
		}
		final int right = window.getGuiScaledWidth() - PADDING;
		final int left = right - width - PADDING * 2;
		final int top = PADDING;
		GuiCompat.fill(matrices, left, top, right, top + texts.size() * LINE_HEIGHT + PADDING * 2, 0xA0000000);
		for (int i = 0; i < texts.size(); i++) {
			GuiCompat.drawShadow(matrices, client.font, texts.get(i), left + PADDING, top + PADDING + i * LINE_HEIGHT, i == 0 ? 0xFFFFDD55 : 0xFFFFFFFF);
		}
	}

	/**
	 * What a passenger display, arrival projector or schedule sensor reads (0.66.4): its platforms and the arrivals there
	 * as this client has them, marked as the display goes by them; a sensor's trigger, filter and whether it would go off.
	 *
	 * @return the lines, or null if it is none of these
	 */
	private static List<String> blockLines(BlockEntity entity, BlockPos pos) {
		final Set<Long> platformIds = new TreeSet<>();
		final List<String> texts = new ArrayList<>();
		Set<Long> routeFilter = Collections.emptySet();
		int sensorSeconds = -1;
		StationCall.Trigger trigger = null;
		boolean showPassing = true;
		if (entity instanceof IPIDS.TileEntityPIDS) {
			final IPIDS.TileEntityPIDS pids = (IPIDS.TileEntityPIDS) entity;
			texts.add(Text.translatable("gui.mtr.block_debug_pids").getString());
			platformIds.addAll(pids.getPlatformIds());
			if (platformIds.isEmpty()) {
				platformIds.add(pids.getPlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE));
			}
			showPassing = pids.getShowPassingTrains();
		} else if (entity instanceof BlockArrivalProjectorBase.TileEntityArrivalProjectorBase) {
			texts.add(Text.translatable("gui.mtr.block_debug_projector").getString());
			platformIds.addAll(((BlockArrivalProjectorBase.TileEntityArrivalProjectorBase) entity).getPlatformIds());
		} else if (entity instanceof BlockTrainScheduleSensor.TileEntityTrainScheduleSensor) {
			final BlockTrainScheduleSensor.TileEntityTrainScheduleSensor sensor = (BlockTrainScheduleSensor.TileEntityTrainScheduleSensor) entity;
			texts.add(Text.translatable("gui.mtr.block_debug_sensor").getString());
			platformIds.add(RailwayData.getClosePlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE, pos, 4, 4, 0));
			sensorSeconds = sensor.getSeconds();
			trigger = sensor.getTrigger();
			routeFilter = sensor.getRouteIds();
			texts.add(Text.translatable("gui.mtr.block_debug_sensor_settings", Text.translatable("gui.mtr.station_trigger_" + trigger.name().toLowerCase(java.util.Locale.ROOT)).getString(), sensorSeconds, routeFilter.isEmpty() ? Text.translatable("gui.mtr.block_debug_all_routes").getString() : String.valueOf(routeFilter.size())).getString());
		} else {
			return null;
		}
		platformIds.remove(0L);
		final StringBuilder platformText = new StringBuilder();
		platformIds.forEach(platformId -> platformText.append(platformText.length() == 0 ? "" : ", ").append(platformName(platformId)));
		texts.add(Text.translatable("gui.mtr.block_debug_platforms", platformIds.isEmpty() ? Text.translatable("gui.mtr.block_debug_all_platforms").getString() : platformText.toString()).getString());
		final long now = System.currentTimeMillis();
		final List<ScheduleEntry> entries = new ArrayList<>();
		final java.util.Map<ScheduleEntry, Long> entryPlatforms = new java.util.IdentityHashMap<>();
		for (final long platformId : platformIds) {
			final Set<ScheduleEntry> schedules = ClientData.ALL_SCHEDULES_FOR_PLATFORM.get(platformId);
			if (schedules != null) {
				entries.addAll(schedules);
				schedules.forEach(entry -> entryPlatforms.put(entry, platformId));
			}
		}
		Collections.sort(entries);
		if (entries.isEmpty()) {
			texts.add(Text.translatable("gui.mtr.block_debug_no_arrivals").getString());
		}
		final Set<Long> filter = routeFilter;
		ScheduleEntry firstMatching = null;
		for (final ScheduleEntry entry : entries) {
			if (firstMatching == null && (filter.isEmpty() || filter.contains(entry.routeId))) {
				firstMatching = entry;
			}
		}
		for (int i = 0; i < entries.size() && i < 8; i++) {
			final ScheduleEntry entry = entries.get(i);
			final Route route = ClientData.DATA_CACHE.routeIdMap.get(entry.routeId);
			final long seconds = (entry.arrivalMillis - now) / 1000;
			// Marked with a cross where the display leaves it out
			final boolean shown = (showPassing || !entry.pass) && (!entry.planned || entry.arrivalMillis > now);
			texts.add((shown ? "" : "× ") + (route == null ? String.valueOf(entry.routeId) : IGui.formatStationName(route.name)) + "  " + (seconds >= 0 ? Text.translatable("gui.mtr.block_debug_in", seconds).getString() : Text.translatable("gui.mtr.block_debug_arrived", -seconds).getString()) + (entry.pass ? " " + Text.translatable("gui.mtr.block_debug_pass").getString() : "") + (entry.planned ? " " + Text.translatable("gui.mtr.block_debug_planned").getString() : ""));
			// The times as sent, to set against the train's own debug panel: the arrival, the departure displays work out from
			// it with the platform's stop time, the planned time and the train
			final mtr.data.Platform platform = ClientData.DATA_CACHE.platformIdMap.get(entryPlatforms.getOrDefault(entry, 0L));
			// A train standing there tells when it leaves (negative: its planned departure, held for something else too)
			final long departure = entry.departureMillis != 0 ? Math.abs(entry.departureMillis) : entry.arrivalMillis + (platform == null ? 0 : platform.getDwellTime() * 500L);
			final String trainId = String.valueOf(entry.trainId);
			texts.add("    " + Text.translatable("gui.mtr.block_debug_times", clock(entry.arrivalMillis), clock(departure), entry.scheduledMillis > 0 ? clock(entry.scheduledMillis) : "-", entry.trainId == 0 ? "-" : trainId.substring(Math.max(0, trainId.length() - 4))).getString());
		}
		if (trigger == StationCall.Trigger.LEGACY) {
			final boolean fires = firstMatching != null && firstMatching.arrivalMillis >= now && (firstMatching.arrivalMillis - now) / 1000 <= sensorSeconds;
			texts.add(Text.translatable(fires ? "gui.mtr.block_debug_fires" : "gui.mtr.block_debug_waits").getString());
		} else if (trigger != null) {
			texts.add(Text.translatable("gui.mtr.block_debug_server_trigger").getString());
		}
		return texts;
	}

	private static String clock(long millis) {
		return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(millis));
	}

	private static String platformName(long platformId) {
		final mtr.data.Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformId);
		final mtr.data.Station station = ClientData.DATA_CACHE.platformIdToStation.get(platformId);
		return platform == null ? String.valueOf(platformId) : (station == null ? "" : IGui.formatStationName(station.name) + " ") + platform.name;
	}

	/**
	 * A value as sent, each word starting with "#" put in the player's language.
	 */
	static String value(String value) {
		if (value.indexOf('#') < 0) {
			return value;
		}
		final StringBuilder builder = new StringBuilder();
		for (final String word : value.split(" ")) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(word.startsWith("#") ? Text.translatable("gui.mtr.train_debug_value_" + word.substring(1)).getString() : word);
		}
		return builder.toString();
	}

	/**
	 * @return the train whose cars are nearest the line the player looks along, or 0
	 */
	private static long findAimedTrain(Vec3 eye, Vec3 look) {
		long best = 0;
		double bestAlong = Double.MAX_VALUE;
		for (final TrainClient train : ClientData.TRAINS) {
			for (int car = 0; car < train.trainCars; car++) {
				final Vec3 position = train.getTrackPosition(car, 0);
				if (position == null) {
					continue;
				}
				final Vec3 offset = position.add(0, 1.5, 0).subtract(eye);
				final double along = offset.dot(look);
				if (along < 0 || along > MAX_DISTANCE) {
					continue;
				}
				if (offset.subtract(look.scale(along)).length() < MAX_AIM_DISTANCE && along < bestAlong) {
					bestAlong = along;
					best = train.id;
				}
			}
		}
		return best;
	}
}
