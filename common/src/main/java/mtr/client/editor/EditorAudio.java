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
			entryObject.addProperty("soundId", entry.soundId);
			// Its id and the references it had before (0.79.0)
			entryObject.addProperty("resourceId", entry.resourceId);
			final JsonArray oldNames = new JsonArray();
			entry.oldNames.forEach(oldNames::add);
			entryObject.add("oldNames", oldNames);
			final net.minecraft.resources.ResourceLocation soundLocation = entry.getSoundLocation();
			entryObject.addProperty("sound", soundLocation == null ? "" : soundLocation.toString());
			entryObject.addProperty("size", entry.size);
			entryObject.addProperty("durationMillis", entry.durationMillis);
			entryObject.addProperty("uploaderName", entry.uploaderName);
			entryObject.addProperty("uploadedAt", entry.uploadedAt);
			entryObject.addProperty("canDelete", AudioCache.canManage || entry.mine);
			entryObject.addProperty("mine", entry.mine);
			entryObject.addProperty("cached", AudioCache.isCached(entry.hash));
			entryObject.addProperty("downloading", AudioCache.isDownloading(entry.hash));
			entryObject.addProperty("uploader", entry.uploader);
			entryObject.addProperty("folder", entry.folder);
			entries.add(entryObject);
		});
		jsonObject.add("entries", entries);
		jsonObject.add("folders", folders(AudioCache.FOLDERS));
		jsonObject.addProperty("self", self());

		AudioCache.expireRequests();
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
	/**
	 * @param soundId the sound ID to give it, or empty for the server to make one from the file name
	 */
	public static JsonObject upload(String name, String soundId, String data) {
		return upload(name, soundId, data, null);
	}

	/**
	 * @param folder the folder to put it in, or null to leave a replaced file where it is (new ones go to the top)
	 */
	public static JsonObject upload(String name, String soundId, String data, String folder) {
		final JsonObject jsonObject = new JsonObject();
		try {
			final byte[] bytes;
			try {
				bytes = Base64.getDecoder().decode(data);
			} catch (IllegalArgumentException e) {
				throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_read_failed"));
			}
			jsonObject.addProperty("id", AudioCache.upload(name, soundId, bytes, folder));
			jsonObject.addProperty("ok", true);
		} catch (AudioFiles.InvalidAudioException e) {
			jsonObject.addProperty("ok", false);
			jsonObject.addProperty("message", TranslatedMessage.decode(e.getMessage()));
		}
		return jsonObject;
	}

	/**
	 * Puts an uploaded file in place of the one with this id (0.79.0), keeping its id, folder and sound ID unless another
	 * sound ID is given.
	 */
	public static JsonObject replace(String resourceId, String name, String soundId, String data) {
		final JsonObject jsonObject = new JsonObject();
		try {
			if (!mtr.media.MediaIds.isId(resourceId) || AudioCache.ENTRIES.stream().noneMatch(entry -> entry.resourceId.equals(resourceId))) {
				throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_missing"));
			}
			final byte[] bytes;
			try {
				bytes = Base64.getDecoder().decode(data);
			} catch (IllegalArgumentException e) {
				throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_read_failed"));
			}
			jsonObject.addProperty("id", AudioCache.upload(name, soundId, bytes, null, resourceId));
			jsonObject.addProperty("ok", true);
		} catch (AudioFiles.InvalidAudioException e) {
			jsonObject.addProperty("ok", false);
			jsonObject.addProperty("message", TranslatedMessage.decode(e.getMessage()));
		}
		return jsonObject;
	}

	/**
	 * @param key the name key of the file ({@link AudioCache.Entry#getKey})
	 */
	public static JsonObject setSoundId(String key, String soundId) {
		final JsonObject jsonObject = new JsonObject();
		final AudioCache.Entry entry = AudioCache.ENTRIES.stream().filter(checkEntry -> checkEntry.getKey().equals(key)).findFirst().orElse(null);
		try {
			if (entry == null) {
				throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_missing"));
			}
			jsonObject.addProperty("id", AudioCache.setSoundId(entry, soundId));
			jsonObject.addProperty("ok", true);
		} catch (AudioFiles.InvalidAudioException e) {
			jsonObject.addProperty("ok", false);
			jsonObject.addProperty("message", TranslatedMessage.decode(e.getMessage()));
		}
		return jsonObject;
	}

	/**
	 * A folder action (0.74.0): {@code op} create, rename, delete or move; {@code owner} the uploader (UUID); {@code path}
	 * the folder; {@code name} the new name (create, rename); {@code folder} and {@code keys} for moving files.
	 */
	public static JsonObject folder(JsonObject request) {
		final JsonObject jsonObject = new JsonObject();
		final int action = folderAction(request.get("op").getAsString());
		final java.util.List<String> keys = new java.util.ArrayList<>();
		if (request.has("keys")) {
			request.getAsJsonArray("keys").forEach(element -> keys.add(element.getAsString()));
		}
		final String argument = action == mtr.media.MediaFolders.MOVE ? string(request, "folder") : string(request, "name");
		if (action < 0) {
			jsonObject.addProperty("ok", false);
			jsonObject.addProperty("message", TranslatedMessage.get(mtr.media.MediaFolders.FAILED));
		} else {
			jsonObject.addProperty("id", AudioCache.folderAction(action, string(request, "owner"), string(request, "path"), argument, keys));
			jsonObject.addProperty("ok", true);
		}
		return jsonObject;
	}

	static int folderAction(String op) {
		switch (op) {
			case "create":
				return mtr.media.MediaFolders.CREATE;
			case "rename":
				return mtr.media.MediaFolders.RENAME;
			case "delete":
				return mtr.media.MediaFolders.DELETE;
			case "move":
				return mtr.media.MediaFolders.MOVE;
			default:
				return -1;
		}
	}

	static String string(JsonObject request, String key) {
		return request.has(key) && !request.get(key).isJsonNull() ? request.get(key).getAsString() : "";
	}

	/**
	 * The kept folders, as the page builds its tree from them and the files' folders.
	 */
	static JsonArray folders(java.util.List<mtr.media.MediaFolders.Folder> folders) {
		final JsonArray array = new JsonArray();
		folders.forEach(folder -> {
			final JsonObject folderObject = new JsonObject();
			folderObject.addProperty("owner", folder.owner);
			folderObject.addProperty("ownerName", folder.ownerName);
			folderObject.addProperty("path", folder.path);
			array.add(folderObject);
		});
		return array;
	}

	/**
	 * This player's UUID, the owner of the folders they upload into.
	 */
	static String self() {
		final net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		return player == null ? "" : player.getUUID().toString();
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
