package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import transitsim.motion.PlatformDwell;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A real train's next passenger stop, retained through its approach and dwell; or a platform it runs through without
 * stopping on the way there ({@link #isPassing}, 2026-11-12), retained until it has gone through.
 */
public final class StationCall {

	public enum Trigger {
		LEGACY, BEFORE_ARRIVAL, AFTER_ARRIVAL, BEFORE_DEPARTURE;

		public static Trigger read(String value) {
			for (final Trigger trigger : values()) {
				if (trigger.name().equals(value)) {
					return trigger;
				}
			}
			return LEGACY;
		}

		public boolean isDue(long now, int seconds, long approachMillis, PlatformDwell dwell) {
			final long interval = seconds * 1000L;
			switch (this) {
				case BEFORE_ARRIVAL:
					return dwell == null && approachMillis > now && approachMillis - now <= interval;
				case AFTER_ARRIVAL:
					return dwell != null && now - dwell.arrivedMillis >= interval;
				case BEFORE_DEPARTURE:
					return dwell != null && dwell.departureMillis > now && dwell.departureMillis - now <= interval;
				default:
					return false;
			}
		}
	}

	public final long platformId;
	private final TrainServer train;
	private final Set<BlockPos> triggered = new HashSet<>();
	long routeId;
	long approachMillis;
	PlatformDwell dwell;
	private final boolean passing;

	StationCall(long platformId, TrainServer train) {
		this(platformId, train, false);
	}

	StationCall(long platformId, TrainServer train, boolean passing) {
		this.platformId = platformId;
		this.train = train;
		this.passing = passing;
	}

	/**
	 * @return whether the train runs through the platform without stopping: only "before arrival" can be due, when it
	 * reaches the platform
	 */
	public boolean isPassing() {
		return passing;
	}

	public long getRouteId() {
		return routeId;
	}

	public boolean trigger(BlockPos pos, Trigger trigger, int seconds, long now) {
		return trigger.isDue(now, seconds, approachMillis, dwell) && triggered.add(pos.immutable());
	}

	public void forEachRider(Consumer<Player> consumer) {
		train.forEachRider(consumer);
	}

	public boolean isRider(Player player) {
		return train.isPlayerRiding(player);
	}
}
