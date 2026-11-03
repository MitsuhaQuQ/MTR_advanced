package mtr;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.audio.AudioFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
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
	public static volatile int audioMaxFileKilobytes = 512;
	public static volatile int audioMaxTotalMegabytes = 64;
	public static volatile int audioMaxSeconds = 60;
	public static volatile int audioUploadsPerHour = 30;

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
				audioMaxFileKilobytes = getInt(jsonObject, KEY_AUDIO_MAX_FILE_KILOBYTES, 512, 0, AudioFiles.MAX_FILE_BYTES / 1024);
				audioMaxTotalMegabytes = getInt(jsonObject, KEY_AUDIO_MAX_TOTAL_MEGABYTES, 64, 0, 4096);
				audioMaxSeconds = getInt(jsonObject, KEY_AUDIO_MAX_SECONDS, 60, 1, 3600);
				audioUploadsPerHour = getInt(jsonObject, KEY_AUDIO_UPLOADS_PER_HOUR, 30, 0, 3600);
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
		jsonObject.addProperty(KEY_AUDIO_MAX_TOTAL_MEGABYTES, audioMaxTotalMegabytes);
		jsonObject.addProperty(KEY_AUDIO_MAX_SECONDS, audioMaxSeconds);
		jsonObject.addProperty(KEY_AUDIO_UPLOADS_PER_HOUR, audioUploadsPerHour);
		Files.createDirectories(path.getParent());
		Files.write(path, new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject).getBytes(StandardCharsets.UTF_8));
	}
}
