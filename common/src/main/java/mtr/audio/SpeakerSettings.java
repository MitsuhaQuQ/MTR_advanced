package mtr.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A speaker's saved settings and the brush screen's edit payload. Since 0.70.0 a speaker plays a list of clips one after
 * another, each with a gap after it ({@link Clip}); {@link #sound} is the first clip's, as before. Gaps and the interval
 * are kept in tenths of a second; {@link #intervalSeconds} is the interval rounded down, as before.
 */
public final class SpeakerSettings {

	public static final int MAX_RADIUS = 128;
	public static final int MAX_CLIPS = 16;
	/** The longest gap or interval, in tenths of a second (an hour) */
	public static final int MAX_TENTHS = 36000;
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
	public final boolean finishBeforeRepeat;
	public final int intervalSeconds;
	/**
	 * In a loop, the silence after the last clip before the first plays again; otherwise how long after a play (or after
	 * it ends, with {@link #finishBeforeRepeat}) the speaker may be set off again. In tenths of a second.
	 */
	public final int intervalTenths;
	/** The clips played one after another, none when no sound is chosen; the first is {@link #sound} */
	public final List<Clip> clips;

	public SpeakerSettings(String sound, Mode mode, int radius, BlockPos triggerCenter, int triggerRadius, ResourceLocation appearance) {
		this(sound, mode, radius, triggerCenter, triggerRadius, appearance, ALL_DIRECTIONS);
	}

	public SpeakerSettings(String sound, Mode mode, int radius, BlockPos triggerCenter, int triggerRadius,
			ResourceLocation appearance, int audienceMask) {
		this(sound, mode, radius, triggerCenter, triggerRadius, appearance, audienceMask, false, 0);
	}

	public SpeakerSettings(String sound, Mode mode, int radius, BlockPos triggerCenter, int triggerRadius,
			ResourceLocation appearance, int audienceMask, boolean finishBeforeRepeat, int intervalSeconds) {
		this(single(sound), mode, radius, triggerCenter, triggerRadius, appearance, audienceMask, finishBeforeRepeat, checkedSeconds(intervalSeconds) * 10);
	}

	/**
	 * @param clips          the clips in order (empty for none); one with no sound is left out
	 * @param intervalTenths in tenths of a second, 0 to {@link #MAX_TENTHS}
	 */
	public SpeakerSettings(List<Clip> clips, Mode mode, int radius, BlockPos triggerCenter, int triggerRadius,
			ResourceLocation appearance, int audienceMask, boolean finishBeforeRepeat, int intervalTenths) {
		final List<Clip> kept = new ArrayList<>();
		for (final Clip clip : clips) {
			if (!clip.sound.isEmpty()) kept.add(clip);
		}
		if (kept.size() > MAX_CLIPS || !validRadius(radius) || !validRadius(triggerRadius) || !validAudienceMask(audienceMask) || !validTenths(intervalTenths)) {
			throw new IllegalArgumentException("Invalid speaker settings");
		}
		this.clips = Collections.unmodifiableList(kept);
		this.sound = kept.isEmpty() ? "" : kept.get(0).sound;
		this.mode = mode;
		this.radius = radius;
		this.triggerCenter = triggerCenter.immutable();
		this.triggerRadius = triggerRadius;
		this.appearance = appearance == null ? DEFAULT_APPEARANCE : appearance;
		this.audienceMask = audienceMask;
		this.finishBeforeRepeat = finishBeforeRepeat;
		this.intervalTenths = intervalTenths;
		this.intervalSeconds = intervalTenths / 10;
	}

	public static SpeakerSettings defaults(BlockPos position) {
		return new SpeakerSettings("", Mode.REDSTONE, 24, position, 4, DEFAULT_APPEARANCE);
	}

	/**
	 * Whether a loop plays as the game's own looping sound, with no gap at all: one clip and no interval
	 */
	public boolean seamlessLoop() {
		return clips.size() == 1 && intervalTenths == 0;
	}

	public void write(CompoundTag tag) {
		tag.putString("sound", sound);
		tag.putString("mode", mode.name());
		tag.putInt("radius", radius);
		tag.putLong("trigger_center", triggerCenter.asLong());
		tag.putInt("trigger_radius", triggerRadius);
		tag.putString("appearance", appearance.toString());
		tag.putInt("audience_mask", audienceMask);
		tag.putBoolean("finish_before_repeat", finishBeforeRepeat);
		tag.putInt("interval_seconds", intervalSeconds);
		tag.putInt("interval_tenths", intervalTenths);
		final ListTag list = new ListTag();
		for (final Clip clip : clips) {
			final CompoundTag each = new CompoundTag();
			each.putString("sound", clip.sound);
			each.putInt("gap_tenths", clip.gapTenths);
			list.add(each);
		}
		tag.put("clips", list);
	}

	public static SpeakerSettings read(CompoundTag tag, BlockPos position) {
		try {
			// Saves before 0.70.0: the one sound, the interval in whole seconds
			final List<Clip> clips = new ArrayList<>();
			if (tag.contains("clips", Tag.TAG_LIST)) {
				final ListTag list = tag.getList("clips", Tag.TAG_COMPOUND);
				for (int i = 0; i < Math.min(list.size(), MAX_CLIPS); i++) {
					clips.add(new Clip(list.getCompound(i).getString("sound"), list.getCompound(i).getInt("gap_tenths")));
				}
			} else {
				clips.addAll(single(tag.getString("sound")));
			}
			final int intervalTenths = tag.contains("interval_tenths", Tag.TAG_INT) ? tag.getInt("interval_tenths") : checkedSeconds(tag.getInt("interval_seconds")) * 10;
			return new SpeakerSettings(clips, Mode.valueOf(tag.getString("mode")), tag.getInt("radius"),
					BlockPos.of(tag.getLong("trigger_center")), tag.getInt("trigger_radius"),
					tag.contains("appearance") ? ResourceLocation.tryParse(tag.getString("appearance")) : DEFAULT_APPEARANCE,
					readAudienceMask(tag), tag.getBoolean("finish_before_repeat"), intervalTenths);
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
		writeClips(packet, clips);
		packet.writeEnum(mode);
		packet.writeVarInt(radius);
		packet.writeBlockPos(triggerCenter);
		packet.writeVarInt(triggerRadius);
		packet.writeResourceLocation(appearance);
		packet.writeByte(audienceMask);
		packet.writeBoolean(finishBeforeRepeat);
		packet.writeVarInt(intervalTenths);
	}

	public static SpeakerSettings read(FriendlyByteBuf packet) {
		return new SpeakerSettings(readClips(packet), packet.readEnum(Mode.class),
				packet.readVarInt(), packet.readBlockPos(), packet.readVarInt(), packet.readResourceLocation(),
				packet.readUnsignedByte(), packet.readBoolean(), packet.readVarInt());
	}

	public static void writeClips(FriendlyByteBuf packet, List<Clip> clips) {
		packet.writeVarInt(clips.size());
		for (final Clip clip : clips) {
			packet.writeUtf(clip.sound, AnnouncementSound.MAX_LENGTH);
			packet.writeVarInt(clip.gapTenths);
		}
	}

	/**
	 * @throws IllegalArgumentException for too many clips or one that isn't valid
	 */
	public static List<Clip> readClips(FriendlyByteBuf packet) {
		final int count = packet.readVarInt();
		if (count < 0 || count > MAX_CLIPS) throw new IllegalArgumentException("Too many clips");
		final List<Clip> clips = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			clips.add(new Clip(packet.readUtf(AnnouncementSound.MAX_LENGTH), packet.readVarInt()));
		}
		return clips;
	}

	/** The gap after each clip in ticks, as the clips play (the last clip's is not used) */
	public long[] gapTicks() {
		final long[] gaps = new long[clips.size()];
		for (int i = 0; i < gaps.length; i++) gaps[i] = tenthsToTicks(clips.get(i).gapTenths);
		return gaps;
	}

	public static long tenthsToTicks(int tenths) {
		return tenths * 2L;
	}

	public static boolean validTenths(int tenths) {
		return tenths >= 0 && tenths <= MAX_TENTHS;
	}

	private static int checkedSeconds(int seconds) {
		if (seconds < 0 || seconds > MAX_TENTHS / 10) throw new IllegalArgumentException("Invalid interval");
		return seconds;
	}

	private static List<Clip> single(String sound) {
		final List<Clip> clips = new ArrayList<>();
		clips.add(new Clip(sound, 0));
		return clips;
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
		for (final Direction face : FACES) {
			if ((audienceMask & directionBit(face)) == 0) continue;
			final double forward = dx * face.getStepX() + dy * face.getStepY() + dz * face.getStepZ();
			if (forward > 0 || dx == 0 && dy == 0 && dz == 0) return true;
		}
		return false;
	}

	public static float volumeAtDistance(double distanceSquared, int radius) {
		return (float) Math.max(0, 1 - Math.sqrt(distanceSquared) / radius);
	}

	/** Audibility changes the gain, not the lifetime or playback position of the sound. */
	public static float volumeAt(BlockPos center, int radius, int audienceMask, double x, double y, double z) {
		if (!audibleFrom(center, radius, audienceMask, x, y, z)) return 0;
		final double dx = x - center.getX() - 0.5;
		final double dy = y - center.getY() - 0.5;
		final double dz = z - center.getZ() - 0.5;
		return volumeAtDistance(dx * dx + dy * dy + dz * dz, radius);
	}

	/**
	 * One clip of a speaker's list: an uploaded sound, and the silence after it before the next, in tenths of a second.
	 */
	public static final class Clip {
		public final String sound;
		public final int gapTenths;

		public Clip(String sound, int gapTenths) {
			this.sound = AnnouncementSound.clean(sound);
			if ((!this.sound.isEmpty() && AnnouncementSound.splitUploaded(this.sound) == null) || !validTenths(gapTenths)) {
				throw new IllegalArgumentException("Invalid speaker clip");
			}
			this.gapTenths = gapTenths;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Clip && ((Clip) other).sound.equals(sound) && ((Clip) other).gapTenths == gapTenths;
		}

		@Override
		public int hashCode() {
			return sound.hashCode() * 31 + gapTenths;
		}
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
