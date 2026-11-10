package mtr.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;

public final class WorldRenderVisibility {

	private static Frustum frustum;

	private WorldRenderVisibility() {
	}

	public static void setFrustum(Frustum currentFrustum) {
		frustum = currentFrustum;
	}

	public static boolean hasFrustum() {
		return frustum != null;
	}

	public static boolean isVisible(double x, double y, double z, double radius) {
		return isVisible(x, y, z, x, y, z, radius);
	}

	public static boolean isVisible(double x, double y, double z, double endX, double endY, double endZ, double padding) {
		return frustum == null || !Double.isFinite(padding)
				|| frustum.isVisible(new AABB(
						Math.min(x, endX) - padding, Math.min(y, endY) - padding, Math.min(z, endZ) - padding,
						Math.max(x, endX) + padding, Math.max(y, endY) + padding, Math.max(z, endZ) + padding));
	}
}
