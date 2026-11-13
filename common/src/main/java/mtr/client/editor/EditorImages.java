package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.client.ImportedImages;
import mtr.image.ImageFiles;
import mtr.mappings.Text;
import java.util.Base64;

/** The image category of the media library, on the game thread. */
public final class EditorImages {
	private EditorImages() { }
	public static JsonObject build() {
		ImportedImages.ensureConnection();
		final JsonObject result = new JsonObject(); result.addProperty("canUpload", ImportedImages.canUpload); result.addProperty("busy", ImportedImages.busy()); result.addProperty("message", ImportedImages.message.isEmpty() ? "" : Text.translatable(ImportedImages.message).getString()); result.addProperty("maxBytes", ImageFiles.MAX_BYTES); result.addProperty("maxDimension", ImageFiles.MAX_DIMENSION);
		final JsonArray entries = new JsonArray();
		result.addProperty("maxTotalBytes", ImportedImages.maxTotalBytes);
		final java.util.Map<String, Integer> unique = new java.util.HashMap<>();
		ImportedImages.ENTRIES.forEach(entry -> unique.put(entry.hash, entry.size));
		result.addProperty("totalBytes", unique.values().stream().mapToLong(Integer::longValue).sum());
		ImportedImages.ENTRIES.forEach(entry -> { final JsonObject item = new JsonObject(); item.addProperty("hash", entry.hash); item.addProperty("name", entry.name); item.addProperty("width", entry.width); item.addProperty("height", entry.height); item.addProperty("size", entry.size); item.addProperty("uploaderName", entry.uploaderName); item.addProperty("uploadedAt", entry.uploadedAt); item.addProperty("canDelete", ImportedImages.DELETABLE.contains(entry.name)); item.addProperty("uploader", entry.uploader); item.addProperty("folder", entry.folder); entries.add(item); }); result.add("entries", entries);
		result.add("folders", EditorAudio.folders(ImportedImages.FOLDERS)); result.addProperty("self", EditorAudio.self()); result.addProperty("canManage", ImportedImages.canManage);
		return result;
	}
	public static JsonObject upload(String name, String data) {
		return upload(name, data, null);
	}
	/**
	 * @param folder the folder to put it in, or null to leave a replaced picture where it is (new ones go to the top)
	 */
	public static JsonObject upload(String name, String data, String folder) {
		final JsonObject result = new JsonObject();
		try { if (data.length() > ImageFiles.MAX_BYTES * 4L / 3 + 8) throw new IllegalArgumentException("gui.mtr.image_invalid"); ImportedImages.upload(name, Base64.getDecoder().decode(data), folder); result.addProperty("ok", true); }
		catch (Exception e) { result.addProperty("ok", false); final String key = e.getMessage(); result.addProperty("message", Text.translatable(e instanceof IllegalArgumentException && key != null && (key.startsWith("gui.mtr.image_") || key.equals(mtr.media.MediaFolders.INVALID)) ? key : "gui.mtr.image_invalid").getString()); }
		return result;
	}
	/**
	 * A folder action (0.74.0), as {@link EditorAudio#folder}; the game answers with a message the list shows.
	 */
	public static JsonObject folder(JsonObject request) {
		final JsonObject result = new JsonObject();
		final int action = EditorAudio.folderAction(request.get("op").getAsString());
		final java.util.List<String> references = new java.util.ArrayList<>();
		if (request.has("keys")) request.getAsJsonArray("keys").forEach(element -> references.add(element.getAsString()));
		if (action < 0) { result.addProperty("ok", false); result.addProperty("message", Text.translatable(mtr.media.MediaFolders.FAILED).getString()); return result; }
		ImportedImages.folderAction(action, EditorAudio.string(request, "owner"), EditorAudio.string(request, "path"), action == mtr.media.MediaFolders.MOVE ? EditorAudio.string(request, "folder") : EditorAudio.string(request, "name"), references);
		result.addProperty("ok", true);
		return result;
	}
	public static JsonObject delete(String reference) {
		final JsonObject result = new JsonObject();
		if (!ImageFiles.isReference(reference) || !ImportedImages.DELETABLE.contains(reference)) { result.addProperty("ok", false); result.addProperty("message", Text.translatable("gui.mtr.image_no_permission").getString()); }
		else { ImportedImages.delete(reference); result.addProperty("ok", true); }
		return result;
	}
}
