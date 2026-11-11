package cn.zbx1425.mtrsteamloco.mixin;

import mtr.data.TicketSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Metropolis's turnstiles (0.4.0-beta for MTR 3.2.2, Fabric) charge their own cards and single trip tickets, whose money
 * is in the item, by the stations' zones. Here they charge the fare by distance instead (design document 6.13, 0.68.0),
 * still from the card or ticket: entering also records the gate and the minute in the item, and leaving works the fare
 * out from them ({@link TicketSystem#getTripFare}). A card or ticket entered without them (before this, or at a gate in
 * no station) keeps the zone fare. Metropolis's ticket vendors and fare adjustment machines are unchanged, so a single
 * trip ticket bought for a destination may hold more or less than the fare by distance.
 * <p>
 * Its {@code use} method is a Minecraft one, so it is named as Fabric's intermediary names it; the local {@code cost}
 * goes by its name. Without Metropolis, on Forge, or with a version whose method differs, nothing is changed.
 */
@Pseudo
@Mixin(targets = "team.dovecotmc.metropolis.block.BlockTurnstile", remap = false)
public class MetropolisTurnstileMixin {

	@Unique
	private static final String mtr$ENTERED_GATE = "mtm_entered_gate";
	@Unique
	private static final String mtr$ENTERED_MINUTE = "mtm_entered_minute";
	@Unique
	private static final String mtr$ENTERED_STATION = "entered_station";

	/**
	 * Entering: with the station's name, the gate and the minute.
	 */
	@Redirect(method = "method_9534", at = @At(value = "INVOKE", target = "Lnet/minecraft/class_2487;method_10582(Ljava/lang/String;Ljava/lang/String;)V", remap = false), require = 0, remap = false)
	private void mtr$recordEntry(CompoundTag tag, String key, String value, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		tag.putString(key, value);
		if (mtr$ENTERED_STATION.equals(key)) {
			tag.putLong(mtr$ENTERED_GATE, pos.asLong());
			tag.putLong(mtr$ENTERED_MINUTE, TicketSystem.getMinute());
		}
	}

	/**
	 * Leaving with a card: the record goes with the station's.
	 */
	@Redirect(method = "method_9534", at = @At(value = "INVOKE", target = "Lnet/minecraft/class_2487;method_10551(Ljava/lang/String;)V", remap = false), require = 0, remap = false)
	private void mtr$clearEntry(CompoundTag tag, String key, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		tag.remove(key);
		if (mtr$ENTERED_STATION.equals(key)) {
			tag.remove(mtr$ENTERED_GATE);
			tag.remove(mtr$ENTERED_MINUTE);
		}
	}

	/**
	 * Leaving: the fare by distance from the gate entered at, in place of the zones'.
	 */
	@ModifyVariable(method = "method_9534", at = @At("STORE"), name = "cost", require = 0, remap = false)
	private int mtr$fareByDistance(int cost, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		final CompoundTag tag = player.getItemInHand(InteractionHand.MAIN_HAND).getTag();
		if (tag == null || !tag.contains(mtr$ENTERED_GATE)) {
			return cost;
		}
		final int fare = TicketSystem.getTripFare(world, BlockPos.of(tag.getLong(mtr$ENTERED_GATE)), pos, TicketSystem.getMinute() - tag.getLong(mtr$ENTERED_MINUTE));
		return fare < 0 ? cost : fare;
	}
}
