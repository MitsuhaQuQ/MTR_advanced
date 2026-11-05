package mtr;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.audio.AudioFiles;
import net.minecraft.world.item.DyeColor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

public final class SecurityConfig {

	private static final String DEFAULT_BIND_HOST = "127.0.0.1";

	public static volatile boolean webserverEnabled = true;
	public static volatile String webserverBindHost = DEFAULT_BIND_HOST;
	public static volatile String webserverToken = "";
	public static volatile String webserverCorsOrigin = "";
	public static volatile Set<String> webserverAllowedHosts = Collections.emptySet();
	public static volatile int editPermissionLevel = 0;
	// Announcement audio: who can add files, who can delete anyone's, and how much each file and the server can hold
	public static volatile int audioUploadPermissionLevel = 2;
	public static volatile int audioManagePermissionLevel = 2;
	public static volatile int audioMaxFileKilobytes = 2048;
	public static volatile int audioMaxTotalMegabytes = 64;
	public static volatile int audioMaxSeconds = 60;
	public static volatile int audioUploadsPerHour = 30;
	/**
	 * Trains on duty lock their way through station throats (L2, {@code ThroatLocks}); off, they run as before.
	 */
	public static volatile boolean throatLocks = true;
	/**
	 * The advanced editor's fast simulation of a day ("模拟运行", S5), a debugging tool, which takes a lot of the server's
	 * time in a big world; off, the editor doesn't offer it and the server refuses it.
	 */
	public static volatile boolean editorSimulation = false;
	/**
	 * The signal block colour of interlocking zones (L2): blocks of it in a station throat are taken as a whole by a route
	 * through it, besides being signal blocks as before.
	 */
	public static volatile DyeColor interlockColor = DyeColor.MAGENTA;
	/**
	 * Long rails are cut into block sections of the braking distance at their speed limit for this
	 * deceleration, in m/s², and at least this long, in blocks ({@code BlockSections}).
	 */
	public static volatile double blockDeceleration = 1.0;
	public static volatile int blockMinLength = 400;

	private static final String KEY_WEBSERVER_ENABLED = "webserverEnabled";
	private static final String KEY_WEBSERVER_BIND_HOST = "webserverBindHost";
	private static final String KEY_WEBSERVER_TOKEN = "webserverToken";
	private static final String KEY_WEBSERVER_CORS_ORIGIN = "webserverCorsOrigin";
	private static final String KEY_WEBSERVER_ALLOWED_HOSTS = "webserverAllowedHosts";
	private static final String KEY_EDIT_PERMISSION_LEVEL = "editPermissionLevel";
	private static final String KEY_AUDIO_UPLOAD_PERMISSION_LEVEL = "audioUploadPermissionLevel";
	private static final String KEY_AUDIO_MANAGE_PERMISSION_LEVEL = "audioManagePermissionLevel";
	private static final String KEY_AUDIO_MAX_FILE_KILOBYTES = "audioMaxFileKilobytes";
	private static final String KEY_AUDIO_MAX_TOTAL_MEGABYTES = "audioMaxTotalMegabytes";
	private static final String KEY_AUDIO_MAX_SECONDS = "audioMaxSeconds";
	private static final String KEY_AUDIO_UPLOADS_PER_HOUR = "audioUploadsPerHour";
	private static final String KEY_THROAT_LOCKS = "throatLocks";
	private static final String KEY_EDITOR_SIMULATION = "editorSimulation";
	private static final String KEY_INTERLOCK_COLOR = "interlockColor";
	private static final String KEY_BLOCK_DECELERATION = "blockDeceleration";
	private static final String KEY_BLOCK_MIN_LENGTH = "blockMinLength";

	private SecurityConfig() {
	}

	public static void load(Path path) {
		try {
			if (Files.exists(path)) {
				final JsonObject jsonObject = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
				webserverEnabled = !jsonObject.has(KEY_WEBSERVER_ENABLED) || jsonObject.get(KEY_WEBSERVER_ENABLED).getAsBoolean();
				webserverBindHost = getString(jsonObject, KEY_WEBSERVER_BIND_HOST, DEFAULT_BIND_HOST);
				webserverToken = getString(jsonObject, KEY_WEBSERVER_TOKEN, "");
				webserverCorsOrigin = getString(jsonObject, KEY_WEBSERVER_CORS_ORIGIN, "");
				editPermissionLevel = jsonObject.has(KEY_EDIT_PERMISSION_LEVEL) ? Math.max(0, Math.min(4, jsonObject.get(KEY_EDIT_PERMISSION_LEVEL).getAsInt())) : 0;
				final Set<String> allowedHosts = new HashSet<>();
				if (jsonObject.has(KEY_WEBSERVER_ALLOWED_HOSTS)) {
					jsonObject.getAsJsonArray(KEY_WEBSERVER_ALLOWED_HOSTS).forEach(host -> allowedHosts.add(host.getAsString().toLowerCase(Locale.ENGLISH)));
				}
				webserverAllowedHosts = allowedHosts;
				audioUploadPermissionLevel = getInt(jsonObject, KEY_AUDIO_UPLOAD_PERMISSION_LEVEL, 2, 0, 4);
				audioManagePermissionLevel = getInt(jsonObject, KEY_AUDIO_MANAGE_PERMISSION_LEVEL, 2, 0, 4);
				audioMaxFileKilobytes = getInt(jsonObject, KEY_AUDIO_MAX_FILE_KILOBYTES, 2048, 0, AudioFiles.MAX_FILE_BYTES / 1024);
				audioMaxTotalMegabytes = getInt(jsonObject, KEY_AUDIO_MAX_TOTAL_MEGABYTES, 64, 0, 4096);
				audioMaxSeconds = getInt(jsonObject, KEY_AUDIO_MAX_SECONDS, 60, 1, 3600);
				audioUploadsPerHour = getInt(jsonObject, KEY_AUDIO_UPLOADS_PER_HOUR, 30, 0, 3600);
				throatLocks = !jsonObject.has(KEY_THROAT_LOCKS) || jsonObject.get(KEY_THROAT_LOCKS).getAsBoolean();
				editorSimulation = jsonObject.has(KEY_EDITOR_SIMULATION) && jsonObject.get(KEY_EDITOR_SIMULATION).getAsBoolean();
				interlockColor = parseColor(jsonObject.has(KEY_INTERLOCK_COLOR) ? jsonObject.get(KEY_INTERLOCK_COLOR).getAsString() : "");
				blockDeceleration = jsonObject.has(KEY_BLOCK_DECELERATION) ? Math.max(0.1, Math.min(10, jsonObject.get(KEY_BLOCK_DECELERATION).getAsDouble())) : 1.0;
				blockMinLength = getInt(jsonObject, KEY_BLOCK_MIN_LENGTH, 400, 16, 10000);
			} else {
				write(path);
			}
		} catch (Exception e) {
			// Invalid settings should fail closed instead of exposing the web server
			System.out.println("Failed to read Minecraft Transit Railway security settings from " + path);
			e.printStackTrace();
			webserverEnabled = false;
			audioMaxFileKilobytes = 0;
		}
	}

	/**
	 * Hands the settings the simulation component reads to it, after they are loaded.
	 */
	public static void apply() {
		transitsim.sim.ThroatLocks.enabled = throatLocks;
		transitsim.network.BlockSections.deceleration = blockDeceleration;
		transitsim.network.BlockSections.minLength = blockMinLength;
	}

	public static boolean isLoopbackBinding() {
		final String host = webserverBindHost.toLowerCase(Locale.ENGLISH);
		return host.equals("localhost") || host.equals("::1") || host.startsWith("127.");
	}

	private static String getString(JsonObject jsonObject, String key, String defaultValue) {
		final JsonElement jsonElement = jsonObject.get(key);
		return jsonElement == null ? defaultValue : jsonElement.getAsString();
	}

	private static int getInt(JsonObject jsonObject, String key, int defaultValue, int minimum, int maximum) {
		final JsonElement jsonElement = jsonObject.get(key);
		return Math.max(minimum, Math.min(maximum, jsonElement == null ? defaultValue : jsonElement.getAsInt()));
	}

	private static void write(Path path) throws Exception {
		final JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty(KEY_WEBSERVER_ENABLED, webserverEnabled);
		jsonObject.addProperty(KEY_WEBSERVER_BIND_HOST, webserverBindHost);
		jsonObject.addProperty(KEY_WEBSERVER_TOKEN, webserverToken);
		jsonObject.addProperty(KEY_WEBSERVER_CORS_ORIGIN, webserverCorsOrigin);
		jsonObject.add(KEY_WEBSERVER_ALLOWED_HOSTS, new JsonArray());
		jsonObject.addProperty(KEY_EDIT_PERMISSION_LEVEL, editPermissionLevel);
		jsonObject.addProperty(KEY_AUDIO_UPLOAD_PERMISSION_LEVEL, audioUploadPermissionLevel);
		jsonObject.addProperty(KEY_AUDIO_MANAGE_PERMISSION_LEVEL, audioManagePermissionLevel);
		jsonObject.addProperty(KEY_AUDIO_MAX_FILE_KILOBYTES, audioMaxFileKilobytes);
		jsonObject.addProperty(KEY_THROAT_LOCKS, throatLocks);
		jsonObject.addProperty(KEY_EDITOR_SIMULATION, editorSimulation);
		jsonObject.addProperty(KEY_INTERLOCK_COLOR, interlockColor.name().toLowerCase(Locale.ROOT));
		jsonObject.addProperty(KEY_BLOCK_DECELERATION, blockDeceleration);
		jsonObject.addProperty(KEY_BLOCK_MIN_LENGTH, blockMinLength);
		jsonObject.addProperty(KEY_AUDIO_MAX_TOTAL_MEGABYTES, audioMaxTotalMegabytes);
		jsonObject.addProperty(KEY_AUDIO_MAX_SECONDS, audioMaxSeconds);
		jsonObject.addProperty(KEY_AUDIO_UPLOADS_PER_HOUR, audioUploadsPerHour);
		Files.createDirectories(path.getParent());
		Files.write(path, new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * @return the dye colour of this name, such as {@code magenta}, or magenta for anything else
	 */
	public static DyeColor parseColor(String name) {
		for (final DyeColor color : DyeColor.values()) {
			if (color.name().equalsIgnoreCase(name.trim())) {
				return color;
			}
		}
		return DyeColor.MAGENTA;
	}
}
