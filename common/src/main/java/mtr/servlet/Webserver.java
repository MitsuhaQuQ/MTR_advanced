package mtr.servlet;

import mtr.MTR;
import mtr.SecurityConfig;
import mtr.data.DataCache;
import mtr.data.RailwayData;
import mtr.data.Route;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The built-in web server (Jetty) serving the website and the {@code /data}, {@code /info}, {@code /arrivals},
 * {@code /delays} and {@code /route} endpoints. Requests are dealt with on the server thread through {@link #callback}.
 */
public abstract class Webserver {

	public static Consumer<Runnable> callback = Runnable::run;
	public static Supplier<List<Level>> getWorlds = ArrayList::new;
	public static Function<RailwayData, Set<Route>> getRoutes = railwayData -> new HashSet<>();
	public static Function<RailwayData, DataCache> getDataCache = railwayData -> null;

	private static Server webServer;
	private static ServerConnector serverConnector;

	public static void init() {
		// Daemon threads, so they never keep the server's process running once it has stopped
		final QueuedThreadPool threadPool = new QueuedThreadPool(100, 10, 120);
		threadPool.setDaemon(true);
		threadPool.setName("mtr-webserver");
		webServer = new Server(threadPool);
		serverConnector = new ServerConnector(webServer);
		webServer.setConnectors(new Connector[]{serverConnector});
		final ServletContextHandler context = new ServletContextHandler();
		webServer.setHandler(context);
		final URL url = MTR.class.getResource("/assets/mtr/website/");
		if (url != null) {
			try {
				context.setBaseResource(Resource.newResource(url.toURI()));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		context.addFilter(new FilterHolder(new WebSecurityFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));
		final ServletHolder servletHolder = new ServletHolder("default", DefaultServlet.class);
		servletHolder.setInitParameter("dirAllowed", "false");
		servletHolder.setInitParameter("cacheControl", "max-age=0,public");
		context.addServlet(servletHolder, "/");
		context.addServlet(DataServletHandler.class, "/data");
		context.addServlet(InfoServletHandler.class, "/info");
		context.addServlet(ArrivalsServletHandler.class, "/arrivals");
		context.addServlet(DelaysServletHandler.class, "/delays");
		context.addServlet(RouteFinderServletHandler.class, "/route");
	}

	public static void runSafely(Runnable runnable) {
		callback.accept(() -> {
			try {
				runnable.run();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	public static boolean canExposePlayers() {
		return SecurityConfig.isLoopbackBinding() || !SecurityConfig.webserverToken.isEmpty();
	}

	public static void start(Path path) {
		stop();
		if (!SecurityConfig.webserverEnabled) {
			return;
		}

		int port = 8888;
		try {
			port = Mth.clamp(Integer.parseInt(String.join("", Files.readAllLines(path)).replaceAll("\\D", "")), 1025, 65535);
		} catch (Exception ignored) {
			try {
				Files.write(path, Collections.singleton(String.valueOf(port)));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		serverConnector.setHost(SecurityConfig.webserverBindHost);
		serverConnector.setPort(port);
		try {
			webServer.start();
		} catch (Exception e) {
			// As when the port is taken: what did start, such as the thread pool, is stopped again
			System.out.println("Minecraft Transit Railway: the web server couldn't start on port " + port + ": " + e.getMessage());
			stop();
		}
	}

	public static void stop() {
		try {
			// Also after a failed start, which leaves the server neither started nor stopped
			if (!webServer.isStopped()) {
				webServer.stop();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
