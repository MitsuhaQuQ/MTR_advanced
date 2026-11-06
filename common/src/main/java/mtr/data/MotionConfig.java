package mtr.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Trains run by one set of rules (R4): the component's, with speed curves, its simulator running the trains of the depots
 * it can run. The old settings (the rules from before they moved to the component, and the simulator off or only
 * comparing) are gone; a settings file left from before is read only to say in the log that they no longer count.
 */
public final class MotionConfig {

	public static final String FILE_NAME = "mtr_motion.json";

	private MotionConfig() {
	}

	public static void load(Path path) {
		try {
			if (Files.exists(path)) {
				final JsonObject jsonObject = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
				final boolean legacy = jsonObject.has("useLegacyMotion") && jsonObject.get("useLegacyMotion").getAsBoolean();
				final boolean notDriving = jsonObject.has("componentDrive") && jsonObject.get("componentDrive").getAsInt() != 2;
				if (legacy || notDriving) {
					System.out.println("Minecraft Transit Railway: " + path + " sets the old motion rules or the component's simulator off, which are gone; trains run by the component's rules");
				}
			}
		} catch (Exception e) {
			System.out.println("Failed to read Minecraft Transit Railway motion settings from " + path);
		}
	}
}
