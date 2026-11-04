package mtr.client;

import mtr.model.ModelTrainBase;

import java.util.Locale;

/**
 * A bogie from a resource pack, after MTR 4's bogie1 and bogie2. The position is along the car body in blocks, in the
 * car model's own coordinates. Without a model, the built-in bogie is drawn at that position.
 */
public class CustomBogie {

	public final ModelTrainBase model;
	public final String textureId;
	public final float position;

	public CustomBogie(ModelTrainBase model, String textureId, float position) {
		this.model = model;
		this.textureId = textureId == null ? null : textureId.toLowerCase(Locale.ENGLISH).split("\\.png")[0];
		this.position = position;
	}
}
