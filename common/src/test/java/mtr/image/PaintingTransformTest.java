package mtr.image;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaintingTransformTest {
	@Test void oldSavesDefaultToNoRotationAndNewAnglesRoundTrip() {
		PaintingSettings old = PaintingSettings.parse("{\"image\":\"steve:test.png\"}");
		assertEquals(0, old.rotationX); assertEquals(0, old.rotationY); assertEquals(0, old.rotationZ);
		old.rotationX = 12.5; old.rotationY = -90; old.rotationZ = 180;
		PaintingSettings copy = PaintingSettings.parse(old.json());
		assertEquals(12.5,copy.rotationX); assertEquals(-90,copy.rotationY); assertEquals(180,copy.rotationZ);
		old.rotationX = Double.POSITIVE_INFINITY;
		assertThrows(IllegalArgumentException.class,old::json);
		assertThrows(IllegalArgumentException.class,()->PaintingSettings.parse("{\"rotationY\":361}"));
	}
	@Test void rotatesInDocumentedOrderAndKeepsTheCentreFixed() {
		assertArrayEquals(new double[]{0,0,0},PaintingTransform.rotate(0,0,0,37,-56,110),1e-9);
		assertArrayEquals(new double[]{0,0,1},PaintingTransform.rotate(0,1,0,90,0,0),1e-9);
		assertArrayEquals(new double[]{1,0,0},PaintingTransform.rotate(0,0,1,0,90,0),1e-9);
		assertArrayEquals(new double[]{0,1,0},PaintingTransform.rotate(1,0,0,0,0,90),1e-9);
		assertArrayEquals(new double[]{0,1,0},PaintingTransform.rotate(0,1,0,90,90,90),1e-9);
		assertEquals(-179,PaintingTransform.wrap(181));
		assertEquals(179,PaintingTransform.wrap(-181));
	}
}
