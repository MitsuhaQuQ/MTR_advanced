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
		ImportedImages.ENTRIES.forEach(entry -> { final JsonObject item = new JsonObject(); item.addProperty("hash", entry.hash); item.addProperty("name", entry.name); item.addProperty("width", entry.width); item.addProperty("height", entry.height); item.addProperty("size", entry.size); item.addProperty("uploaderName", entry.uploaderName); item.addProperty("uploadedAt", entry.uploadedAt); item.addProperty("canDelete", ImportedImages.DELETABLE.contains(entry.name)); entries.add(item); }); result.add("entries", entries); return result;
	}
	public static JsonObject upload(String name, String data) {
		final JsonObject result = new JsonObject();
		try { if (data.length() > ImageFiles.MAX_BYTES * 4L / 3 + 8) throw new IllegalArgumentException("gui.mtr.image_invalid"); ImportedImages.upload(name, Base64.getDecoder().decode(data)); result.addProperty("ok", true); }
		catch (Exception e) { result.addProperty("ok", false); result.addProperty("message", Text.translatable(e instanceof IllegalArgumentException && e.getMessage() != null && e.getMessage().startsWith("gui.mtr.image_") ? e.getMessage() : "gui.mtr.image_invalid").getString()); }
		return result;
	}
	public static JsonObject delete(String reference) {
		final JsonObject result = new JsonObject();
		if (!ImageFiles.isReference(reference) || !ImportedImages.DELETABLE.contains(reference)) { result.addProperty("ok", false); result.addProperty("message", Text.translatable("gui.mtr.image_no_permission").getString()); }
		else { ImportedImages.delete(reference); result.addProperty("ok", true); }
		return result;
	}
}
