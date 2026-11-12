package mtm.testkit.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The text drawn in the last whole frame, as the font drew it (Font.drawInternal), with where (in the coordinates the
 * screen draws in, after its own transforms) and in what colour. Text a screen draws itself, not as a widget (a status
 * line, a label, a conflict summary), can so be read by the tests. Only the render thread writes and swaps it.
 */
public final class ScreenText {

	private static final int KEEP = 600;
	private static List<JsonObject> drawing = new ArrayList<>();
	private static volatile List<JsonObject> lastFrame = new ArrayList<>();

	private ScreenText() {
	}

	public static void record(String text, float x, float y, int color, Matrix4f matrix) {
		if (text == null || text.isEmpty() || drawing.size() >= KEEP) {
			return;
		}
		final Vector3f at = matrix.transformPosition(new Vector3f(x, y, 0));
		final JsonObject entry = new JsonObject();
		entry.addProperty("text", text);
		entry.addProperty("x", Math.round(at.x * 10) / 10.0);
		entry.addProperty("y", Math.round(at.y * 10) / 10.0);
		entry.addProperty("color", String.format("#%06x", color & 0xFFFFFF));
		drawing.add(entry);
	}

	/** At the start of each frame: what was drawn in the frame before becomes the last frame */
	public static void nextFrame() {
		lastFrame = drawing;
		drawing = new ArrayList<>();
	}

	public static JsonArray lastFrame() {
		final JsonArray array = new JsonArray();
		lastFrame.forEach(array::add);
		return array;
	}
}
