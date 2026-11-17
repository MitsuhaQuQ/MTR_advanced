package mtr.escalator;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The list of free escalators taken away (0.100.0): kept across a save, the oldest forgotten past the limit */
class FreeEscalatorRemovalsTest {

	@Test
	void savedAndReadBack() {
		final FreeEscalatorRemovals removals = new FreeEscalatorRemovals();
		removals.add(5);
		removals.add(-9);
		removals.add(0);
		final FreeEscalatorRemovals read = new FreeEscalatorRemovals();
		read.load(removals.save(new CompoundTag()));
		assertTrue(read.contains(5));
		assertTrue(read.contains(-9));
		assertFalse(read.contains(0), "0 is no escalator");
	}

	@Test
	void oldestForgotten() {
		final FreeEscalatorRemovals removals = new FreeEscalatorRemovals();
		for (long id = 1; id <= FreeEscalatorRemovals.LIMIT + 5; id++) {
			removals.add(id);
		}
		assertFalse(removals.contains(1));
		assertFalse(removals.contains(5));
		assertTrue(removals.contains(6));
		assertTrue(removals.contains(FreeEscalatorRemovals.LIMIT + 5));
	}

	@Test
	void badDataRead() {
		final CompoundTag tag = new CompoundTag();
		tag.putString("removed", "not a list");
		final FreeEscalatorRemovals removals = new FreeEscalatorRemovals();
		removals.load(tag);
		assertFalse(removals.contains(0));
	}
}
