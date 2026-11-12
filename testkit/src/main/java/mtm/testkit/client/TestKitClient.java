package mtm.testkit.client;

import mtm.testkit.TestKit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Debug only. Starts the client driver once the game window is up, and records the chat for the tests.
 */
public final class TestKitClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		TestKit.installLogCapture();
		ClientLifecycleEvents.CLIENT_STARTED.register(ClientDriver::start);
		ClientReceiveMessageEvents.GAME.register(ClientDriver::onChat);
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> ClientDriver.onChat(message, false));
	}
}
