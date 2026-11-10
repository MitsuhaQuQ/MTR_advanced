package mtr.render;

import net.minecraft.client.renderer.culling.Frustum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldRenderVisibilityTest {

	@AfterEach
	void clearFrustum() {
		WorldRenderVisibility.setFrustum(null);
	}

	@Test
	void retainsOversizedModelsAndConnectionsAtTheEdgeOfTheView() throws ReflectiveOperationException {
		final Frustum frustum = createFrustum();
		frustum.prepare(1000, 0, 0);
		WorldRenderVisibility.setFrustum(frustum);

		assertFalse(WorldRenderVisibility.isVisible(1003, 0, 0, 0.5));
		assertTrue(WorldRenderVisibility.isVisible(1003, 0, 0, 2.5));
		assertTrue(WorldRenderVisibility.isVisible(1003, 0, 0, 1000, 0, 0, 0.5));
		assertFalse(WorldRenderVisibility.isVisible(1003, 0, 0, 1005, 0, 0, 0.5));
	}

	@Test
	void keepsModelsWhenGeometryOrViewIsUnknown() throws ReflectiveOperationException {
		final Frustum frustum = createFrustum();
		WorldRenderVisibility.setFrustum(frustum);
		assertTrue(WorldRenderVisibility.isVisible(20, 0, 0, Double.POSITIVE_INFINITY));

		WorldRenderVisibility.setFrustum(null);
		assertTrue(WorldRenderVisibility.isVisible(20, 0, 0, 1.5));
	}

	private static Frustum createFrustum() throws ReflectiveOperationException {
		for (final Constructor<?> constructor : Frustum.class.getConstructors()) {
			if (constructor.getParameterCount() == 2) {
				final Class<?> matrixClass = constructor.getParameterTypes()[0];
				final Object identity = matrixClass.getConstructor().newInstance();
				if (matrixClass.getName().startsWith("com.mojang.")) {
					matrixClass.getMethod("setIdentity").invoke(identity);
				}
				return (Frustum) constructor.newInstance(identity, identity);
			}
		}
		throw new NoSuchMethodException("Frustum projection constructor");
	}
}
