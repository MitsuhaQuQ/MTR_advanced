package mtr.image;

import com.google.gson.Gson;

/** Display coordinates are blocks in the painting's local frame; its vanilla anchor is unchanged. */
public final class PaintingSettings {
	public String image = "";
	public double x, y, z;
	public double width = 1, height = 1;
	public double rotationX, rotationY, rotationZ;
	private static final Gson GSON = new Gson();

	public String json() { validate(); return GSON.toJson(this); }
	public static PaintingSettings parse(String json) {
		final PaintingSettings settings = json == null || json.isEmpty() ? new PaintingSettings() : GSON.fromJson(json, PaintingSettings.class);
		if (settings == null) throw new IllegalArgumentException("gui.mtr.image_invalid");
		settings.validate();
		return settings;
	}
	private void validate() {
		if (!finite(rotationX) || !finite(rotationY) || !finite(rotationZ) || Math.abs(rotationX) > 360 || Math.abs(rotationY) > 360 || Math.abs(rotationZ) > 360) throw new IllegalArgumentException("gui.mtr.image_invalid_settings");
		if (image == null || (!image.isEmpty() && !ImageFiles.isReference(image)) || !finite(x) || !finite(y) || !finite(z) || !finite(width) || !finite(height) || Math.abs(x) > 16 || Math.abs(y) > 16 || Math.abs(z) > 16 || width < 0.0625 || height < 0.0625 || width > 16 || height > 16) throw new IllegalArgumentException("gui.mtr.image_invalid_settings");
	}
	private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
