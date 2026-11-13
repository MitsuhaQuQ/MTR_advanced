package mtm.testkit.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import mtm.testkit.Http;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compares the picture with a render setting on and off (2026-11-13), to check that a faster way of drawing draws the
 * same: the same view is captured, the setting switched, captured again after the frames have settled, and switched
 * back. The two pictures are saved and counted pixel by pixel. Settings:
 * <ul>
 * <li>{@code bake}: MTR's own train models from baked buffers (ANTE {@code ClientConfig.bakeBuiltInTrains}, 0.73.5)</li>
 * <li>{@code optimization}: ANTE's buffers at all ({@code ClientConfig.enableOptimization}), which also switches the flat
 * rails between chunk buffers and MTR's quad-by-quad drawing (0.73.4)</li>
 * </ul>
 */
public final class RenderCompare {

	private RenderCompare() {
	}

	/**
	 * @param body {@code setting}, {@code settleMs} (default 1500), {@code tolerance} (a channel difference that still
	 *             counts as the same, default 8), {@code name} (for the saved pictures)
	 */
	public static JsonObject compare(Minecraft minecraft, JsonObject body) throws Exception {
		final String setting = body.get("setting").getAsString();
		final long settleMs = body.has("settleMs") ? body.get("settleMs").getAsLong() : 1500;
		final int tolerance = body.has("tolerance") ? body.get("tolerance").getAsInt() : 8;
		final String name = body.has("name") ? body.get("name").getAsString().replaceAll("[^A-Za-z0-9_-]", "_") : setting;
		final boolean before = Http.onThread(minecraft, () -> get(setting));
		final NativeImage first = Http.onThread(minecraft, () -> Screenshot.takeScreenshot(minecraft.getMainRenderTarget()));
		NativeImage second = null;
		try {
			Http.onThread(minecraft, () -> { set(setting, !before); return null; });
			Thread.sleep(settleMs);
			second = Http.onThread(minecraft, () -> Screenshot.takeScreenshot(minecraft.getMainRenderTarget()));
		} finally {
			Http.onThread(minecraft, () -> { set(setting, before); return null; });
		}
		try {
			final Path folder = minecraft.gameDirectory.toPath().resolve("testkit").resolve("compare");
			Files.createDirectories(folder);
			first.writeToFile(folder.resolve(name + "-" + (before ? "on" : "off") + ".png"));
			second.writeToFile(folder.resolve(name + "-" + (before ? "off" : "on") + ".png"));
			final JsonObject result = new JsonObject();
			result.addProperty("setting", setting);
			result.addProperty("was", before);
			result.addProperty("folder", folder.toString());
			if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight()) {
				result.addProperty("error", "sizes differ");
				return result;
			}
			long differing = 0;
			int maxDelta = 0;
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
			for (int y = 0; y < first.getHeight(); y++) {
				for (int x = 0; x < first.getWidth(); x++) {
					final int a = first.getPixelRGBA(x, y), b = second.getPixelRGBA(x, y);
					int delta = 0;
					for (int shift = 0; shift < 24; shift += 8) {
						delta = Math.max(delta, Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF)));
					}
					maxDelta = Math.max(maxDelta, delta);
					if (delta > tolerance) {
						differing++;
						minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
					}
				}
			}
			final long pixels = (long) first.getWidth() * first.getHeight();
			result.addProperty("pixels", pixels);
			result.addProperty("differing", differing);
			result.addProperty("share", (double) differing / pixels);
			result.addProperty("maxDelta", maxDelta);
			if (differing > 0) {
				result.addProperty("box", minX + "," + minY + "," + maxX + "," + maxY);
			}
			return result;
		} finally {
			first.close();
			if (second != null) {
				second.close();
			}
		}
	}

	private static boolean get(String setting) {
		switch (setting) {
			case "bake":
				return cn.zbx1425.mtrsteamloco.ClientConfig.bakeBuiltInTrains;
			case "optimization":
				return cn.zbx1425.mtrsteamloco.ClientConfig.enableOptimization;
			default:
				throw new IllegalArgumentException("no setting " + setting);
		}
	}

	private static void set(String setting, boolean value) {
		switch (setting) {
			case "bake":
				cn.zbx1425.mtrsteamloco.ClientConfig.bakeBuiltInTrains = value;
				break;
			case "optimization":
				cn.zbx1425.mtrsteamloco.ClientConfig.enableOptimization = value;
				break;
			default:
				throw new IllegalArgumentException("no setting " + setting);
		}
	}
}
