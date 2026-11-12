package mtm.testkit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.util.thread.BlockableEventLoop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A small HTTP server on 127.0.0.1 only. Every request must carry the token written next to the port in
 * {@code <game dir>/testkit/endpoint-<side>.json}; requests from a browser page (with an Origin header) are refused.
 * A handler returns a {@link JsonElement}, a {@code byte[]} (sent as a PNG) or null.
 */
public final class Http {

	public interface Handler {
		Object handle(JsonObject body, Map<String, String> query) throws Exception;
	}

	public static final String TOKEN_HEADER = "X-TestKit-Token";
	private static final Gson GSON = new Gson();

	private final HttpServer server;
	private final ExecutorService executor;
	private final Path endpointFile;

	private Http(HttpServer server, ExecutorService executor, Path endpointFile) {
		this.server = server;
		this.executor = executor;
		this.endpointFile = endpointFile;
	}

	public static Http start(String side, Path gameDirectory, Map<String, Handler> routes) throws IOException {
		final byte[] tokenBytes = new byte[24];
		new SecureRandom().nextBytes(tokenBytes);
		final StringBuilder tokenBuilder = new StringBuilder();
		for (final byte b : tokenBytes) {
			tokenBuilder.append(String.format("%02x", b));
		}
		final String token = tokenBuilder.toString();

		final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
		final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
			final Thread thread = new Thread(runnable, "MTM-TestKit-" + side);
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		routes.forEach((path, handler) -> server.createContext(path, exchange -> serve(exchange, token, handler)));
		server.start();

		final Path folder = gameDirectory.resolve("testkit");
		Files.createDirectories(folder);
		final Path endpointFile = folder.resolve("endpoint-" + side + ".json");
		final JsonObject endpoint = new JsonObject();
		endpoint.addProperty("port", server.getAddress().getPort());
		endpoint.addProperty("token", token);
		endpoint.addProperty("pid", ProcessHandle.current().pid());
		Files.write(endpointFile, GSON.toJson(endpoint).getBytes(StandardCharsets.UTF_8));
		TestKit.LOGGER.info("[MTM_TestKit] {} driver listening on 127.0.0.1:{}", side, server.getAddress().getPort());
		return new Http(server, executor, endpointFile);
	}

	public void stop() {
		server.stop(0);
		executor.shutdownNow();
		try {
			Files.deleteIfExists(endpointFile);
		} catch (IOException ignored) {
		}
	}

	/**
	 * Runs on the game thread and waits for the result, so handlers see and change the game between ticks or frames.
	 */
	public static <T> T onThread(BlockableEventLoop<?> loop, Supplier<T> supplier) throws Exception {
		if (loop.isSameThread()) {
			return supplier.get();
		}
		return loop.submit(supplier).get(20, TimeUnit.SECONDS);
	}

	private static void serve(HttpExchange exchange, String token, Handler handler) throws IOException {
		try {
			if (!token.equals(exchange.getRequestHeaders().getFirst(TOKEN_HEADER)) || exchange.getRequestHeaders().containsKey("Origin")) {
				send(exchange, 403, error("forbidden"));
				return;
			}
			final JsonObject body;
			try (InputStream inputStream = exchange.getRequestBody()) {
				final String text = new String(readAll(inputStream), StandardCharsets.UTF_8).trim();
				body = text.isEmpty() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
			}
			final Object result = handler.handle(body, parseQuery(exchange.getRequestURI().getRawQuery()));
			if (result instanceof byte[]) {
				exchange.getResponseHeaders().set("Content-Type", "image/png");
				exchange.sendResponseHeaders(200, ((byte[]) result).length);
				try (OutputStream outputStream = exchange.getResponseBody()) {
					outputStream.write((byte[]) result);
				}
			} else {
				final JsonObject response = new JsonObject();
				response.addProperty("ok", true);
				response.add("result", result instanceof JsonElement ? (JsonElement) result : null);
				send(exchange, 200, response);
			}
		} catch (Throwable e) {
			final Throwable cause = e.getCause() != null && e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
			final StringWriter stringWriter = new StringWriter();
			cause.printStackTrace(new PrintWriter(stringWriter));
			final JsonObject response = error(cause.toString());
			response.addProperty("stack", stringWriter.toString());
			send(exchange, 500, response);
		} finally {
			exchange.close();
		}
	}

	private static JsonObject error(String message) {
		final JsonObject response = new JsonObject();
		response.addProperty("ok", false);
		response.addProperty("error", message);
		return response;
	}

	private static void send(HttpExchange exchange, int status, JsonObject response) throws IOException {
		final byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream outputStream = exchange.getResponseBody()) {
			outputStream.write(bytes);
		}
	}

	private static byte[] readAll(InputStream inputStream) throws IOException {
		final java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		int read;
		while ((read = inputStream.read(buffer)) > 0) {
			outputStream.write(buffer, 0, read);
		}
		return outputStream.toByteArray();
	}

	private static Map<String, String> parseQuery(String rawQuery) {
		final Map<String, String> query = new HashMap<>();
		if (rawQuery != null) {
			for (final String pair : rawQuery.split("&")) {
				final int equals = pair.indexOf('=');
				try {
					if (equals < 0) {
						query.put(URLDecoder.decode(pair, "UTF-8"), "");
					} else {
						query.put(URLDecoder.decode(pair.substring(0, equals), "UTF-8"), URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
					}
				} catch (Exception ignored) {
				}
			}
		}
		return query;
	}
}
