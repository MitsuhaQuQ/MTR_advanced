package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.audio.AudioFiles;
import mtr.client.AudioCache;
import mtr.data.TranslatedMessage;

import java.util.Base64;

/**
 * The announcement audio page of the advanced editor: the server's list with what this client has, adding a file and
 * deleting one ({@link AudioCache}). Runs on the game thread.
 */
public final class EditorAudio {

	private EditorAudio() {
	}

	public static JsonObject build() {
		final JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("canUpload", AudioCache.canUpload);
		jsonObject.addProperty("canManage", AudioCache.canManage);
		jsonObject.addProperty("maxFileBytes", AudioCache.maxFileBytes);
		jsonObject.addProperty("maxSeconds", AudioCache.maxSeconds);
		jsonObject.addProperty("maxTotalBytes", AudioCache.maxTotalBytes);
		jsonObject.addProperty("totalBytes", AudioCache.totalBytes);
		jsonObject.addProperty("language", EditorData.getLanguage());

		final JsonArray entries = new JsonArray();
		AudioCache.ENTRIES.forEach(entry -> {
			final JsonObject entryObject = new JsonObject();
			entryObject.addProperty("key", entry.getKey());
			entryObject.addProperty("reference", entry.getReference());
			entryObject.addProperty("hash", entry.hash);
			entryObject.addProperty("name", entry.name);
			entryObject.addProperty("size", entry.size);
			entryObject.addProperty("durationMillis", entry.durationMillis);
			entryObject.addProperty("uploaderName", entry.uploaderName);
			entryObject.addProperty("uploadedAt", entry.uploadedAt);
			entryObject.addProperty("canDelete", AudioCache.canManage || entry.mine);
			entryObject.addProperty("cached", AudioCache.isCached(entry.hash));
			entryObject.addProperty("downloading", AudioCache.isDownloading(entry.hash));
			entries.add(entryObject);
		});
		jsonObject.add("entries", entries);

		final JsonArray requests = new JsonArray();
		AudioCache.REQUESTS.values().forEach(request -> {
			final JsonObject requestObject = new JsonObject();
			requestObject.addProperty("id", request.id);
			requestObject.addProperty("name", request.name);
			requestObject.addProperty("done", request.done);
			requestObject.addProperty("ok", request.ok);
			requestObject.addProperty("message", TranslatedMessage.decode(request.message));
			requests.add(requestObject);
		});
		jsonObject.add("requests", requests);
		return jsonObject;
	}

	/**
	 * @param data the file as base64, as the page reads it
	 */
	public static JsonObject upload(String name, String data) {
		final JsonObject jsonObject = new JsonObject();
		try {
			final byte[] bytes;
			try {
				bytes = Base64.getDecoder().decode(data);
			} catch (IllegalArgumentException e) {
				throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_read_failed"));
			}
			jsonObject.addProperty("id", AudioCache.upload(name, bytes));
			jsonObject.addProperty("ok", true);
		} catch (AudioFiles.InvalidAudioException e) {
			jsonObject.addProperty("ok", false);
			jsonObject.addProperty("message", TranslatedMessage.decode(e.getMessage()));
		}
		return jsonObject;
	}

	/**
	 * @param key the name key of what to delete ({@link AudioCache.Entry#getKey})
	 */
	public static JsonObject delete(String key) {
		final JsonObject jsonObject = new JsonObject();
		final AudioCache.Entry entry = AudioCache.ENTRIES.stream().filter(checkEntry -> checkEntry.getKey().equals(key)).findFirst().orElse(null);
		if (entry == null) {
			jsonObject.addProperty("ok", false);
			jsonObject.addProperty("message", TranslatedMessage.get("gui.mtr.audio_missing"));
		} else {
			jsonObject.addProperty("id", AudioCache.delete(entry));
			jsonObject.addProperty("ok", true);
		}
		return jsonObject;
	}
}
