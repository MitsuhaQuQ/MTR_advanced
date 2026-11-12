package mtm.testkit;

import mtm.testkit.serverdriver.ServerDriver;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug only. Starts the server driver when a server (dedicated or integrated) has started. See
 * MTR-自动化游戏内测试设计.md.
 */
public final class TestKit implements ModInitializer {

	public static final Logger LOGGER = LoggerFactory.getLogger("MTM_TestKit");
	private static boolean logInstalled;

	@Override
	public void onInitialize() {
		installLogCapture();
		LOGGER.warn("[MTM_TestKit] loaded: this is a debug-only mod for automated tests, never use it on a server people play on");
		ServerLifecycleEvents.SERVER_STARTED.register(ServerDriver::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ServerDriver.stop());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(ServerDriver::tickSensors);
	}

	public static synchronized void installLogCapture() {
		if (!logInstalled) {
			logInstalled = true;
			LogCapture.install();
		}
	}
}
