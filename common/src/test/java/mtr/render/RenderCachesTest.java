package mtr.render;

import net.minecraft.core.BlockPos;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** What block drawings keep between frames (0.101.1) and when they look again */
class RenderCachesTest {

	@Test
	void keptUntilASectionWatchedChanges() {
		SectionStamps.clear();
		final BlockPos a = new BlockPos(5, 64, 5);
		final BlockPos b = new BlockPos(20, 64, 5);
		final SectionStamps.Watch watch = new SectionStamps.Watch(Arrays.asList(a, b));
		assertTrue(watch.isCurrent());
		// Another section: still kept
		SectionStamps.onSectionDirty(5, 4, 5);
		assertTrue(watch.isCurrent());
		// The second block's section (x 16 to 31)
		SectionStamps.onSectionDirty(1, 4, 0);
		assertFalse(watch.isCurrent());
		// Another world: nothing kept
		final SectionStamps.Watch again = new SectionStamps.Watch(Collections.singletonList(a));
		assertTrue(again.isCurrent());
		SectionStamps.clear();
		assertFalse(again.isCurrent());
	}

	@Test
	void platformEdgeFacesDrawnAgainWithTheLightOfTheMoment() {
		final RenderPlatformEdge.Mesh mesh = new RenderPlatformEdge.Mesh(new Object[0]);
		// As the drawing code lays a vertex: through the pose, with the light as which light it takes
		mesh.vertex(new Matrix4f(), 0.25F, 1, 0.75F).color(255, 255, 255, 255).uv(0.5F, 0.125F).overlayCoords(0, 10).uv2(RenderPlatformEdge.Mesh.TOP).normal(new Matrix3f(), 0, 1, 0).endVertex();
		mesh.vertex(new Matrix4f(), 1, 0, 0).color(255, 255, 255, 255).uv(0.25F, 0.5F).overlayCoords(0, 10).uv2(RenderPlatformEdge.Mesh.SIDE).normal(new Matrix3f(), 1, 0, 0).endVertex();
		assertEquals(2, mesh.vertices());

		final Recorder recorder = new Recorder();
		mesh.draw(recorder, new Matrix4f().translation(10, 20, 30), new Matrix3f(), 0xF000F0, 0x500050);
		assertEquals(2, recorder.count);
		assertArrayEquals(new float[]{10.25F, 21, 30.75F, 0.5F, 0.125F, 0xF0, 0xF0, 0, 1, 0}, recorder.first, 1E-6F);
		assertEquals(0x50, recorder.lastLightU);
		assertEquals(1, recorder.lastNormalX, 1E-6);
	}

	private static final class Recorder implements com.mojang.blaze3d.vertex.VertexConsumer {

		private final float[] current = new float[10];
		private float[] first;
		private int count;
		private int lastLightU;
		private float lastNormalX;

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer vertex(double x, double y, double z) {
			current[0] = (float) x;
			current[1] = (float) y;
			current[2] = (float) z;
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer color(int red, int green, int blue, int alpha) {
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer uv(float u, float v) {
			current[3] = u;
			current[4] = v;
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer overlayCoords(int u, int v) {
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer uv2(int u, int v) {
			current[5] = u;
			current[6] = v;
			lastLightU = u;
			return this;
		}

		@Override
		public com.mojang.blaze3d.vertex.VertexConsumer normal(float x, float y, float z) {
			current[7] = x;
			current[8] = y;
			current[9] = z;
			lastNormalX = x;
			return this;
		}

		@Override
		public void endVertex() {
			if (first == null) {
				first = current.clone();
			}
			count++;
		}

		@Override
		public void defaultColor(int red, int green, int blue, int alpha) {
		}

		@Override
		public void unsetDefaultColor() {
		}
	}
}
