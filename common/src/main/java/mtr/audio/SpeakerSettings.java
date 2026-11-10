package mtr.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** A speaker's saved settings and the brush screen's edit payload. */
public final class SpeakerSettings {

	public static final int MAX_RADIUS = 128;
	public static final ResourceLocation DEFAULT_APPEARANCE = new ResourceLocation("minecraft", "jukebox");
	public final String sound;
	public final Mode mode;
	public final int radius;
	public final BlockPos triggerCenter;
	public final int triggerRadius;
	public final ResourceLocation appearance;

	public SpeakerSettings(String sound, Mode mode, int radius, BlockPos triggerCenter, int triggerRadius, ResourceLocation appearance) {
		this.sound = AnnouncementSound.clean(sound);
		if ((!this.sound.isEmpty() && AnnouncementSound.splitUploaded(this.sound) == null)
				|| !validRadius(radius) || !validRadius(triggerRadius)) {
			throw new IllegalArgumentException("Invalid speaker settings");
		}
		this.mode = mode;
		this.radius = radius;
		this.triggerCenter = triggerCenter.immutable();
		this.triggerRadius = triggerRadius;
		this.appearance = appearance == null ? DEFAULT_APPEARANCE : appearance;
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
	}

	public static SpeakerSettings read(CompoundTag tag, BlockPos position) {
		try {
			return new SpeakerSettings(tag.getString("sound"), Mode.valueOf(tag.getString("mode")), tag.getInt("radius"),
					BlockPos.of(tag.getLong("trigger_center")), tag.getInt("trigger_radius"),
					tag.contains("appearance") ? ResourceLocation.tryParse(tag.getString("appearance")) : DEFAULT_APPEARANCE);
		} catch (IllegalArgumentException e) {
			return defaults(position);
		}
	}

	public void write(FriendlyByteBuf packet) {
		packet.writeUtf(sound, AnnouncementSound.MAX_LENGTH);
		packet.writeEnum(mode);
		packet.writeVarInt(radius);
		packet.writeBlockPos(triggerCenter);
		packet.writeVarInt(triggerRadius);
		packet.writeResourceLocation(appearance);
	}

	public static SpeakerSettings read(FriendlyByteBuf packet) {
		return new SpeakerSettings(packet.readUtf(AnnouncementSound.MAX_LENGTH), packet.readEnum(Mode.class),
				packet.readVarInt(), packet.readBlockPos(), packet.readVarInt(), packet.readResourceLocation());
	}

	public static boolean validRadius(int radius) {
		return radius >= 1 && radius <= MAX_RADIUS;
	}

	public static boolean inRange(BlockPos center, int radius, double x, double y, double z) {
		final double dx = x - center.getX() - 0.5;
		final double dy = y - center.getY() - 0.5;
		final double dz = z - center.getZ() - 0.5;
		return dx * dx + dy * dy + dz * dz <= radius * radius;
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
