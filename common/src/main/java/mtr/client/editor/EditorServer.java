package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.MTR;
import mtr.audio.AudioFiles;
import mtr.client.AudioCache;
import net.minecraft.client.Minecraft;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The advanced editor: a web page this client serves to itself in the system browser. It is separate from the map web
 * server, which a server hosts for everyone. This one only listens on this computer, on a port picked when it starts, and
 * only answers a page opened with the key made for this session. It reads what this client knows, so it works the same in
 * single player and on a server, and changes still go through the game's packets, where the server checks them.
 */
public final class EditorServer {

	private static Server server;
	private static ServerConnector connector;
	private static String key = "";

	private static final String LOOPBACK = "127.0.0.1";
	private static final String KEY_HEADER = "X-MTR-Editor-Key";
	private static final long DATA_TIMEOUT_SECONDS = 10;
	private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
	private static final int MAX_LAYOUT_TRACKS = 64;
	private static final String DRAFTS_FOLDER = "mtr_editor_drafts";
	private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

	/**
	 * Starts the server if it isn't running.
	 *
	 * @return the address to open in a browser, or null if it couldn't start
	 */
	public static synchronized String open() {
		try {
			if (server == null || !server.isStarted()) {
				start();
			}
			return String.format("http://%s:%s/#key=%s", LOOPBACK, connector.getLocalPort(), key);
		} catch (Exception e) {
			e.printStackTrace();
			stop();
			return null;
		}
	}

	/**
	 * Stops the server and forgets its key, such as when leaving a world, so a page left open stops working.
	 */
	public static synchronized void stop() {
		try {
			if (server != null) {
				server.stop();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		server = null;
		connector = null;
		key = "";
	}

	private static void start() throws Exception {
		final byte[] keyBytes = new byte[24];
		new SecureRandom().nextBytes(keyBytes);
		final StringBuilder keyBuilder = new StringBuilder();
		for (final byte keyByte : keyBytes) {
			keyBuilder.append(String.format("%02x", keyByte));
		}
		key = keyBuilder.toString();

		// Daemon threads, so the editor's server never keeps the game's process running after it closes
		final QueuedThreadPool threadPool = new QueuedThreadPool(8, 1, 60000);
		threadPool.setDaemon(true);
		threadPool.setName("mtr-editor");
		server = new Server(threadPool);
		connector = new ServerConnector(server);
		connector.setHost(LOOPBACK);
		connector.setPort(0);
		server.setConnectors(new Connector[]{connector});

		final ServletContextHandler context = new ServletContextHandler();
		final URL url = MTR.class.getResource("/assets/mtr/editor/");
		if (url != null) {
			context.setBaseResource(Resource.newResource(url.toURI()));
		}
		context.addFilter(new FilterHolder(new EditorFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));
		final ServletHolder servletHolder = new ServletHolder("default", DefaultServlet.class);
		servletHolder.setInitParameter("dirAllowed", "false");
		servletHolder.setInitParameter("cacheControl", "no-cache");
		context.addServlet(servletHolder, "/");
		context.addServlet(new ServletHolder(new DataServlet(false)), "/api/data");
		context.addServlet(new ServletHolder(new DataServlet(true)), "/api/revision");
		context.addServlet(new ServletHolder(new ApplyServlet()), "/api/apply");
		context.addServlet(new ServletHolder(new DraftServlet()), "/api/draft");
		context.addServlet(new ServletHolder(new PlanDutiesServlet()), "/api/plan-duties");
		context.addServlet(new ServletHolder(new PlaceTrainsServlet()), "/api/place-trains");
		context.addServlet(new ServletHolder(new ReplanDutiesServlet()), "/api/replan-duties");
		context.addServlet(new ServletHolder(new TimesServlet()), "/api/times");
		context.addServlet(new ServletHolder(new SimulateServlet()), "/api/simulate");
		context.addServlet(new ServletHolder(new LayoutServlet()), "/api/layout");
		context.addServlet(new ServletHolder(new RouteLayoutServlet()), "/api/route-layout");
		context.addServlet(new ServletHolder(new ConsoleServlet()), "/api/console");
		context.addServlet(new ServletHolder(new NetworkServlet()), "/api/network");
		context.addServlet(new ServletHolder(new RoutePreviewServlet()), "/api/route-preview");
		context.addServlet(new ServletHolder(new AudioServlet()), "/api/audio");
		context.addServlet(new ServletHolder(new AudioFileServlet()), "/api/audio-file");
		server.setHandler(context);
		server.start();
	}

	private static boolean matchesKey(String actual) {
		return actual != null && !key.isEmpty() && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * @return true if a request comes from the editor's own page, which a page on another site can't pretend to be
	 */
	private static boolean isOwnOrigin(String origin) {
		final ServerConnector serverConnector = connector;
		if (origin == null || serverConnector == null) {
			return false;
		}
		final int port = serverConnector.getLocalPort();
		return origin.equals("http://" + LOOPBACK + ":" + port) || origin.equals("http://localhost:" + port);
	}

	/**
	 * Runs on the game thread, which owns the data, and waits for the result.
	 */
	private static <T> T onGameThread(Supplier<T> supplier) throws Exception {
		return CompletableFuture.supplyAsync(supplier, Minecraft.getInstance()).get(DATA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static void sendJson(HttpServletResponse response, String content) throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write(content);
	}

	/**
	 * @return the body as text, or null if it is too big
	 */
	private static String readBody(HttpServletRequest request) throws IOException {
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		try (final InputStream inputStream = request.getInputStream()) {
			int read;
			while ((read = inputStream.read(buffer)) > 0) {
				if (outputStream.size() + read > MAX_BODY_BYTES) {
					return null;
				}
				outputStream.write(buffer, 0, read);
			}
		}
		return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
	}

	/**
	 * Drafts are kept apart for each world and server, under the game's config folder, so they survive closing the page and
	 * restarting the game.
	 */
	private static Path getDraftPath() {
		final Minecraft minecraft = Minecraft.getInstance();
		final String world;
		if (minecraft.getSingleplayerServer() != null) {
			world = "world-" + minecraft.getSingleplayerServer().getWorldData().getLevelName();
		} else if (minecraft.getCurrentServer() != null) {
			world = "server-" + minecraft.getCurrentServer().ip;
		} else {
			world = "unknown";
		}
		// Names can have characters a file name can't, so a hash tells apart names that clean up the same
		final String fileName = world.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + Integer.toHexString(world.hashCode()) + ".json";
		return minecraft.gameDirectory.toPath().resolve("config").resolve(DRAFTS_FOLDER).resolve(fileName);
	}

	/**
	 * The page itself is open to this computer, but the data needs the key, which the page reads from its address. Changes
	 * also have to come from the editor's own page as JSON, so another site can't send them through the browser.
	 */
	private static class EditorFilter implements Filter {

		@Override
		public void init(FilterConfig filterConfig) {
		}

		@Override
		public void destroy() {
		}

		@Override
		public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
			final HttpServletRequest request = (HttpServletRequest) servletRequest;
			final HttpServletResponse response = (HttpServletResponse) servletResponse;
			response.setHeader("X-Content-Type-Options", "nosniff");
			response.setHeader("X-Frame-Options", "DENY");
			response.setHeader("Referrer-Policy", "no-referrer");
			response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);

			final String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ENGLISH);
			final String method = request.getMethod();
			final boolean isApi = request.getServletPath().startsWith("/api/");
			final boolean isRead = "GET".equals(method) || "HEAD".equals(method);
			final String contentType = request.getContentType();
			if (!isRead && !(isApi && ("POST".equals(method) || "PUT".equals(method)))) {
				response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			} else if (!host.equals(LOOPBACK) && !host.equals("localhost")) {
				// Blocks DNS rebinding, where a website points its own domain at this server
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
			} else if (isApi && !matchesKey(request.getHeader(KEY_HEADER))) {
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
			} else if (!isRead && (!isOwnOrigin(request.getHeader("Origin")) || contentType == null || !contentType.toLowerCase(Locale.ENGLISH).startsWith("application/json"))) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
			} else {
				filterChain.doFilter(servletRequest, servletResponse);
			}
		}
	}

	private static class DataServlet extends HttpServlet {

		private final boolean revisionOnly;

		private DataServlet(boolean revisionOnly) {
			this.revisionOnly = revisionOnly;
		}

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String content;
			try {
				content = onGameThread(() -> {
					final String data = EditorData.build().toString();
					return revisionOnly ? String.format("{\"revision\":%s}", data.hashCode()) : data;
				});
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			sendJson(response, content);
		}
	}

	/**
	 * Sends the page's changes to the server through the game's packets. See {@link EditorActions}.
	 */
	private static class ApplyServlet extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final JsonObject requestObject;
			try {
				requestObject = new JsonParser().parse(body).getAsJsonObject();
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			final String content;
			try {
				// Made on the game thread, then the server's answer is waited for here, so the game doesn't stop meanwhile
				content = onGameThread(() -> EditorActions.applyAndSend(requestObject)).await().toString();
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			sendJson(response, content);
		}
	}

	/**
	 * The times of the page's draft timetables, worked out by the game's rules ({@link EditorTimes}). Nothing is changed.
	 */
	/**
	 * Starts simulating the day with the page's draft, and gives how it went. See {@link EditorSimulation}.
	 */
	private static class SimulateServlet extends HttpServlet {

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String content;
			try {
				content = onGameThread(() -> EditorSimulation.getStatus().toString());
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			sendJson(response, content);
		}

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final JsonObject requestObject;
			try {
				requestObject = new JsonParser().parse(body).getAsJsonObject();
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			final String content;
			try {
				content = onGameThread(() -> EditorSimulation.start(requestObject).toString());
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			sendJson(response, content);
		}
	}

	private static class TimesServlet extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final JsonObject requestObject;
			try {
				requestObject = new JsonParser().parse(body).getAsJsonObject();
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			final String content;
			try {
				content = onGameThread(() -> EditorTimes.fromDraft(requestObject).toString());
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			sendJson(response, content);
		}
	}

	/**
	 * A first draft of a depot's duties. The data is copied on the game thread and the plan worked out here, so a big
	 * timetable doesn't hold up the game. Nothing is changed; the page puts the result in its draft.
	 */
	/**
	 * Asks the server to put a depot's trains where its timetable has them now; how it went is said in the game's chat.
	 */
	private static class PlaceTrainsServlet extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final long depotId;
			try {
				depotId = Long.parseLong(new JsonParser().parse(body).getAsJsonObject().get("depot").getAsString());
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			try {
				final boolean sent = onGameThread(() -> {
					if (!mtr.client.ClientData.hasPermission() || mtr.client.ClientData.DATA_CACHE.depotIdMap.get(depotId) == null) {
						return false;
					}
					mtr.packet.PacketTrainDataGuiClient.placeTrainsC2S(depotId);
					return true;
				});
				sendJson(response, "{\"ok\":" + sent + "}");
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
		}
	}

	/**
	 * Asks the server to plan the auto duties of a depot's planning unit again rather than keep them (round 4, step 4).
	 */
	private static class ReplanDutiesServlet extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final long depotId;
			try {
				depotId = Long.parseLong(new JsonParser().parse(body).getAsJsonObject().get("depot").getAsString());
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			try {
				final boolean sent = onGameThread(() -> {
					if (!mtr.client.ClientData.hasPermission() || mtr.client.ClientData.DATA_CACHE.depotIdMap.get(depotId) == null) {
						return false;
					}
					mtr.packet.PacketTrainDataGuiClient.replanDutiesC2S(depotId);
					return true;
				});
				sendJson(response, "{\"ok\":" + sent + "}");
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
		}
	}

	private static class PlanDutiesServlet extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final long depotId;
			try {
				depotId = Long.parseLong(new JsonParser().parse(body).getAsJsonObject().get("depot").getAsString());
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			final String content;
			try {
				final Supplier<JsonObject> generation = onGameThread(() -> EditorDuties.prepareGeneration(depotId));
				if (generation == null) {
					response.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				content = generation.get().toString();
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			sendJson(response, content);
		}
	}

	/**
	 * The straightened track diagram of a route ({@link EditorLayout#buildRoute}).
	 */
	/**
	 * The whole network's track layout ({@link EditorNetwork}).
	 */
	private static class NetworkServlet extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final String mode;
			final boolean fresh;
			final int seenVersion;
			try {
				final JsonObject requestObject = new JsonParser().parse(body).getAsJsonObject();
				mode = requestObject.has("mode") ? requestObject.get("mode").getAsString() : "";
				fresh = requestObject.has("fresh") && requestObject.get("fresh").getAsBoolean();
				seenVersion = requestObject.has("version") ? requestObject.get("version").getAsInt() : -1;
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			try {
				sendJson(response, onGameThread(() -> EditorNetwork.build(mode, fresh, seenVersion)).toString());
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
		}
	}

	/**
	 * The dispatcher's console (D6): the last snapshot the server sent, asking it for a new one.
	 */
	private static class ConsoleServlet extends HttpServlet {

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String content;
			try {
				content = onGameThread(() -> EditorConsole.build().toString());
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			sendJson(response, content);
		}
	}

	private static class RouteLayoutServlet extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final long routeId;
			final int halfWidth;
			final boolean fresh;
			try {
				final JsonObject requestObject = new JsonParser().parse(body).getAsJsonObject();
				routeId = Long.parseLong(requestObject.get("route").getAsString());
				halfWidth = requestObject.has("halfWidth") ? requestObject.get("halfWidth").getAsInt() : 0;
				fresh = requestObject.has("fresh") && requestObject.get("fresh").getAsBoolean();
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			try {
				sendJson(response, onGameThread(() -> EditorLayout.buildRoute(routeId, halfWidth, fresh)).toString());
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
		}
	}

	/**
	 * The rails around a station for the track diagram ({@link EditorLayout}).
	 */
	private static class LayoutServlet extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final long stationId;
			final int margin;
			final List<Long> tracks = new ArrayList<>();
			try {
				final JsonObject requestObject = new JsonParser().parse(body).getAsJsonObject();
				stationId = Long.parseLong(requestObject.get("station").getAsString());
				margin = requestObject.has("margin") ? requestObject.get("margin").getAsInt() : 0;
				if (requestObject.has("tracks")) {
					requestObject.getAsJsonArray("tracks").forEach(track -> {
						if (tracks.size() < MAX_LAYOUT_TRACKS) {
							tracks.add(Long.parseLong(track.getAsString()));
						}
					});
				}
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			final JsonObject layout;
			try {
				layout = onGameThread(() -> EditorLayout.build(stationId, margin, tracks));
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			if (layout == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			sendJson(response, layout.toString());
		}
	}

	/**
	 * The way a new route would run between the stops chosen on the whole network map, found by the server
	 * ({@link mtr.packet.RoutePreview}): for each leg its nodes as {x, z, id}, or why it can't be found.
	 */
	private static class RoutePreviewServlet extends HttpServlet {

		private static final int ANSWER_SECONDS = 8;

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final List<Long> platformIds = new ArrayList<>();
			final List<List<Long>> waypoints = new ArrayList<>();
			try {
				final JsonObject requestObject = new JsonParser().parse(body).getAsJsonObject();
				requestObject.getAsJsonArray("platforms").forEach(platform -> {
					if (platformIds.size() < mtr.packet.RoutePreview.MAX_STOPS) {
						platformIds.add(Long.parseLong(platform.getAsString()));
					}
				});
				if (requestObject.has("waypoints")) {
					requestObject.getAsJsonArray("waypoints").forEach(leg -> {
						final List<Long> legWaypoints = new ArrayList<>();
						leg.getAsJsonArray().forEach(node -> legWaypoints.add(Long.parseLong(node.getAsString())));
						waypoints.add(legWaypoints);
					});
				}
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			final JsonObject result = new JsonObject();
			final JsonArray legsArray = new JsonArray();
			try {
				final java.util.concurrent.CompletableFuture<List<mtr.packet.RoutePreview.Leg>> future = onGameThread(() -> mtr.packet.RoutePreview.request(platformIds, waypoints));
				final List<mtr.packet.RoutePreview.Leg> legs = future.get(ANSWER_SECONDS, TimeUnit.SECONDS);
				final List<String> texts = onGameThread(() -> {
					final List<String> list = new ArrayList<>();
					legs.forEach(leg -> list.add(mtr.client.PathFailureText.get(leg.failure)));
					return list;
				});
				for (int i = 0; i < legs.size(); i++) {
					final JsonObject legObject = new JsonObject();
					final JsonArray nodesArray = new JsonArray();
					legs.get(i).nodes.forEach(node -> {
						final net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(node);
						final JsonArray nodeArray = new JsonArray();
						nodeArray.add(pos.getX() + 0.5);
						nodeArray.add(pos.getZ() + 0.5);
						nodeArray.add(String.valueOf(node));
						nodesArray.add(nodeArray);
					});
					legObject.add("nodes", nodesArray);
					legObject.addProperty("found", !legs.get(i).nodes.isEmpty());
					legObject.addProperty("failure", texts.get(i).isEmpty() && legs.get(i).nodes.isEmpty() ? "?" : texts.get(i));
					legsArray.add(legObject);
				}
			} catch (Exception e) {
				result.addProperty("error", "no answer");
			}
			result.add("legs", legsArray);
			sendJson(response, result.toString());
		}
	}

	/**
	 * The announcement audio list, and adding or deleting a file ({@link EditorAudio}). The game answers an upload or a
	 * deletion later, and the page sees it in the list it reads again.
	 */
	private static class AudioServlet extends HttpServlet {

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			try {
				sendJson(response, onGameThread(() -> EditorAudio.build().toString()));
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
		}

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			final String action;
			final String name;
			final String value;
			final String soundId;
			try {
				final JsonObject requestObject = new JsonParser().parse(body).getAsJsonObject();
				action = requestObject.get("action").getAsString();
				name = requestObject.has("name") ? requestObject.get("name").getAsString() : "";
				value = requestObject.get("upload".equals(action) ? "data" : "key").getAsString();
				soundId = requestObject.has("soundId") ? requestObject.get("soundId").getAsString() : "";
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			if (!"upload".equals(action) && !"delete".equals(action) && !"soundId".equals(action)) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			try {
				sendJson(response, onGameThread(() -> ("upload".equals(action) ? EditorAudio.upload(name, soundId, value) : "soundId".equals(action) ? EditorAudio.setSoundId(value, soundId) : EditorAudio.delete(value)).toString()));
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
		}
	}

	/**
	 * A file this client has, for the page to play. One that isn't here yet is asked for, and the page tries again.
	 */
	private static class AudioFileServlet extends HttpServlet {

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String hash = request.getParameter("hash");
			if (!AudioFiles.isHash(hash)) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			final byte[] bytes;
			try {
				bytes = onGameThread(() -> AudioCache.read(hash));
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			if (bytes == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("audio/ogg");
			response.setHeader("Cache-Control", "no-store");
			response.setContentLength(bytes.length);
			response.getOutputStream().write(bytes);
		}
	}

	/**
	 * The page's draft, saved as the page sends it, as only the page reads it back.
	 */
	private static class DraftServlet extends HttpServlet {

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			try {
				final Path path = onGameThread(EditorServer::getDraftPath);
				sendJson(response, Files.exists(path) ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8) : "{}");
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
		}

		@Override
		protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String body = readBody(request);
			if (body == null) {
				response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return;
			}
			try {
				// Only JSON is kept, so a broken draft never gets in the way of opening the editor
				new JsonParser().parse(body).getAsJsonObject();
			} catch (Exception e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			try {
				final Path path = onGameThread(EditorServer::getDraftPath);
				Files.createDirectories(path.getParent());
				Files.write(path, body.getBytes(StandardCharsets.UTF_8));
				sendJson(response, "{\"ok\":true}");
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
		}
	}
}
