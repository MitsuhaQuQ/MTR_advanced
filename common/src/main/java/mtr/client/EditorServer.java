package mtr.client;

import mtr.MTR;
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
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
	private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

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

		server = new Server(new QueuedThreadPool(8, 1, 60000));
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
		server.setHandler(context);
		server.start();
	}

	private static boolean matchesKey(String actual) {
		return actual != null && !key.isEmpty() && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * The page itself is open to this computer, but the data needs the key, which the page reads from its address.
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
			if (!"GET".equals(method) && !"HEAD".equals(method)) {
				response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			} else if (!host.equals(LOOPBACK) && !host.equals("localhost")) {
				// Blocks DNS rebinding, where a website points its own domain at this server
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
			} else if (request.getServletPath().startsWith("/api/") && !matchesKey(request.getHeader(KEY_HEADER))) {
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
			} else {
				filterChain.doFilter(servletRequest, servletResponse);
			}
		}
	}

	/**
	 * Reads the data on the game thread, which owns it.
	 */
	private static class DataServlet extends HttpServlet {

		private final boolean revisionOnly;

		private DataServlet(boolean revisionOnly) {
			this.revisionOnly = revisionOnly;
		}

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			final String content;
			try {
				content = CompletableFuture.supplyAsync(() -> {
					final String data = EditorData.build().toString();
					return revisionOnly ? String.format("{\"revision\":%s}", data.hashCode()) : data;
				}, Minecraft.getInstance()).get(DATA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("application/json");
			response.setCharacterEncoding("UTF-8");
			response.setHeader("Cache-Control", "no-store");
			response.getWriter().write(content);
		}
	}
}
