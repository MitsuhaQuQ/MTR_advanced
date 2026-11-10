package mtr.image;

/** A vanilla painting's additional tracked and saved display settings. */
public interface PaintingImage {
	String mtr$getImageSettings();
	void mtr$setImageSettings(String settings);
}
