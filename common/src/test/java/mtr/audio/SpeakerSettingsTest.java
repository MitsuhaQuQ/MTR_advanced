package mtr.audio;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeakerSettingsTest {
	@Test
	void savesAndSynchronizesTheTriggerAreaAndAppearance() {
		final SpeakerSettings original = new SpeakerSettings("steve:站台.ogg", SpeakerSettings.Mode.REGION, 128,
				new BlockPos(-200, 71, 400), 9, new ResourceLocation("minecraft:polished_andesite"),
				SpeakerSettings.directionBit(Direction.UP) | SpeakerSettings.directionBit(Direction.EAST));
		final CompoundTag tag = new CompoundTag();
		original.write(tag);
		assertSettings(original, SpeakerSettings.read(tag, BlockPos.ZERO));
		tag.remove("audience_mask");
		final SpeakerSettings previousSave = SpeakerSettings.read(tag, BlockPos.ZERO);
		assertEquals(SpeakerSettings.ALL_DIRECTIONS, previousSave.audienceMask);
		assertEquals(original.sound, previousSave.sound);
		assertEquals(original.triggerCenter, previousSave.triggerCenter);
		tag.putString("audience_direction", "WEST");
		assertEquals(SpeakerSettings.directionBit(Direction.WEST), SpeakerSettings.read(tag, BlockPos.ZERO).audienceMask);
		tag.putString("audience_direction", "ALL");
		assertEquals(SpeakerSettings.ALL_DIRECTIONS, SpeakerSettings.read(tag, BlockPos.ZERO).audienceMask);
		tag.putInt("audience_mask", 0);
		assertEquals(0, SpeakerSettings.read(tag, BlockPos.ZERO).audienceMask);

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
	void eachFaceOnlyAcceptsListenersInItsSectorAndRadius() {
		final Direction[] directions = {
				Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
		};
		final double[][] listeners = {
				{0.5, 2.5, 0.5}, {0.5, -1.5, 0.5}, {0.5, 0.5, -1.5},
				{0.5, 0.5, 2.5}, {-1.5, 0.5, 0.5}, {2.5, 0.5, 0.5}
		};
		for (int i = 0; i < directions.length; i++) {
			for (int j = 0; j < listeners.length; j++) {
				final double[] pos = listeners[j];
				assertEquals(i == j, SpeakerSettings.audibleFrom(BlockPos.ZERO, 4, SpeakerSettings.directionBit(directions[i]), pos[0], pos[1], pos[2]));
			}
		}
		final int east = SpeakerSettings.directionBit(Direction.EAST);
		assertTrue(SpeakerSettings.audibleFrom(BlockPos.ZERO, 4, east, 2.5, 1.5, 0.5));
		assertFalse(SpeakerSettings.audibleFrom(BlockPos.ZERO, 4, east, 1.5, 2.5, 0.5));
		assertFalse(SpeakerSettings.audibleFrom(BlockPos.ZERO, 4, east, 5.5, 0.5, 0.5));
	}

	@Test
	void combinesSelectedSectorsAndMutesWhenNoFaceIsSelected() {
		final int mask = SpeakerSettings.directionBit(Direction.UP) | SpeakerSettings.directionBit(Direction.EAST);
		assertTrue(SpeakerSettings.audibleFrom(BlockPos.ZERO, 4, mask, 2.5, 0.5, 0.5));
		assertTrue(SpeakerSettings.audibleFrom(BlockPos.ZERO, 4, mask, 0.5, 2.5, 0.5));
		assertFalse(SpeakerSettings.audibleFrom(BlockPos.ZERO, 4, mask, -1.5, 0.5, 0.5));
		assertFalse(SpeakerSettings.audibleFrom(BlockPos.ZERO, 4, 0, 0.5, 0.5, 0.5));
		assertFalse(SpeakerSettings.validAudienceMask(0x40));
	}

	@Test
	void volumeFadesLinearlyOverTheConfiguredRadius() {
		assertEquals(1F, SpeakerSettings.volumeAtDistance(0, 8));
		assertEquals(0.75F, SpeakerSettings.volumeAtDistance(4, 8));
		assertEquals(0.5F, SpeakerSettings.volumeAtDistance(16, 8));
		assertEquals(0F, SpeakerSettings.volumeAtDistance(64, 8));
		assertEquals(0F, SpeakerSettings.volumeAtDistance(81, 8));
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
		assertEquals(expected.audienceMask, actual.audienceMask);
	}
}
