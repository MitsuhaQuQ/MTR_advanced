package mtr.audio;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeakerSettingsTest {
	@Test
	void savesAndSynchronizesTheTriggerAreaAndAppearance() {
		final SpeakerSettings original = new SpeakerSettings("steve:站台.ogg", SpeakerSettings.Mode.REGION, 128,
				new BlockPos(-200, 71, 400), 9, new ResourceLocation("minecraft:polished_andesite"));
		final CompoundTag tag = new CompoundTag();
		original.write(tag);
		assertSettings(original, SpeakerSettings.read(tag, BlockPos.ZERO));

		final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			original.write(buffer);
			assertSettings(original, SpeakerSettings.read(buffer));
			assertEquals(0, buffer.readableBytes());
		} finally {
			buffer.release();
		}
	}

	@Test
	void onlyRisingEdgesTriggerAndLoadingSeedsTheCurrentState() {
		final SpeakerSettings.Trigger trigger = new SpeakerSettings.Trigger();
		assertFalse(trigger.sample(true));
		assertFalse(trigger.sample(true));
		assertFalse(trigger.sample(false));
		assertTrue(trigger.sample(true));
		assertFalse(trigger.sample(true));
		trigger.reset();
		assertFalse(trigger.sample(false));
		assertTrue(trigger.sample(true));
	}

	@Test
	void rangeIsSphericalAndIncludesTheBoundary() {
		final BlockPos center = new BlockPos(5, 20, -10);
		assertTrue(SpeakerSettings.inRange(center, 4, 5.5, 24.5, -9.5));
		assertFalse(SpeakerSettings.inRange(center, 4, 9.5, 24.5, -9.5));
		assertFalse(SpeakerSettings.inRange(center, 4, 5.5, 24.51, -9.5));
	}

	@Test
	void rejectsOutOfBoundsRadiiAndNetworkAudio() {
		assertThrows(IllegalArgumentException.class, () -> new SpeakerSettings("", SpeakerSettings.Mode.LOOP,
				129, BlockPos.ZERO, 4, SpeakerSettings.DEFAULT_APPEARANCE));
		assertThrows(IllegalArgumentException.class, () -> new SpeakerSettings("https://example.org/track.ogg",
				SpeakerSettings.Mode.REDSTONE, 24, BlockPos.ZERO, 4, SpeakerSettings.DEFAULT_APPEARANCE));
	}

	private static void assertSettings(SpeakerSettings expected, SpeakerSettings actual) {
		assertEquals(expected.sound, actual.sound);
		assertEquals(expected.mode, actual.mode);
		assertEquals(expected.radius, actual.radius);
		assertEquals(expected.triggerCenter, actual.triggerCenter);
		assertEquals(expected.triggerRadius, actual.triggerRadius);
		assertEquals(expected.appearance, actual.appearance);
	}
}
