package mtm.testkit.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import mtm.testkit.DataJson;
import mtm.testkit.Http;
import mtm.testkit.LogCapture;
import mtm.testkit.TestKit;
import mtr.client.ClientData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The client side of the driver: reads the open screen, clicks and types into it, uses items on blocks, sends chat and
 * commands, takes screenshots and reads the client's copy of the railway data. Every action runs on the render thread.
 */
public final class ClientDriver {

	private static final int KEEP_CHAT = 300;
	private static final Deque<JsonObject> CHAT = new ArrayDeque<>();
	private static long chatSequence;
	private static final Map<String, Integer> KEYS = new HashMap<>();
	private static Http http;

	static {
		KEYS.put("ENTER", GLFW.GLFW_KEY_ENTER);
		KEYS.put("ESCAPE", GLFW.GLFW_KEY_ESCAPE);
		KEYS.put("TAB", GLFW.GLFW_KEY_TAB);
		KEYS.put("BACKSPACE", GLFW.GLFW_KEY_BACKSPACE);
		KEYS.put("DELETE", GLFW.GLFW_KEY_DELETE);
		KEYS.put("LEFT", GLFW.GLFW_KEY_LEFT);
		KEYS.put("RIGHT", GLFW.GLFW_KEY_RIGHT);
		KEYS.put("UP", GLFW.GLFW_KEY_UP);
		KEYS.put("DOWN", GLFW.GLFW_KEY_DOWN);
		KEYS.put("HOME", GLFW.GLFW_KEY_HOME);
		KEYS.put("END", GLFW.GLFW_KEY_END);
		KEYS.put("A", GLFW.GLFW_KEY_A);
		KEYS.put("C", GLFW.GLFW_KEY_C);
		KEYS.put("V", GLFW.GLFW_KEY_V);
	}

	private ClientDriver() {
	}

	public static synchronized void start(Minecraft minecraft) {
		final Map<String, Http.Handler> routes = new LinkedHashMap<>();
		routes.put("/ping", (body, query) -> Http.onThread(minecraft, () -> ping(minecraft)));
		routes.put("/state", (body, query) -> Http.onThread(minecraft, () -> state(minecraft)));
		routes.put("/click", (body, query) -> Http.onThread(minecraft, () -> click(minecraft, body)));
		routes.put("/move", (body, query) -> Http.onThread(minecraft, () -> {
			screen(minecraft).mouseMoved(body.get("x").getAsDouble(), body.get("y").getAsDouble());
			return null;
		}));
		routes.put("/scroll", (body, query) -> Http.onThread(minecraft, () -> {
			screen(minecraft).mouseScrolled(body.get("x").getAsDouble(), body.get("y").getAsDouble(), body.get("amount").getAsDouble());
			return null;
		}));
		routes.put("/type", (body, query) -> Http.onThread(minecraft, () -> type(minecraft, body)));
		routes.put("/key", (body, query) -> Http.onThread(minecraft, () -> key(minecraft, body)));
		routes.put("/close", (body, query) -> Http.onThread(minecraft, () -> {
			if (minecraft.screen != null) {
				minecraft.screen.onClose();
			}
			return null;
		}));
		routes.put("/slot", (body, query) -> Http.onThread(minecraft, () -> {
			player(minecraft).getInventory().selected = body.get("slot").getAsInt();
			return null;
		}));
		routes.put("/use", (body, query) -> Http.onThread(minecraft, () -> use(minecraft, body)));
		routes.put("/use-item", (body, query) -> Http.onThread(minecraft, () -> {
			final JsonObject result = new JsonObject();
			result.addProperty("result", minecraft.gameMode.useItem(player(minecraft), InteractionHand.MAIN_HAND).toString());
			return result;
		}));
		routes.put("/chat", (body, query) -> Http.onThread(minecraft, () -> chat(minecraft, body.get("text").getAsString())));
		routes.put("/suggest", (body, query) -> suggest(minecraft, body.get("text").getAsString()));
		routes.put("/chat-log", (body, query) -> chatLog(query.containsKey("since") ? Long.parseLong(query.get("since")) : 0));
		routes.put("/screenshot", (body, query) -> Http.onThread(minecraft, () -> screenshot(minecraft, query.get("id"))));
		routes.put("/client-data", (body, query) -> Http.onThread(minecraft, () -> DataJson.snapshot(ClientData.STATIONS, ClientData.PLATFORMS, ClientData.SIDINGS, ClientData.ROUTES, ClientData.DEPOTS)));
		routes.put("/editor-url", (body, query) -> {
			final JsonObject result = new JsonObject();
			result.addProperty("url", mtr.client.editor.EditorServer.open());
			return result;
		});
		// Frame times over a while (FrameStats); optionally first sets MTR's "optimise train rendering", the frame cap and the
		// player's position and view, so a measurement starts from a known state
		routes.put("/frames", (body, query) -> {
			Http.onThread(minecraft, () -> {
				if (body.has("optimizeTrainRendering")) {
					mtr.client.Config.setOptimizeTrainRendering(body.get("optimizeTrainRendering").getAsBoolean());
				}
				if (body.has("maxFps")) {
					minecraft.options.framerateLimit().set(body.get("maxFps").getAsInt());
					minecraft.options.enableVsync().set(false);
					minecraft.getWindow().updateVsync(false);
				}
				if (body.has("x")) {
					player(minecraft).moveTo(body.get("x").getAsDouble(), body.get("y").getAsDouble(), body.get("z").getAsDouble(), body.get("yaw").getAsFloat(), body.get("pitch").getAsFloat());
					player(minecraft).getAbilities().flying = true;
				}
				return null;
			});
			if (body.has("settleMs")) {
				Thread.sleep(body.get("settleMs").getAsLong());
			}
			return FrameStats.measure(body.has("ms") ? body.get("ms").getAsLong() : 10000);
		});
		// Resource packs: lists the ones found with their compatibility and which are selected; with "select" (pack IDs such
		// as "file/shmetro") selects those, keeping the built-in ones, and reloads, waiting until the reload is done
		routes.put("/packs", (body, query) -> {
			final CompletableFuture<Void> reload = Http.onThread(minecraft, () -> {
				final net.minecraft.server.packs.repository.PackRepository repository = minecraft.getResourcePackRepository();
				repository.reload();
				if (!body.has("select")) {
					return CompletableFuture.completedFuture(null);
				}
				final List<String> selected = new java.util.ArrayList<>();
				repository.getSelectedPacks().forEach(pack -> {
					if (!pack.getId().startsWith("file/")) {
						selected.add(pack.getId());
					}
				});
				body.getAsJsonArray("select").forEach(element -> selected.add(element.getAsString()));
				repository.setSelected(selected);
				minecraft.options.updateResourcePacks(repository);
				return minecraft.reloadResourcePacks();
			});
			reload.get(300, TimeUnit.SECONDS);
			return Http.onThread(minecraft, () -> {
				final JsonObject result = new JsonObject();
				final JsonArray available = new JsonArray();
				minecraft.getResourcePackRepository().getAvailablePacks().forEach(pack -> available.add(pack.getId() + " " + pack.getCompatibility()));
				result.add("available", available);
				final JsonArray selected = new JsonArray();
				minecraft.getResourcePackRepository().getSelectedPacks().forEach(pack -> selected.add(pack.getId()));
				result.add("selected", selected);
				return result;
			});
		});
		routes.put("/log", (body, query) -> LogCapture.since(query.containsKey("since") ? Long.parseLong(query.get("since")) : 0));
		routes.put("/join", (body, query) -> Http.onThread(minecraft, () -> {
			// As the multiplayer screen's direct connect does
			final String address = body.get("address").getAsString();
			final Screen parent = minecraft.screen == null ? new net.minecraft.client.gui.screens.TitleScreen() : minecraft.screen;
			net.minecraft.client.gui.screens.ConnectScreen.startConnecting(parent, minecraft, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address), new net.minecraft.client.multiplayer.ServerData("MTM UI tests", address, false), false);
			return null;
		}));
		routes.put("/quit", (body, query) -> {
			minecraft.execute(minecraft::stop);
			return null;
		});
		try {
			http = Http.start("client", minecraft.gameDirectory.toPath(), routes);
		} catch (Exception e) {
			TestKit.LOGGER.error("[MTM_TestKit] could not start the client driver", e);
		}
	}

	public static void onChat(Component message, boolean overlay) {
		final JsonObject entry = new JsonObject();
		entry.addProperty("text", message.getString());
		entry.addProperty("overlay", overlay);
		synchronized (CHAT) {
			entry.addProperty("seq", ++chatSequence);
			CHAT.addLast(entry);
			while (CHAT.size() > KEEP_CHAT) {
				CHAT.removeFirst();
			}
		}
	}

	private static JsonObject chatLog(long since) {
		final JsonObject result = new JsonObject();
		final JsonArray messages = new JsonArray();
		synchronized (CHAT) {
			CHAT.stream().filter(entry -> entry.get("seq").getAsLong() > since).forEach(messages::add);
			result.addProperty("last", chatSequence);
		}
		result.add("messages", messages);
		return result;
	}

	private static Screen screen(Minecraft minecraft) {
		if (minecraft.screen == null) {
			throw new IllegalStateException("no screen is open");
		}
		return minecraft.screen;
	}

	private static net.minecraft.client.player.LocalPlayer player(Minecraft minecraft) {
		if (minecraft.player == null) {
			throw new IllegalStateException("not in a world");
		}
		return minecraft.player;
	}

	private static JsonObject ping(Minecraft minecraft) {
		final JsonObject result = new JsonObject();
		result.addProperty("side", "client");
		result.addProperty("inWorld", minecraft.level != null && minecraft.player != null);
		result.addProperty("player", minecraft.player == null ? null : minecraft.player.getGameProfile().getName());
		result.addProperty("server", minecraft.getCurrentServer() == null ? null : minecraft.getCurrentServer().ip);
		result.addProperty("screen", minecraft.screen == null ? null : minecraft.screen.getClass().getName());
		result.addProperty("titleScreen", minecraft.screen instanceof net.minecraft.client.gui.screens.TitleScreen);
		result.addProperty("fps", minecraft.getFps());
		return result;
	}

	private static JsonObject state(Minecraft minecraft) {
		final JsonObject result = new JsonObject();
		result.addProperty("guiScale", minecraft.getWindow().getGuiScale());
		result.addProperty("guiWidth", minecraft.getWindow().getGuiScaledWidth());
		result.addProperty("guiHeight", minecraft.getWindow().getGuiScaledHeight());
		// The text drawn in the last frame, in the screen's (scaled) coordinates
		result.add("texts", ScreenText.lastFrame());
		final Screen screen = minecraft.screen;
		if (screen == null) {
			result.add("screen", null);
			return result;
		}
		result.addProperty("screen", screen.getClass().getName());
		result.addProperty("screenName", screen.getClass().getSimpleName());
		result.addProperty("title", screen.getTitle().getString());
		final JsonArray widgets = new JsonArray();
		WidgetTree.widgets(screen).forEach(found -> widgets.add(WidgetTree.describe(found)));
		result.add("widgets", widgets);
		result.add("lists", WidgetTree.lists(screen));
		return result;
	}

	private static WidgetTree.Found find(Screen screen, JsonObject body) {
		final List<WidgetTree.Found> widgets = WidgetTree.widgets(screen);
		if (body.has("id")) {
			final String id = body.get("id").getAsString();
			return widgets.stream().filter(found -> found.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("no widget " + id + " on " + screen.getClass().getSimpleName()));
		} else if (body.has("text")) {
			final String text = body.get("text").getAsString();
			return widgets.stream().filter(found -> found.widget.visible && found.widget.getMessage().getString().equals(text)).findFirst().orElseThrow(() -> new IllegalArgumentException("no widget with text " + text + " on " + screen.getClass().getSimpleName()));
		} else {
			return null;
		}
	}

	/**
	 * Clicks a widget (by "id" or "text") in its middle, or a point ("x", "y"), with mouse button "button" (0 by default).
	 * A hidden or inactive widget is not clicked unless "force" is set, as a player could not click it either.
	 */
	private static JsonObject click(Minecraft minecraft, JsonObject body) {
		final Screen screen = screen(minecraft);
		final WidgetTree.Found found = find(screen, body);
		final double x;
		final double y;
		if (found != null) {
			final AbstractWidget widget = found.widget;
			if ((!widget.visible || !widget.active) && !(body.has("force") && body.get("force").getAsBoolean())) {
				throw new IllegalStateException(String.format("widget %s is %s", found.id, widget.visible ? "inactive" : "hidden"));
			}
			x = widget.getX() + widget.getWidth() / 2.0;
			y = widget.getY() + widget.getHeight() / 2.0;
		} else {
			x = body.get("x").getAsDouble();
			y = body.get("y").getAsDouble();
		}
		final int button = body.has("button") ? body.get("button").getAsInt() : 0;
		screen.mouseMoved(x, y);
		final boolean handled = screen.mouseClicked(x, y, button);
		// The click may have closed or replaced the screen
		if (minecraft.screen == screen) {
			screen.mouseReleased(x, y, button);
		}
		final JsonObject result = new JsonObject();
		result.addProperty("id", found == null ? null : found.id);
		result.addProperty("x", x);
		result.addProperty("y", y);
		result.addProperty("handled", handled);
		return result;
	}

	/**
	 * Types "text" a character at a time, as the keyboard would, into widget "id" (focused first) or the focused widget.
	 * With "clear", the text box is emptied first.
	 */
	private static JsonElement type(Minecraft minecraft, JsonObject body) {
		final Screen screen = screen(minecraft);
		final WidgetTree.Found found = find(screen, body);
		if (found != null) {
			screen.setFocused(found.widget);
			if (found.widget instanceof EditBox && body.has("clear") && body.get("clear").getAsBoolean()) {
				((EditBox) found.widget).setValue("");
			}
		}
		final String text = body.get("text").getAsString();
		for (int i = 0; i < text.length(); i++) {
			screen.charTyped(text.charAt(i), 0);
		}
		return null;
	}

	/**
	 * Presses and releases "key" (a name like ENTER or a GLFW key code) with modifiers "ctrl", "shift", "alt".
	 */
	private static JsonElement key(Minecraft minecraft, JsonObject body) {
		final Screen screen = screen(minecraft);
		final String name = body.get("key").getAsString().toUpperCase();
		final int code = KEYS.containsKey(name) ? KEYS.get(name) : Integer.parseInt(name);
		final int modifiers = (flag(body, "ctrl") ? GLFW.GLFW_MOD_CONTROL : 0) | (flag(body, "shift") ? GLFW.GLFW_MOD_SHIFT : 0) | (flag(body, "alt") ? GLFW.GLFW_MOD_ALT : 0);
		final int scanCode = GLFW.glfwGetKeyScancode(code);
		screen.keyPressed(code, scanCode, modifiers);
		if (minecraft.screen == screen) {
			screen.keyReleased(code, scanCode, modifiers);
		}
		return null;
	}

	private static boolean flag(JsonObject body, String name) {
		return body.has(name) && body.get(name).getAsBoolean();
	}

	/**
	 * Uses the held item (or the hand) on block "x" "y" "z" from "face" (up by default), as a right click would, after
	 * selecting hotbar "slot" if given. The server checks the player is near enough.
	 */
	private static JsonObject use(Minecraft minecraft, JsonObject body) {
		final net.minecraft.client.player.LocalPlayer player = player(minecraft);
		if (body.has("slot")) {
			player.getInventory().selected = body.get("slot").getAsInt();
		}
		final BlockPos pos = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
		final Direction face = body.has("face") ? Direction.byName(body.get("face").getAsString()) : Direction.UP;
		final BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
		final JsonObject result = new JsonObject();
		result.addProperty("result", minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult).toString());
		return result;
	}

	private static JsonElement chat(Minecraft minecraft, String text) {
		final ClientPacketListener connection = minecraft.getConnection();
		if (connection == null) {
			throw new IllegalStateException("not connected");
		}
		if (text.startsWith("/")) {
			connection.sendCommand(text.substring(1));
		} else {
			connection.sendChat(text);
		}
		return null;
	}

	/**
	 * The completions the chat box would offer for "text" (a command, with or without the slash), asking the server
	 * where the command does.
	 */
	private static JsonObject suggest(Minecraft minecraft, String text) throws Exception {
		final String command = text.startsWith("/") ? text.substring(1) : text;
		final CompletableFuture<Suggestions> future = Http.onThread(minecraft, () -> {
			final ClientPacketListener connection = minecraft.getConnection();
			if (connection == null) {
				throw new IllegalStateException("not connected");
			}
			final ParseResults<SharedSuggestionProvider> parse = connection.getCommands().parse(new StringReader(command), connection.getSuggestionsProvider());
			return connection.getCommands().getCompletionSuggestions(parse);
		});
		final Suggestions suggestions = future.get(10, TimeUnit.SECONDS);
		final JsonObject result = new JsonObject();
		final JsonArray list = new JsonArray();
		for (final Suggestion suggestion : suggestions.getList()) {
			list.add(suggestion.getText());
		}
		result.add("suggestions", list);
		return result;
	}

	/**
	 * The last frame as a PNG; with "id", only that widget's rectangle.
	 */
	private static byte[] screenshot(Minecraft minecraft, String id) {
		try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
			if (id == null || id.isEmpty()) {
				return image.asByteArray();
			}
			final JsonObject body = new JsonObject();
			body.addProperty("id", id);
			final AbstractWidget widget = find(screen(minecraft), body).widget;
			final double scale = minecraft.getWindow().getGuiScale();
			final int x = (int) Math.max(0, Math.floor(widget.getX() * scale));
			final int y = (int) Math.max(0, Math.floor(widget.getY() * scale));
			final int width = (int) Math.min(image.getWidth() - x, Math.ceil(widget.getWidth() * scale));
			final int height = (int) Math.min(image.getHeight() - y, Math.ceil(widget.getHeight() * scale));
			try (NativeImage cropped = new NativeImage(Math.max(1, width), Math.max(1, height), false)) {
				for (int i = 0; i < width; i++) {
					for (int j = 0; j < height; j++) {
						cropped.setPixelRGBA(i, j, image.getPixelRGBA(x + i, y + j));
					}
				}
				return cropped.asByteArray();
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
