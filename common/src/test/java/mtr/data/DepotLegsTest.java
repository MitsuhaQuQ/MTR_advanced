package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a siding's trains get out and back reaches the clients as it was worked out.
 */
class DepotLegsTest {

	@Test
	void legsAreSentAsTheyAre() {
		final Map<Long, Long> out = new HashMap<>();
		out.put(11L, 45000L);
		out.put(12L, 90000L);
		final DepotLegs legs = new DepotLegs(11, out, Collections.singletonMap(12L, 60000L));
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		legs.writePacket(packet);
		final DepotLegs read = DepotLegs.readPacket(packet);
		assertEquals(11, read.entrancePlatform);
		assertEquals(legs.outMillis, read.outMillis);
		assertEquals(legs.backMillis, read.backMillis);
	}

	@Test
	void noPathsMeansNoLegs() {
		final DepotLegs legs = DepotLegs.of(Collections.emptyList());
		assertEquals(0, legs.entrancePlatform);
		assertTrue(legs.outMillis.isEmpty());
	}

	private static DepotLegs legs(long outPlatform, long outMillis, long backPlatform, long backMillis) {
		return new DepotLegs(outPlatform, Collections.singletonMap(outPlatform, outMillis), Collections.singletonMap(backPlatform, backMillis));
	}

	@Test
	void theEstimateStaysWhereMeasuringFindsItRoughlyRight() {
		// Estimated a minute both ways, measured 10 s more: the plan stays as it is
		final DepotLegs kept = DepotLegs.record(DepotLegs.NONE, legs(11, 70000, 12, 50000), platform -> 60000);
		assertTrue(kept.outMillis.isEmpty() && kept.backMillis.isEmpty(), "nothing kept: " + kept.outMillis + " " + kept.backMillis);
	}

	@Test
	void aMeasuredTimeFarOffTheEstimateIsKept() {
		final DepotLegs kept = DepotLegs.record(DepotLegs.NONE, legs(11, 100000, 12, 20000), platform -> 60000);
		assertEquals(100000L, kept.outMillis.get(11L));
		assertEquals(20000L, kept.backMillis.get(12L));
		// Measured again much the same: unchanged, so the duties aren't planned again
		assertSame(kept, DepotLegs.record(kept, legs(11, 104000, 12, 25000), platform -> 60000));
		// Off what was kept by more than the tolerance: kept anew
		assertEquals(130000L, DepotLegs.record(kept, legs(11, 130000, 12, 25000), platform -> 60000).outMillis.get(11L));
	}

	@Test
	void keptLegsReadBackAsSaved() {
		final DepotLegs legs = legs(11, 100000, 12, 20000);
		final DepotLegs read = DepotLegs.fromList(legs.toList());
		assertEquals(legs.entrancePlatform, read.entrancePlatform);
		assertEquals(legs.outMillis, read.outMillis);
		assertEquals(legs.backMillis, read.backMillis);
		assertSame(DepotLegs.NONE, DepotLegs.fromList(Collections.singletonList(3L)), "a broken list reads as none");
	}

	@Test
	void waysFoundMissingAreSavedAndSentAndOlderSavesStillRead() {
		final DepotLegs legs = legs(11, 100000, 12, 20000).withUnreachable(Collections.singleton(13L), Collections.singleton(14L));
		final DepotLegs read = DepotLegs.fromList(legs.toList());
		assertEquals(Collections.singleton(13L), read.unreachableOut);
		assertEquals(Collections.singleton(14L), read.unreachableBack);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		legs.writePacket(packet);
		final DepotLegs sent = DepotLegs.readPacket(packet);
		assertEquals(legs.unreachableOut, sent.unreachableOut);
		assertEquals(legs.unreachableBack, sent.unreachableBack);

		// Saved before: the list ends after the ways back
		final java.util.List<Long> older = legs(11, 100000, 12, 20000).toList();
		final DepotLegs old = DepotLegs.fromList(older.subList(0, older.size() - 2));
		assertTrue(old.unreachableOut.isEmpty() && old.unreachableBack.isEmpty());
		assertEquals(Long.valueOf(100000), old.outMillis.get(11L));
	}

	@Test
	void aWayFoundMissingIsKeptUntilOneIsMeasured() {
		final DepotLegs missing = DepotLegs.record(DepotLegs.NONE, DepotLegs.NONE.withUnreachable(Collections.singleton(11L), Collections.emptySet()), platform -> 60000);
		assertEquals(Collections.singleton(11L), missing.unreachableOut);
		// Found again, as far as estimated: no longer missing, and nothing else to keep
		final DepotLegs found = DepotLegs.record(missing, legs(11, 65000, 12, 60000), platform -> 60000);
		assertTrue(found.unreachableOut.isEmpty());
		// The same found missing again: unchanged
		assertSame(missing, DepotLegs.record(missing, DepotLegs.NONE.withUnreachable(Collections.singleton(11L), Collections.emptySet()), platform -> 60000));
	}
}
