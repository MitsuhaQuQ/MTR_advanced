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
	void whyThereIsNoWayIsSavedAndSentAndOlderListsStillRead() {
		final Map<Long, Long> out = new HashMap<>();
		out.put(11L, 100000L);
		final Map<Long, long[]> outReasons = new HashMap<>();
		outReasons.put(12L, new long[]{DepotLegs.REASON_AGAINST, 31L, 32L});
		// A platform with a way has no reason, and an unknown kind is dropped
		outReasons.put(11L, new long[]{DepotLegs.REASON_NO_TRACK});
		final Map<Long, long[]> backReasons = new HashMap<>();
		backReasons.put(13L, new long[]{DepotLegs.REASON_ENTRANCE_UNSET});
		backReasons.put(14L, new long[]{99});
		final DepotLegs legs = new DepotLegs(10, out, Collections.emptyMap()).withUnreachable(new java.util.TreeSet<>(java.util.Arrays.asList(12L)), new java.util.TreeSet<>(java.util.Arrays.asList(13L, 14L))).withReasons(outReasons, backReasons);
		assertEquals(1, legs.outReasons.size());
		assertArrayEquals(new long[]{DepotLegs.REASON_AGAINST, 31L, 32L}, legs.outReasons.get(12L));
		assertEquals(1, legs.backReasons.size());

		final DepotLegs saved = DepotLegs.fromList(legs.toList());
		assertArrayEquals(legs.outReasons.get(12L), saved.outReasons.get(12L));
		assertArrayEquals(legs.backReasons.get(13L), saved.backReasons.get(13L));

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		legs.writePacket(packet);
		final DepotLegs sent = DepotLegs.readPacket(packet);
		assertArrayEquals(legs.outReasons.get(12L), sent.outReasons.get(12L));
		assertArrayEquals(legs.backReasons.get(13L), sent.backReasons.get(13L));
		assertEquals(0, packet.readableBytes());

		// A list saved before reasons were kept: the same ways, no reasons
		final java.util.List<Long> plain = legs.withReasons(Collections.emptyMap(), Collections.emptyMap()).toList();
		final java.util.List<Long> older = plain.subList(0, plain.size() - 2);
		final DepotLegs old = DepotLegs.fromList(older);
		assertEquals(legs.unreachableOut, old.unreachableOut);
		assertEquals(legs.unreachableBack, old.unreachableBack);
		assertTrue(old.outReasons.isEmpty() && old.backReasons.isEmpty());
	}
}
