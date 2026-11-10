package mtr.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/** A speaker's saved settings and the brush screen's edit payload. */
public final class SpeakerSettings {

	public static final int MAX_RADIUS = 128;
	public static final int ALL_DIRECTIONS = 0x3F;
	public static final ResourceLocation DEFAULT_APPEARANCE = new ResourceLocation("minecraft", "jukebox");
	private static final Direction[] FACES = Direction.values();
	public final String sound;
	public final Mode mode;
	public final int radius;
	public final BlockPos triggerCenter;
	public final int triggerRadius;
	public final ResourceLocation appearance;
	public final int audienceMask;

	public SpeakerSettings(String sound, Mode mode, int radius, BlockPos triggerCenter, int triggerRadius, ResourceLocation appearance) {
		this(sound, mode, radius, triggerCenter, triggerRadius, appearance, ALL_DIRECTIONS);
	}

	public SpeakerSettings(String sound, Mode mode, int radius, BlockPos triggerCenter, int triggerRadius,
			ResourceLocation appearance, int audienceMask) {
		this.sound = AnnouncementSound.clean(sound);
		if ((!this.sound.isEmpty() && AnnouncementSound.splitUploaded(this.sound) == null)
				|| !validRadius(radius) || !validRadius(triggerRadius) || !validAudienceMask(audienceMask)) {
			throw new IllegalArgumentException("Invalid speaker settings");
		}
		this.mode = mode;
		this.radius = radius;
		this.triggerCenter = triggerCenter.immutable();
		this.triggerRadius = triggerRadius;
		this.appearance = appearance == null ? DEFAULT_APPEARANCE : appearance;
		this.audienceMask = audienceMask;
	}

	public static SpeakerSettings defaults(BlockPos position) {
		return new SpeakerSettings("", Mode.REDSTONE, 24, position, 4, DEFAULT_APPEARANCE);
	}

	public void write(CompoundTag tag) {
		tag.putString("sound", sound);
		tag.putString("mode", mode.name());
		tag.putInt("radius", radius);
		tag.putLong("trigger_center", triggerCenter.asLong());
		tag.putInt("trigger_radius", triggerRadius);
		tag.putString("appearance", appearance.toString());
		tag.putInt("audience_mask", audienceMask);
	}

	public static SpeakerSettings read(CompoundTag tag, BlockPos position) {
		try {
			return new SpeakerSettings(tag.getString("sound"), Mode.valueOf(tag.getString("mode")), tag.getInt("radius"),
					BlockPos.of(tag.getLong("trigger_center")), tag.getInt("trigger_radius"),
					tag.contains("appearance") ? ResourceLocation.tryParse(tag.getString("appearance")) : DEFAULT_APPEARANCE,
					readAudienceMask(tag));
		} catch (IllegalArgumentException e) {
			return defaults(position);
		}
	}

	private static int readAudienceMask(CompoundTag tag) {
		if (tag.contains("audience_mask", Tag.TAG_INT)) {
			final int mask = tag.getInt("audience_mask");
			return validAudienceMask(mask) ? mask : ALL_DIRECTIONS;
		}
		final Direction previous = Direction.byName(tag.getString("audience_direction").toLowerCase(Locale.ROOT));
		return previous == null ? ALL_DIRECTIONS : directionBit(previous);
	}

	public void write(FriendlyByteBuf packet) {
		packet.writeUtf(sound, AnnouncementSound.MAX_LENGTH);
		packet.writeEnum(mode);
		packet.writeVarInt(radius);
		packet.writeBlockPos(triggerCenter);
		packet.writeVarInt(triggerRadius);
		packet.writeResourceLocation(appearance);
		packet.writeByte(audienceMask);
	}

	public static SpeakerSettings read(FriendlyByteBuf packet) {
		return new SpeakerSettings(packet.readUtf(AnnouncementSound.MAX_LENGTH), packet.readEnum(Mode.class),
				packet.readVarInt(), packet.readBlockPos(), packet.readVarInt(), packet.readResourceLocation(),
				packet.readUnsignedByte());
	}

	public static boolean validRadius(int radius) {
		return radius >= 1 && radius <= MAX_RADIUS;
	}

	public static boolean inRange(BlockPos center, int radius, double x, double y, double z) {
		return audibleFrom(center, radius, ALL_DIRECTIONS, x, y, z);
	}

	public static int directionBit(Direction direction) {
		return 1 << direction.get3DDataValue();
	}

	public static boolean validAudienceMask(int mask) {
		return (mask & ~ALL_DIRECTIONS) == 0;
	}

	public static boolean audibleFrom(BlockPos center, int radius, int audienceMask, double x, double y, double z) {
		final double dx = x - center.getX() - 0.5;
		final double dy = y - center.getY() - 0.5;
		final double dz = z - center.getZ() - 0.5;
		if (audienceMask == 0 || !(dx * dx + dy * dy + dz * dz <= radius * radius)) return false;
		if (audienceMask == ALL_DIRECTIONS) return true;
		final double dominantOffset = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
		for (final Direction face : FACES) {
			if ((audienceMask & directionBit(face)) == 0) continue;
			final double forward = dx * face.getStepX() + dy * face.getStepY() + dz * face.getStepZ();
			if (forward >= dominantOffset) return true;
		}
		return false;
	}

	public static float volumeAtDistance(double distanceSquared, int radius) {
		return (float) Math.max(0, 1 - Math.sqrt(distanceSquared) / radius);
	}

	public enum Mode {
		LOOP, REDSTONE, REGION
	}

	/** The first observation after loading or editing establishes a baseline. */
	public static final class Trigger {
		private boolean initialized;
		private boolean active;

		public boolean sample(boolean signal) {
			final boolean rising = initialized && !active && signal;
			initialized = true;
			active = signal;
			return rising;
		}

		public void reset() {
			initialized = false;
		}
	}
}
