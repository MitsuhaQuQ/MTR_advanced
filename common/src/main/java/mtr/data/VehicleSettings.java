package mtr.data;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

import java.util.Objects;

/**
 * How the trains of a pool of a depot's sidings, the same vehicle, run (R1): set once for the pool, so a train is the same
 * wherever it goes. Not set, they are those of the pool's first siding.
 */
public final class VehicleSettings {

	public final float acceleration;
	/**
	 * How fast they brake, or 0 for as fast as they speed up.
	 */
	public final float deceleration;
	public final boolean manual;
	public final int maxManualSpeed;
	public final int manualToAutomaticTime;

	public VehicleSettings(float acceleration, float deceleration, boolean manual, int maxManualSpeed, int manualToAutomaticTime) {
		this.acceleration = Mth.clamp(RailwayData.round(acceleration, 3), Train.MIN_ACCELERATION, Train.MAX_ACCELERATION);
		this.deceleration = deceleration <= 0 || Float.isNaN(deceleration) ? 0 : Mth.clamp(RailwayData.round(deceleration, 3), Train.MIN_ACCELERATION, Train.MAX_ACCELERATION);
		this.manual = manual;
		this.maxManualSpeed = Math.max(0, maxManualSpeed);
		this.manualToAutomaticTime = Math.max(0, manualToAutomaticTime);
	}

	public float getDeceleration() {
		return deceleration <= 0 ? acceleration : deceleration;
	}

	public static VehicleSettings read(FriendlyByteBuf packet) {
		return new VehicleSettings(packet.readFloat(), packet.readFloat(), packet.readBoolean(), packet.readInt(), packet.readInt());
	}

	public void write(FriendlyByteBuf packet) {
		packet.writeFloat(acceleration);
		packet.writeFloat(deceleration);
		packet.writeBoolean(manual);
		packet.writeInt(maxManualSpeed);
		packet.writeInt(manualToAutomaticTime);
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof VehicleSettings)) {
			return false;
		}
		final VehicleSettings settings = (VehicleSettings) other;
		return acceleration == settings.acceleration && deceleration == settings.deceleration && manual == settings.manual && maxManualSpeed == settings.maxManualSpeed && manualToAutomaticTime == settings.manualToAutomaticTime;
	}

	@Override
	public int hashCode() {
		return Objects.hash(acceleration, deceleration, manual, maxManualSpeed, manualToAutomaticTime);
	}
}
