package mtr.block;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A curved screen door's bend read from bad data (0.100.0): no bend or no curve, never an error */
class CurvedPSDBendReadTest {

	@Test
	void angleOutOfRange() {
		final CompoundTag tag = new CompoundTag();
		tag.putInt("bend_angle", 999);
		tag.putInt("bend_cut", 999);
		final CurvedPSD.BendHolder holder = new CurvedPSD.BendHolder();
		assertDoesNotThrow(() -> holder.read(tag));
		assertNull(holder.getBend());
	}

	@Test
	void curveNotNumbers() {
		final CompoundTag tag = new CompoundTag();
		tag.putLong("curve_from", 1);
		tag.putLong("curve_to", 2);
		tag.putDouble("curve_offset", Double.NaN);
		tag.putDouble("curve_start", 0);
		tag.putDouble("curve_end", Double.POSITIVE_INFINITY);
		final CurvedPSD.BendHolder holder = new CurvedPSD.BendHolder();
		holder.read(tag);
		assertNull(holder.getCurve());
	}

	@Test
	void goodCurveKept() {
		final CompoundTag tag = new CompoundTag();
		tag.putInt("bend_angle", 0);
		tag.putInt("bend_cut", 0);
		tag.putLong("curve_from", 1);
		tag.putLong("curve_to", 2);
		tag.putDouble("curve_offset", 0.5);
		tag.putDouble("curve_start", 1);
		tag.putDouble("curve_end", 2);
		final CurvedPSD.BendHolder holder = new CurvedPSD.BendHolder();
		holder.read(tag);
		assertNotNull(holder.getCurve());
	}
}
