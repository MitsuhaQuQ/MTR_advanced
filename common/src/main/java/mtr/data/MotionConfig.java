package mtr.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which rules move the trains: the ones in the component, or the ones from before they moved there (tag {@code pre-s2}),
 * kept for comparing in the game while the new ones are tested (decoupling document 5.2, S2). It is one setting for the
 * whole server, in a file in the config folder rather than in the save, and the server sends it to players.
 */
public final class MotionConfig {

	public static final String FILE_NAME = "mtr_motion.json";

	private static final String KEY_USE_LEGACY_MOTION = "useLegacyMotion";

	private static volatile Path configPath;
	private static volatile boolean useLegacyMotion;
	private static volatile boolean useLegacyMotionClient;

	private MotionConfig() {
	}

	/**
	 * Reads the file, or creates it with the new rules. If it can't be read, the setting stays as it was.
	 */
	public static void load(Path path) {
		configPath = path;
		try {
			if (Files.exists(path)) {
				final JsonObject jsonObject = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
				useLegacyMotion = jsonObject.has(KEY_USE_LEGACY_MOTION) && jsonObject.get(KEY_USE_LEGACY_MOTION).getAsBoolean();
			} else {
				useLegacyMotion = false;
				write(path);
			}
		} catch (Exception e) {
			System.out.println("Failed to read Minecraft Transit Railway motion settings from " + path);
			e.printStackTrace();
		}
	}

	/**
	 * @param isClientSide a client follows what its server uses
	 */
	public static boolean useLegacyMotion(boolean isClientSide) {
		return isClientSide ? useLegacyMotionClient : useLegacyMotion;
	}

	/**
	 * Changes the setting on the server and saves it.
	 */
	public static void setUseLegacyMotion(boolean newUseLegacyMotion) {
		useLegacyMotion = newUseLegacyMotion;
		final Path path = configPath;
		if (path != null) {
			try {
				write(path);
			} catch (Exception e) {
				System.out.println("Failed to save Minecraft Transit Railway motion settings to " + path);
				e.printStackTrace();
			}
		}
	}

	/**
	 * What the server told this client.
	 */
	public static void setUseLegacyMotionClient(boolean newUseLegacyMotion) {
		useLegacyMotionClient = newUseLegacyMotion;
	}

	private static void write(Path path) throws Exception {
		final JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty(KEY_USE_LEGACY_MOTION, useLegacyMotion);
		Files.createDirectories(path.getParent());
		Files.write(path, new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject).getBytes(StandardCharsets.UTF_8));
	}
}
