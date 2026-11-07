package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import mtr.data.RailwayData;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * ANTE's rails can have shapes of their own, so this only changes how rails near a player are found and how far that
 * reaches. The rest of the tick is MTR's own, so nothing added to it is lost when ANTE is in.
 * <p>
 * Only the fields used here are shadowed, and none of them with a value: Mixin merges a field's initialiser into the
 * target's constructors, so a shadowed field given one here, as the list of trains' positions used to be, replaced
 * MTR's own with an empty one. Trains then took no rails at all, and anything reading the positions crashed (the same
 * fix as ANTE-advanced 2f3921c8).
 */
@Mixin(RailwayData.class)
public class RailwayDataMixin {

	@Shadow(remap = false)
	private Level world;

	@Shadow(remap = false)
	@Final
	@Mutable
	private static int RAIL_UPDATE_DISTANCE;

	public void simulateTrains() {
		if (!mtr.SecurityConfig.anteRunHooks) {
			((RailwayData) (Object) this).simulateTrains(RailwayData::isRailNearInBox);
			return;
		}
		RAIL_UPDATE_DISTANCE = world.getServer().getPlayerList().getViewDistance() * 16;
		final int railUpdateDistance = RAIL_UPDATE_DISTANCE;
		((RailwayData) (Object) this).simulateTrains((rail, startPos, endPos, playerPos) -> ((RailExtraSupplier) (Object) rail).isBetween(playerPos.x, playerPos.y, playerPos.z, railUpdateDistance));
	}
}
