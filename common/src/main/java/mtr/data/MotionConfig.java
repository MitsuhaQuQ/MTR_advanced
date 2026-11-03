package mtr.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which rules move the trains: the ones in the component, or the ones from before they moved there (tag {@code pre-s2}),
 * kept for comparing in the game while the new ones are tested (decoupling document 5.2, S2); and whether the
 * component's simulator runs the trains of the depots it can run, or runs next to the game to compare (G1). They are
 * settings for the whole server, in a file in the config folder rather than in the save, and the server sends them to
 * players.
 */
public final class MotionConfig {

	public static final String FILE_NAME = "mtr_motion.json";

	public static final int COMPONENT_DRIVE_OFF = 0;
	/**
	 * The simulator runs next to the game, which still moves the trains, and says in the log how the two compare.
	 */
	public static final int COMPONENT_DRIVE_COMPARE = 1;
	public static final int COMPONENT_DRIVE_ON = 2;
	public static final int COMPONENT_DRIVE_COUNT = 3;

	private static final String KEY_USE_LEGACY_MOTION = "useLegacyMotion";
	private static final String KEY_COMPONENT_DRIVE = "componentDrive";

	private static volatile Path configPath;
	private static volatile boolean useLegacyMotion;
	private static volatile boolean useLegacyMotionClient;
	private static volatile int componentDrive = COMPONENT_DRIVE_COMPARE;
	private static volatile int componentDriveClient = COMPONENT_DRIVE_COMPARE;

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
				componentDrive = jsonObject.has(KEY_COMPONENT_DRIVE) ? clampComponentDrive(jsonObject.get(KEY_COMPONENT_DRIVE).getAsInt()) : COMPONENT_DRIVE_COMPARE;
			} else {
				useLegacyMotion = false;
				componentDrive = COMPONENT_DRIVE_COMPARE;
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
	 * @param isClientSide a client follows what its server uses
	 * @return one of the {@code COMPONENT_DRIVE} values
	 */
	public static int getComponentDrive(boolean isClientSide) {
		return isClientSide ? componentDriveClient : componentDrive;
	}

	/**
	 * Changes the settings on the server and saves them.
	 */
	public static void set(boolean newUseLegacyMotion, int newComponentDrive) {
		useLegacyMotion = newUseLegacyMotion;
		componentDrive = clampComponentDrive(newComponentDrive);
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
	public static void setClient(boolean newUseLegacyMotion, int newComponentDrive) {
		useLegacyMotionClient = newUseLegacyMotion;
		componentDriveClient = clampComponentDrive(newComponentDrive);
	}

	private static int clampComponentDrive(int value) {
		return value < 0 || value >= COMPONENT_DRIVE_COUNT ? COMPONENT_DRIVE_COMPARE : value;
	}

	private static void write(Path path) throws Exception {
		final JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty(KEY_USE_LEGACY_MOTION, useLegacyMotion);
		jsonObject.addProperty(KEY_COMPONENT_DRIVE, componentDrive);
		Files.createDirectories(path.getParent());
		Files.write(path, new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject).getBytes(StandardCharsets.UTF_8));
	}
}
