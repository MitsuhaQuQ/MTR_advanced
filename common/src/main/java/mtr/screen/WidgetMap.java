package mtr.screen;

import mtr.compat.GuiCompat;
import mtr.compat.WidgetBase;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.client.MapTerrainCache;
import mtr.data.*;
import mtr.mappings.SelectableMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.function.Supplier;


public class WidgetMap implements WidgetBase, SelectableMapper, GuiEventListener, IGui {

	private int x;
	private int y;
	private int width;
	private int height;
	private double scale;
	private double centerX;
	private double centerY;
	private Tuple<Integer, Integer> drawArea1, drawArea2;
	private MapState mapState;
	private boolean showStations;

	private final TransportMode transportMode;
	private final OnDrawCorners onDrawCorners;
	private final Runnable onDrawCornersMouseRelease;
	private final Consumer<Long> onClickAddPlatformToRoute;
	private final Consumer<BlockPos> onClickAddKeyNodeToRoute;
	private String areaNotice = "";
	private Supplier<Collection<Route>> routesToDraw = Collections::emptyList;
	private BooleanSupplier canPickRoute = () -> false;
	private Consumer<Route> onPickRoute = route -> {
	};
	private String selectedLineKey = "";
	private List<Route> hoveredRoutes = Collections.emptyList();
	private Set<String> hoveredLineKeys = Collections.emptySet();
	private BooleanSupplier canPickStation = () -> false;
	private Consumer<Station> onPickStation = station -> {
	};
	private long selectedStationId;
	private List<Station> hoveredStations = Collections.emptyList();
	private BooleanSupplier canPickDepot = () -> false;
	private Consumer<Depot> onPickDepot = depot -> {
	};
	private long selectedDepotId;
	private List<Depot> hoveredDepots = Collections.emptyList();
	private double hoverMouseX = -1;
	private double hoverMouseY = -1;
	private double hoverScale;
	private double hoverCenterX;
	private double hoverCenterY;
	private double pressMouseX;
	private double pressMouseY;
	private final Consumer<SavedRailBase> onClickEditSavedRail;
	private final BiFunction<Double, Double, Boolean> isRestrictedMouseArea;
	private final ClientLevel world;
	private final LocalPlayer player;
	private final Font textRenderer;

	private static final double KEY_NODE_MARKER_PIXELS = 3;
	private static final double KEY_NODE_CLICK_PIXELS = 8;
	private static final double RAIL_LINE_PIXELS = 1;
	private static final double ROUTE_STOP_MARKER_PIXELS = 4;
	private static final double ROUTE_LINE_PIXELS = 2;
	private static final double ROUTE_HIGHLIGHT_PIXELS = 4;
	private static final double ROUTE_PICK_PIXELS = 5;
	private static final double CLICK_MAX_MOVE_PIXELS = 3;
	private static final int ARGB_RAIL_LINE = 0x50FFFFFF;
	private static final int ARGB_BOUNDARY = 0xFF7A5FD0;
	private static final int ARGB_CONNECTING_LINE = 0xFFE8891A;
	private static final int ARGB_RELEASED = 0xA0999999;
	private static final double ISOLATION_LINE_PIXELS = 2;
	private static final int ARGB_AREA_HIGHLIGHT = 0x50FFFFFF;
	private static final int ARGB_ROUTE_LINE_ALPHA = 0xB0000000;
	private static final int ARGB_KEY_NODE = 0xFFFFFF00;
	private static final int ARGB_KEY_NODE_PICKED = 0xFF00FF00;
	private static final int ARGB_BLUE = 0xFF4285F4;
	private static final int SCALE_UPPER_LIMIT = 64;
	private static final double SCALE_LOWER_LIMIT = 1 / 128D;

	public WidgetMap(TransportMode transportMode, OnDrawCorners onDrawCorners, Runnable onDrawCornersMouseRelease, Consumer<Long> onClickAddPlatformToRoute, Consumer<BlockPos> onClickAddKeyNodeToRoute, Consumer<SavedRailBase> onClickEditSavedRail, BiFunction<Double, Double, Boolean> isRestrictedMouseArea) {
		this.transportMode = transportMode;
		this.onDrawCorners = onDrawCorners;
		this.onDrawCornersMouseRelease = onDrawCornersMouseRelease;
		this.onClickAddPlatformToRoute = onClickAddPlatformToRoute;
		this.onClickAddKeyNodeToRoute = onClickAddKeyNodeToRoute;
		this.onClickEditSavedRail = onClickEditSavedRail;
		this.isRestrictedMouseArea = isRestrictedMouseArea;

		final Minecraft minecraftClient = Minecraft.getInstance();
		world = minecraftClient.level;
		player = minecraftClient.player;
		textRenderer = minecraftClient.font;
		if (player == null) {
			centerX = 0;
			centerY = 0;
		} else {
			centerX = player.getX();
			centerY = player.getZ();
		}
		scale = 1;
		setShowStations(true);
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		final Tesselator tesselator = Tesselator.getInstance();
		final BufferBuilder buffer = tesselator.getBuilder();
		UtilitiesClient.beginDrawingRectangle(buffer);
		RenderSystem.enableBlend();

		if (world != null) {
			MapTerrainCache.scan(world);
		}

		final Tuple<Integer, Integer> topLeft = coordsToWorldPos(0, 0);
		final Tuple<Integer, Integer> bottomRight = coordsToWorldPos(width, height);
		final int increment = scale >= 1 ? 1 : (int) Math.ceil(1 / scale);
		for (int i = topLeft.getA(); i <= bottomRight.getA(); i += increment) {
			for (int j = topLeft.getB(); j <= bottomRight.getB(); j += increment) {
				if (world != null) {
					final int color = divideColorRGB(MapTerrainCache.getColor(world, i, j), 2);
					drawRectangleFromWorldCoords(buffer, i, j, i + increment, j + increment, ARGB_BLACK | color);
				}
			}
		}

		final Tuple<Double, Double> mouseWorldPos = coordsToWorldPos((double) mouseX - x, mouseY - y);

		try {
			drawRails(buffer);
			drawIsolation(buffer);
			drawDepotPaths(buffer);
			routesToDraw.get().forEach(route -> drawRoute(buffer, route));
			updateHoveredRoutes(mouseX, mouseY, mouseWorldPos);
			if (!selectedLineKey.isEmpty() || !hoveredRoutes.isEmpty()) {
				routesToDraw.get().forEach(route -> {
					final String lineKey = route.getLineKey();
					if (lineKey.equals(selectedLineKey) || hoveredLineKeys.contains(lineKey)) {
						drawRouteNodes(buffer, getRouteNodes(route), ROUTE_HIGHLIGHT_PIXELS, ARGB_BLACK | route.color);
					}
				});
			}
			if (showStations) {
				ClientData.DATA_CACHE.getPosToPlatforms(transportMode).forEach((platformPos, platforms) -> drawRectangleFromWorldCoords(buffer, platformPos.getX(), platformPos.getZ(), platformPos.getX() + 1, platformPos.getZ() + 1, ARGB_WHITE));
				for (final Station station : ClientData.STATIONS) {
					if (AreaBase.nonNullCorners(station)) {
						for (final int[] rectangle : station.getRectangles()) {
							drawRectangleFromWorldCoords(buffer, new Tuple<>(rectangle[0], rectangle[1]), new Tuple<>(rectangle[2], rectangle[3]), ARGB_BLACK_TRANSLUCENT + station.color);
							if (station.id == selectedStationId || hoveredStations.contains(station)) {
								drawRectangleFromWorldCoords(buffer, new Tuple<>(rectangle[0], rectangle[1]), new Tuple<>(rectangle[2], rectangle[3]), ARGB_AREA_HIGHLIGHT);
							}
						}
					}
				}
				mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> drawRectangleFromWorldCoords(buffer, x1, z1, x2, z2, ARGB_WHITE), true);
			} else {
				ClientData.DATA_CACHE.getPosToSidings(transportMode).forEach((sidingPos, sidings) -> drawRectangleFromWorldCoords(buffer, sidingPos.getX(), sidingPos.getZ(), sidingPos.getX() + 1, sidingPos.getZ() + 1, ARGB_WHITE));
				for (final Depot depot : ClientData.DEPOTS) {
					if (depot.isTransportMode(transportMode) && AreaBase.nonNullCorners(depot)) {
						drawRectangleFromWorldCoords(buffer, depot.corner1, depot.corner2, ARGB_BLACK_TRANSLUCENT + depot.color);
						if (depot.id == selectedDepotId || hoveredDepots.contains(depot)) {
							drawRectangleFromWorldCoords(buffer, depot.corner1, depot.corner2, ARGB_AREA_HIGHLIGHT);
						}
					}
				}
				mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> drawRectangleFromWorldCoords(buffer, x1, z1, x2, z2, ARGB_WHITE), false);
			}
			if (mapState == MapState.EDITING_ROUTE) {
				routesToDraw.get().forEach(route -> drawRouteHighlights(buffer, route));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (mapState == MapState.EDITING_AREA && drawArea1 != null && drawArea2 != null) {
			drawRectangleFromWorldCoords(buffer, drawArea1, drawArea2, ARGB_WHITE_TRANSLUCENT);
		}

		if (player != null) {
			drawFromWorldCoords(player.getX(), player.getZ(), (x1, y1) -> {
				drawRectangle(buffer, x1 - 2, y1 - 3, x1 + 2, y1 + 3, ARGB_WHITE);
				drawRectangle(buffer, x1 - 3, y1 - 2, x1 + 3, y1 + 2, ARGB_WHITE);
				drawRectangle(buffer, x1 - 2, y1 - 2, x1 + 2, y1 + 2, ARGB_BLUE);
			});
		}

		tesselator.end();
		RenderSystem.disableBlend();
		UtilitiesClient.finishDrawingRectangle();

		// The hint along the top, which the mouse position on the right keeps clear of
		final String topText;
		if (mapState == MapState.EDITING_AREA) {
			topText = Text.translatable("gui.mtr.edit_area").getString();
		} else if (mapState == MapState.EDITING_ROUTE) {
			topText = Text.translatable("gui.mtr.edit_route_key_nodes").getString();
		} else if (canPickRoute.getAsBoolean()) {
			topText = Text.translatable("gui.mtr.map_pick_route").getString();
		} else if (canPickStation.getAsBoolean()) {
			topText = Text.translatable("gui.mtr.map_pick_station").getString();
		} else if (canPickDepot.getAsBoolean()) {
			topText = Text.translatable("gui.mtr.map_pick_depot").getString();
		} else {
			topText = "";
		}
		if (!topText.isEmpty()) {
			GuiCompat.drawString(matrices, textRenderer, topText, x + TEXT_PADDING, y + TEXT_PADDING, ARGB_WHITE);
		}
		if (mapState == MapState.EDITING_AREA) {
			if (!areaNotice.isEmpty()) {
				GuiCompat.drawString(matrices, textRenderer, areaNotice, x + TEXT_PADDING, y + TEXT_PADDING + SQUARE_SIZE, ARGB_KEY_NODE);
			}
		}

		if (scale >= 8) {
			try {
				if (showStations) {
					ClientData.DATA_CACHE.getPosToPlatforms(transportMode).forEach((platformPos, platforms) -> drawSavedRail(matrices, platformPos, platforms));
				} else {
					ClientData.DATA_CACHE.getPosToSidings(transportMode).forEach((sidingPos, sidings) -> drawSavedRail(matrices, sidingPos, sidings));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		final MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
		if (showStations) {
			for (final Station station : ClientData.STATIONS) {
				if (canDrawAreaText(station)) {
					final BlockPos pos = station.getCenter();
					final String stationString = String.format("%s|(%s)", station.name, Text.translatable("gui.mtr.zone_number", station.zone).getString());
					drawFromWorldCoords(pos.getX(), pos.getZ(), (x1, y1) -> IDrawing.drawStringWithFont(matrices, textRenderer, immediate, stationString, x + x1.floatValue(), y + y1.floatValue(), MAX_LIGHT_GLOWING));
				}
			}
		} else {
			for (final Depot depot : ClientData.DEPOTS) {
				if (canDrawAreaText(depot)) {
					final BlockPos pos = depot.getCenter();
					drawFromWorldCoords(pos.getX(), pos.getZ(), (x1, y1) -> IDrawing.drawStringWithFont(matrices, textRenderer, immediate, depot.name, x + x1.floatValue(), y + y1.floatValue(), MAX_LIGHT_GLOWING));
				}
			}
		}
		if (mapState == MapState.EDITING_ROUTE) {
			routesToDraw.get().forEach(route -> {
				final List<Object> entries = route.getEntries();
				for (int i = 0; i < entries.size(); i++) {
					if (entries.get(i) instanceof BlockPos) {
						final String entryNumber = String.valueOf(i + 1);
						final BlockPos pos = (BlockPos) entries.get(i);
						drawFromWorldCoords(pos.getX() + 0.5, pos.getZ() + 0.5, (x1, y1) -> IDrawing.drawStringWithFont(matrices, textRenderer, immediate, entryNumber, x + x1.floatValue(), y + y1.floatValue() - (float) KEY_NODE_MARKER_PIXELS * 2, MAX_LIGHT_GLOWING));
					}
				}
			});
		}
		immediate.endBatch();

		if (!hoveredRoutes.isEmpty() || !hoveredStations.isEmpty() || !hoveredDepots.isEmpty()) {
			final List<? extends AreaBase> hoveredAreas = hoveredStations.isEmpty() ? hoveredDepots : hoveredStations;
			final String names = hoveredRoutes.isEmpty() ? hoveredAreas.stream().map(area -> IGui.formatStationName(area.name)).collect(Collectors.joining(" / ")) : hoveredRoutes.stream().map(route -> IGui.formatStationName(route.getLineName())).collect(Collectors.joining(" / "));
			final int textX = Math.min(mouseX + TEXT_PADDING * 2, x + width - textRenderer.width(names) - TEXT_PADDING);
			GuiCompat.drawString(matrices, textRenderer, names, textX, mouseY - TEXT_HEIGHT - TEXT_PADDING, ARGB_WHITE);
		}

		final String mousePosText = String.format("(%s, %s)", RailwayData.round(mouseWorldPos.getA(), 1), RailwayData.round(mouseWorldPos.getB(), 1));
		final int mousePosX = x + width - TEXT_PADDING - textRenderer.width(mousePosText);
		// Down in the corner below when the hint along the top would run into it
		final boolean clearOfTopText = topText.isEmpty() || x + TEXT_PADDING * 3 + textRenderer.width(topText) <= mousePosX;
		GuiCompat.drawString(matrices, textRenderer, mousePosText, mousePosX, clearOfTopText ? y + TEXT_PADDING : y + height - TEXT_PADDING - TEXT_HEIGHT, ARGB_WHITE);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (mapState == MapState.EDITING_AREA) {
			drawArea2 = coordsToWorldPos((int) Math.round(mouseX - x), (int) Math.round(mouseY - y));
			if (drawArea1.getA().equals(drawArea2.getA())) {
				drawArea2 = new Tuple<>(drawArea2.getA() + 1, drawArea2.getB());
			}
			if (drawArea1.getB().equals(drawArea2.getB())) {
				drawArea2 = new Tuple<>(drawArea2.getA(), drawArea2.getB() + 1);
			}
			onDrawCorners.onDrawCorners(drawArea1, drawArea2);
		} else {
			centerX -= deltaX / scale;
			centerY -= deltaY / scale;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (mapState == MapState.EDITING_AREA) {
			onDrawCornersMouseRelease.run();
		} else if (isMouseOver(mouseX, mouseY) && Math.hypot(mouseX - pressMouseX, mouseY - pressMouseY) <= CLICK_MAX_MOVE_PIXELS) {
			if (canPickRoute.getAsBoolean()) {
				pickRoute(coordsToWorldPos(mouseX - x, mouseY - y));
			} else if (canPickStation.getAsBoolean()) {
				pickArea(getStationsAt(coordsToWorldPos(mouseX - x, mouseY - y)), selectedStationId, onPickStation);
			} else if (canPickDepot.getAsBoolean()) {
				pickArea(getDepotsAt(coordsToWorldPos(mouseX - x, mouseY - y)), selectedDepotId, onPickDepot);
			}
		}
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (isMouseOver(mouseX, mouseY)) {
			pressMouseX = mouseX;
			pressMouseY = mouseY;
			if (ClientData.hasPermission()) {
				if (mapState == MapState.EDITING_AREA) {
					drawArea1 = coordsToWorldPos((int) (mouseX - x), (int) (mouseY - y));
					drawArea2 = null;
				} else if (mapState == MapState.EDITING_ROUTE) {
					final Tuple<Double, Double> mouseWorldPos = coordsToWorldPos(mouseX - x, mouseY - y);
					final boolean[] clickedPlatform = {false};
					mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> {
						clickedPlatform[0] = true;
						onClickAddPlatformToRoute.accept(savedRail.id);
					}, true);
					if (!clickedPlatform[0]) {
						clickKeyNode(mouseWorldPos);
					}
				} else {
					final Tuple<Double, Double> mouseWorldPos = coordsToWorldPos(mouseX - x, mouseY - y);
					mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> onClickEditSavedRail.accept(savedRail), showStations);
				}
			}
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		final double oldScale = scale;
		if (oldScale > SCALE_LOWER_LIMIT && amount < 0) {
			centerX -= (mouseX - x - width / 2D) / scale;
			centerY -= (mouseY - y - height / 2D) / scale;
		}
		scale(amount);
		if (oldScale < SCALE_UPPER_LIMIT && amount > 0) {
			centerX += (mouseX - x - width / 2D) / scale;
			centerY += (mouseY - y - height / 2D) / scale;
		}
		return true;
	}

	/**
	 * The bottom right corner holds the dashboard's buttons, from the region filter to zoom, so clicks there go to them.
	 */
	private int bottomButtonsWidth = SQUARE_SIZE * 22;

	public void setBottomButtonsWidth(int bottomButtonsWidth) {
		this.bottomButtonsWidth = bottomButtonsWidth;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height && !(mouseX >= x + width - bottomButtonsWidth && mouseY >= y + height - SQUARE_SIZE) && !isRestrictedMouseArea.apply(mouseX, mouseY);
	}

	public void setFocused(boolean focused) {
	}

	public boolean isFocused() {
		return false;
	}

	public void setPositionAndSize(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	public void scale(double amount) {
		scale *= Math.pow(2, amount);
		scale = Mth.clamp(scale, SCALE_LOWER_LIMIT, SCALE_UPPER_LIMIT);
	}

	public void find(double x1, double z1, double x2, double z2) {
		centerX = (x1 + x2) / 2;
		centerY = (z1 + z2) / 2;
		scale = Math.max(2, scale);
	}

	public void find(BlockPos pos) {
		centerX = pos.getX();
		centerY = pos.getZ();
		scale = Math.max(8, scale);
	}

	public void startEditingArea(AreaBase editingArea) {
		mapState = MapState.EDITING_AREA;
		drawArea1 = editingArea.corner1;
		drawArea2 = editingArea.corner2;
	}

	public void setDrawArea(Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2) {
		drawArea1 = corner1;
		drawArea2 = corner2;
	}

	public void setAreaNotice(String areaNotice) {
		this.areaNotice = areaNotice;
	}

	public void startEditingRoute() {
		mapState = MapState.EDITING_ROUTE;
	}

	public void setRoutesToDraw(Supplier<Collection<Route>> routesToDraw) {
		this.routesToDraw = routesToDraw;
	}

	public void stopEditing() {
		mapState = MapState.DEFAULT;
	}

	/**
	 * Lets routes be chosen on the map by the coloured lines they are drawn with.
	 */
	public void setRoutePicking(BooleanSupplier canPickRoute, Consumer<Route> onPickRoute) {
		this.canPickRoute = canPickRoute;
		this.onPickRoute = onPickRoute;
	}

	public void setSelectedLine(String selectedLineKey) {
		this.selectedLineKey = selectedLineKey;
	}

	/**
	 * Lets stations be chosen on the map by clicking any of their areas.
	 */
	public void setStationPicking(BooleanSupplier canPickStation, Consumer<Station> onPickStation) {
		this.canPickStation = canPickStation;
		this.onPickStation = onPickStation;
	}

	public void setSelectedStation(long selectedStationId) {
		this.selectedStationId = selectedStationId;
	}

	public void setDepotPicking(BooleanSupplier canPickDepot, Consumer<Depot> onPickDepot) {
		this.canPickDepot = canPickDepot;
		this.onPickDepot = onPickDepot;
	}

	public void setSelectedDepot(long selectedDepotId) {
		this.selectedDepotId = selectedDepotId;
	}

	public void setShowStations(boolean showStations) {
		this.showStations = showStations;
	}

	private void mouseOnSavedRail(Tuple<Double, Double> mouseWorldPos, MouseOnSavedRailCallback mouseOnSavedRailCallback, boolean isPlatform) {
		try {
			(isPlatform ? ClientData.DATA_CACHE.getPosToPlatforms(transportMode) : ClientData.DATA_CACHE.getPosToSidings(transportMode)).forEach((savedRailPos, savedRails) -> {
				final int savedRailCount = savedRails.size();
				for (int i = 0; i < savedRailCount; i++) {
					final float left = savedRailPos.getX();
					final float right = savedRailPos.getX() + 1;
					final float top = savedRailPos.getZ() + (float) i / savedRailCount;
					final float bottom = savedRailPos.getZ() + (i + 1F) / savedRailCount;
					if (RailwayData.isBetween(mouseWorldPos.getA(), left, right) && RailwayData.isBetween(mouseWorldPos.getB(), top, bottom)) {
						mouseOnSavedRailCallback.mouseOnSavedRailCallback(savedRails.get(i), left, top, right, bottom);
					}
				}
			});
		} catch (ConcurrentModificationException ignored) {
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Tuple<Integer, Integer> coordsToWorldPos(int mouseX, int mouseY) {
		final Tuple<Double, Double> worldPos = coordsToWorldPos((double) mouseX, mouseY);
		return new Tuple<>((int) Math.floor(worldPos.getA()), (int) Math.floor(worldPos.getB()));
	}

	private void drawRails(BufferBuilder buffer) {
		ClientData.MAP_RAILS.forEach(mapRail -> {
			if (mapRail.transportMode == transportMode) {
				drawRailShape(buffer, mapRail.node1, mapRail.node2, RAIL_LINE_PIXELS, ARGB_RAIL_LINE);
			}
		});
	}

	/**
	 * One layer of isolation over the rails: pathfinding boundaries, which no search crosses, and the rails connecting
	 * lines take from other routes, or have released (L1).
	 */
	private void drawIsolation(BufferBuilder buffer) {
		mtr.client.MapIsolation.getStatuses(transportMode).forEach((key, status) -> drawIsolatedRail(buffer, key.getMostSignificantBits(), key.getLeastSignificantBits(), status == RoutePaths.RAIL_BOUNDARY ? ARGB_BOUNDARY : status == RoutePaths.RAIL_RELEASED ? ARGB_RELEASED : ARGB_CONNECTING_LINE));
		ClientData.PATH_REGIONS.boundaries.nodes.forEach(node -> drawMarker(buffer, BlockPos.of(node), KEY_NODE_MARKER_PIXELS, ARGB_BOUNDARY));
	}

	private void drawIsolatedRail(BufferBuilder buffer, long node1, long node2, int color) {
		drawRailShape(buffer, node1, node2, ISOLATION_LINE_PIXELS, color);
	}

	/**
	 * A rail from node to node, through the few points along its curve the server sent ({@link ClientData#MAP_RAIL_SHAPES}),
	 * so a long curve isn't drawn straight across.
	 */
	private void drawRailShape(BufferBuilder buffer, long node1, long node2, double pixels, int color) {
		final BlockPos pos1 = BlockPos.of(Math.min(node1, node2));
		final BlockPos pos2 = BlockPos.of(Math.max(node1, node2));
		final float[] shape = ClientData.MAP_RAIL_SHAPES.get(transitsim.network.RailKeys.of(node1, node2));
		double lastX = pos1.getX() + 0.5;
		double lastZ = pos1.getZ() + 0.5;
		if (shape != null) {
			for (int i = 0; i + 1 < shape.length; i += 2) {
				drawLineFromWorldCoords(buffer, lastX, lastZ, shape[i], shape[i + 1], pixels, color);
				lastX = shape[i];
				lastZ = shape[i + 1];
			}
		}
		drawLineFromWorldCoords(buffer, lastX, lastZ, pos2.getX() + 0.5, pos2.getZ() + 0.5, pixels, color);
	}

	private void drawRouteHighlights(BufferBuilder buffer, Route route) {
		final Set<BlockPos> waypoints = new HashSet<>();
		route.platformIds.forEach(routePlatform -> {
			waypoints.addAll(routePlatform.waypoints);
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
			if (platform != null) {
				drawMarker(buffer, platform.getMidPos(), ROUTE_STOP_MARKER_PIXELS, ARGB_BLACK | route.color);
			}
		});
		ClientData.KEY_NODES.forEach(pos -> drawMarker(buffer, pos, KEY_NODE_MARKER_PIXELS, waypoints.contains(pos) ? ARGB_KEY_NODE_PICKED : ARGB_KEY_NODE));
	}

	private void drawMarker(BufferBuilder buffer, BlockPos pos, double radiusPixels, int color) {
		final double radius = radiusPixels / scale;
		drawRectangleFromWorldCoords(buffer, pos.getX() + 0.5 - radius, pos.getZ() + 0.5 - radius, pos.getX() + 0.5 + radius, pos.getZ() + 0.5 + radius, color);
	}

	private void drawDepotPaths(BufferBuilder buffer) {
		final Collection<Route> routes = routesToDraw.get();
		ClientData.MAP_SIDINGS.forEach((sidingId, nodes) -> {
			final Depot depot = ClientData.DATA_CACHE.sidingIdToDepot.get(sidingId);
			if (depot != null && depot.isTransportMode(transportMode) && nodes.length > 1 && (mapState != MapState.EDITING_ROUTE || routes.stream().anyMatch(route -> depot.routeIds.contains(route.id)))) {
				final Route firstRoute = depot.routeIds.isEmpty() ? null : ClientData.DATA_CACHE.routeIdMap.get(depot.routeIds.get(0));
				drawNodes(buffer, nodes, ARGB_ROUTE_LINE_ALPHA | (firstRoute == null ? depot.color : firstRoute.color) & 0xFFFFFF);
			}
		});
	}

	private void drawNodes(BufferBuilder buffer, long[] nodes, int color) {
		BlockPos lastNode = BlockPos.of(nodes[0]);
		for (int i = 1; i < nodes.length; i++) {
			final BlockPos node = BlockPos.of(nodes[i]);
			drawRouteSegment(buffer, lastNode, node, ROUTE_LINE_PIXELS, color);
			lastNode = node;
		}
	}

	private void drawRoute(BufferBuilder buffer, Route route) {
		drawRouteNodes(buffer, getRouteNodes(route), ROUTE_LINE_PIXELS, ARGB_ROUTE_LINE_ALPHA | route.color & 0xFFFFFF);
	}

	private void drawRouteNodes(BufferBuilder buffer, List<BlockPos> nodes, double thickness, int color) {
		for (int i = 1; i < nodes.size(); i++) {
			drawRouteSegment(buffer, nodes.get(i - 1), nodes.get(i), thickness, color);
		}
	}

	private void drawRouteSegment(BufferBuilder buffer, BlockPos lastPos, BlockPos pos, double thickness, int color) {
		// Along the rail's curve when the two nodes are the ends of a curved rail
		drawRailShape(buffer, lastPos.asLong(), pos.asLong(), thickness, color);
	}

	private static List<BlockPos> getRouteNodes(Route route) {
		final List<BlockPos> routeNodes = new ArrayList<>();
		final long[] nodes = ClientData.MAP_ROUTES.get(route.id);
		if (nodes != null && nodes.length > 1) {
			for (final long node : nodes) {
				routeNodes.add(BlockPos.of(node));
			}
			return routeNodes;
		}

		// A route without a traced path falls back to straight lines between its stops
		for (final Route.RoutePlatform routePlatform : route.platformIds) {
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
			if (platform != null) {
				routeNodes.add(platform.getMidPos());
				routeNodes.addAll(routePlatform.waypoints);
			}
		}
		return routeNodes;
	}

	private void updateHoveredRoutes(int mouseX, int mouseY, Tuple<Double, Double> mouseWorldPos) {
		final boolean pickRoutes = canPickRoute.getAsBoolean();
		final boolean pickStations = canPickStation.getAsBoolean();
		final boolean pickDepots = canPickDepot.getAsBoolean();
		if (!pickRoutes && !pickStations && !pickDepots || !isMouseOver(mouseX, mouseY)) {
			hoveredRoutes = Collections.emptyList();
			hoveredLineKeys = Collections.emptySet();
			hoveredStations = Collections.emptyList();
			hoveredDepots = Collections.emptyList();
			hoverMouseX = -1;
		} else if (mouseX != hoverMouseX || mouseY != hoverMouseY || scale != hoverScale || centerX != hoverCenterX || centerY != hoverCenterY) {
			hoverMouseX = mouseX;
			hoverMouseY = mouseY;
			hoverScale = scale;
			hoverCenterX = centerX;
			hoverCenterY = centerY;
			hoveredRoutes = pickRoutes ? getRoutesAt(mouseWorldPos) : Collections.emptyList();
			hoveredLineKeys = hoveredRoutes.stream().map(Route::getLineKey).collect(Collectors.toSet());
			hoveredStations = pickStations ? getStationsAt(mouseWorldPos) : Collections.emptyList();
			hoveredDepots = pickDepots ? getDepotsAt(mouseWorldPos) : Collections.emptyList();
		}
	}

	/**
	 * @return the stations with an area at this position, in an order that doesn't change between clicks
	 */
	private static List<Station> getStationsAt(Tuple<Double, Double> worldPos) {
		final List<Station> stations = new ArrayList<>();
		for (final Station station : ClientData.STATIONS) {
			if (AreaBase.nonNullCorners(station)) {
				for (final int[] rectangle : station.getRectangles()) {
					if (RailwayData.isBetween(worldPos.getA(), rectangle[0], rectangle[2]) && RailwayData.isBetween(worldPos.getB(), rectangle[1], rectangle[3])) {
						stations.add(station);
						break;
					}
				}
			}
		}
		stations.sort(Comparator.comparingLong(station -> station.id));
		return stations;
	}

	/**
	 * @return the depots of this transport mode with an area at this position, in an order that doesn't change between clicks
	 */
	private List<Depot> getDepotsAt(Tuple<Double, Double> worldPos) {
		final List<Depot> depots = new ArrayList<>();
		for (final Depot depot : ClientData.DEPOTS) {
			if (depot.isTransportMode(transportMode) && AreaBase.nonNullCorners(depot) && RailwayData.isBetween(worldPos.getA(), depot.corner1.getA(), depot.corner2.getA()) && RailwayData.isBetween(worldPos.getB(), depot.corner1.getB(), depot.corner2.getB())) {
				depots.add(depot);
			}
		}
		depots.sort(Comparator.comparingLong(depot -> depot.id));
		return depots;
	}

	/**
	 * Picks the area after the selected one, so clicking again where areas overlap goes through all of them.
	 */
	private static <T extends AreaBase> void pickArea(List<T> areas, long selectedId, Consumer<T> onPick) {
		if (!areas.isEmpty()) {
			int selectedIndex = -1;
			for (int i = 0; i < areas.size(); i++) {
				if (areas.get(i).id == selectedId) {
					selectedIndex = i;
				}
			}
			onPick.accept(areas.get((selectedIndex + 1) % areas.size()));
		}
	}

	/**
	 * @return one route for each line drawn near this position, with the closest first
	 */
	private List<Route> getRoutesAt(Tuple<Double, Double> worldPos) {
		final double maxDistance = ROUTE_PICK_PIXELS / scale;
		final Map<Route, Double> distances = new HashMap<>();
		routesToDraw.get().forEach(route -> {
			final List<BlockPos> nodes = getRouteNodes(route);
			double routeDistance = Double.POSITIVE_INFINITY;
			for (int i = 1; i < nodes.size(); i++) {
				routeDistance = Math.min(routeDistance, getDistanceToSegment(worldPos.getA(), worldPos.getB(), nodes.get(i - 1), nodes.get(i)));
			}
			if (routeDistance <= maxDistance) {
				distances.put(route, routeDistance);
			}
		});
		final Set<String> lineKeys = new HashSet<>();
		return distances.keySet().stream().sorted(Comparator.comparingDouble((Route route) -> distances.get(route)).thenComparingLong(route -> route.id)).filter(route -> lineKeys.add(route.getLineKey())).collect(Collectors.toList());
	}

	private static double getDistanceToSegment(double pointX, double pointZ, BlockPos pos1, BlockPos pos2) {
		final double x1 = pos1.getX() + 0.5;
		final double z1 = pos1.getZ() + 0.5;
		final double differenceX = pos2.getX() - pos1.getX();
		final double differenceZ = pos2.getZ() - pos1.getZ();
		final double lengthSquared = differenceX * differenceX + differenceZ * differenceZ;
		final double progress = lengthSquared == 0 ? 0 : Mth.clamp(((pointX - x1) * differenceX + (pointZ - z1) * differenceZ) / lengthSquared, 0, 1);
		return Math.hypot(x1 + differenceX * progress - pointX, z1 + differenceZ * progress - pointZ);
	}

	/**
	 * Clicking again where lines overlap moves on to the next one, so every line sharing a rail can be reached.
	 */
	private void pickRoute(Tuple<Double, Double> worldPos) {
		final List<Route> routes = getRoutesAt(worldPos);
		if (!routes.isEmpty()) {
			int selectedIndex = -1;
			for (int i = 0; i < routes.size(); i++) {
				if (routes.get(i).getLineKey().equals(selectedLineKey)) {
					selectedIndex = i;
				}
			}
			onPickRoute.accept(routes.get((selectedIndex + 1) % routes.size()));
		}
	}

	private void drawLineFromWorldCoords(BufferBuilder buffer, double posX1, double posZ1, double posX2, double posZ2, double thickness, int color) {
		final double x1 = (posX1 - centerX) * scale + width / 2D;
		final double y1 = (posZ1 - centerY) * scale + height / 2D;
		final double differenceX = (posX2 - posX1) * scale;
		final double differenceY = (posZ2 - posZ1) * scale;

		// Clip to the map, so long rails don't draw over the panels beside it
		double start = 0;
		double end = 1;
		final double[][] edges = {{-differenceX, x1}, {differenceX, width - x1}, {-differenceY, y1}, {differenceY, height - y1}};
		for (final double[] edge : edges) {
			if (edge[0] == 0) {
				if (edge[1] < 0) {
					return;
				}
			} else if (edge[0] < 0) {
				start = Math.max(start, edge[1] / edge[0]);
			} else {
				end = Math.min(end, edge[1] / edge[0]);
			}
		}

		final double length = Math.hypot(differenceX, differenceY);
		if (start < end && length > 0) {
			final double offsetX = -differenceY / length * thickness / 2;
			final double offsetY = differenceX / length * thickness / 2;
			final double startX = x + x1 + differenceX * start;
			final double startY = y + y1 + differenceY * start;
			final double endX = x + x1 + differenceX * end;
			final double endY = y + y1 + differenceY * end;
			final int alpha = color >>> 24;
			final int red = color >> 16 & 0xFF;
			final int green = color >> 8 & 0xFF;
			final int blue = color & 0xFF;
			// The same winding as IDrawing.drawRectangle, otherwise the quad is culled
			buffer.vertex(startX - offsetX, startY - offsetY, 0).color(red, green, blue, alpha).endVertex();
			buffer.vertex(startX + offsetX, startY + offsetY, 0).color(red, green, blue, alpha).endVertex();
			buffer.vertex(endX + offsetX, endY + offsetY, 0).color(red, green, blue, alpha).endVertex();
			buffer.vertex(endX - offsetX, endY - offsetY, 0).color(red, green, blue, alpha).endVertex();
		}
	}

	private void clickKeyNode(Tuple<Double, Double> mouseWorldPos) {
		final double maxDistance = KEY_NODE_CLICK_PIXELS / scale;
		ClientData.KEY_NODES.stream().filter(pos -> Math.hypot(pos.getX() + 0.5 - mouseWorldPos.getA(), pos.getZ() + 0.5 - mouseWorldPos.getB()) <= maxDistance).min(Comparator.comparingDouble((BlockPos pos) -> Math.hypot(pos.getX() + 0.5 - mouseWorldPos.getA(), pos.getZ() + 0.5 - mouseWorldPos.getB())).thenComparingLong(BlockPos::asLong)).ifPresent(onClickAddKeyNodeToRoute);
	}

	private Tuple<Double, Double> coordsToWorldPos(double mouseX, double mouseY) {
		final double left = (mouseX - width / 2D) / scale + centerX;
		final double right = (mouseY - height / 2D) / scale + centerY;
		return new Tuple<>(left, right);
	}

	private void drawFromWorldCoords(double worldX, double worldZ, BiConsumer<Double, Double> callback) {
		final double coordsX = (worldX - centerX) * scale + width / 2D;
		final double coordsY = (worldZ - centerY) * scale + height / 2D;
		if (RailwayData.isBetween(coordsX, 0, width) && RailwayData.isBetween(coordsY, 0, height)) {
			callback.accept(coordsX, coordsY);
		}
	}

	private void drawRectangleFromWorldCoords(BufferBuilder buffer, Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2, int color) {
		drawRectangleFromWorldCoords(buffer, corner1.getA(), corner1.getB(), corner2.getA(), corner2.getB(), color);
	}

	private void drawRectangleFromWorldCoords(BufferBuilder buffer, double posX1, double posZ1, double posX2, double posZ2, int color) {
		final double x1 = (posX1 - centerX) * scale + width / 2D;
		final double z1 = (posZ1 - centerY) * scale + height / 2D;
		final double x2 = (posX2 - centerX) * scale + width / 2D;
		final double z2 = (posZ2 - centerY) * scale + height / 2D;
		drawRectangle(buffer, x1, z1, x2, z2, color);
	}

	private void drawRectangle(BufferBuilder buffer, double xA, double yA, double xB, double yB, int color) {
		final double x1 = Math.min(xA, xB);
		final double y1 = Math.min(yA, yB);
		final double x2 = Math.max(xA, xB);
		final double y2 = Math.max(yA, yB);
		if (x1 < width && y1 < height && x2 >= 0 && y2 >= 0) {
			IDrawing.drawRectangle(buffer, x + Math.max(0, x1), y + y1, x + x2, y + y2, color);
		}
	}

	private boolean canDrawAreaText(AreaBase areaBase) {
		return areaBase.getCenter() != null && scale >= 80F / Math.max(Math.abs(areaBase.corner1.getA() - areaBase.corner2.getA()), Math.abs(areaBase.corner1.getB() - areaBase.corner2.getB()));
	}

	private void drawSavedRail(PoseStack matrices, BlockPos savedRailPos, List<? extends SavedRailBase> savedRails) {
		final int savedRailCount = savedRails.size();
		for (int i = 0; i < savedRailCount; i++) {
			final int index = i;
			drawFromWorldCoords(savedRailPos.getX() + 0.5, savedRailPos.getZ() + (i + 0.5) / savedRailCount, (x1, y1) -> GuiCompat.drawCenteredString(matrices, textRenderer, savedRails.get(index).name, x + x1.intValue(), y + y1.intValue() - TEXT_HEIGHT / 2, ARGB_WHITE));
		}
	}

	private static int divideColorRGB(int color, int amount) {
		final int r = ((color >> 16) & 0xFF) / amount;
		final int g = ((color >> 8) & 0xFF) / amount;
		final int b = (color & 0xFF) / amount;
		return (r << 16) + (g << 8) + b;
	}

	@FunctionalInterface
	public interface OnDrawCorners {
		void onDrawCorners(Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2);
	}

	@FunctionalInterface
	private interface MouseOnSavedRailCallback {
		void mouseOnSavedRailCallback(SavedRailBase savedRail, double x1, double z1, double x2, double z2);
	}

	private enum MapState {DEFAULT, EDITING_AREA, EDITING_ROUTE}
}
