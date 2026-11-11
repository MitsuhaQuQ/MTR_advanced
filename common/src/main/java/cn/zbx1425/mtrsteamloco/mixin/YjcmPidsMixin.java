package cn.zbx1425.mtrsteamloco.mixin;

import mtr.data.ScheduleEntry;
import mtr.mappings.Text;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import transitsim.plan.OperatingDay;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * YJCM's passenger displays (Joban Client Mod 1.2.4, RV and LCD) leave the time of a train that has arrived blank. A
 * train standing at the platform with a known or planned departure ({@link ScheduleEntry#departureMillis}) shows when
 * it leaves there instead, as this mod's own displays do (0.67.2). Their render methods keep their local variable names,
 * which these hooks go by. Without YJCM, or with a version whose methods differ, nothing is changed.
 */
@Pseudo
@Mixin(targets = {
		"fabric.com.jsblock.render.RenderRVPIDS",
		"fabric.com.jsblock.render.RenderLCDPIDS",
		"forge.com.jsblock.render.RenderRVPIDS",
		"forge.com.jsblock.render.RenderLCDPIDS"
}, remap = false)
public class YjcmPidsMixin {

	@Unique
	private static final DateTimeFormatter mtr$DEPARTURE_TIME = DateTimeFormatter.ofPattern("HH:mm");
	@Unique
	private ScheduleEntry mtr$currentSchedule;

	@ModifyVariable(method = "render", at = @At("STORE"), name = "currentSchedule", require = 0, remap = false)
	private ScheduleEntry mtr$keepSchedule(ScheduleEntry entry) {
		mtr$currentSchedule = entry;
		return entry;
	}

	@ModifyVariable(method = "render", at = @At("STORE"), name = "arrivalText", require = 0, remap = false)
	private Component mtr$departureText(Component arrivalText) {
		final ScheduleEntry entry = mtr$currentSchedule;
		if (arrivalText != null || entry == null || entry.departureMillis == 0 || entry.arrivalMillis > System.currentTimeMillis()) {
			return arrivalText;
		}
		return Text.translatable("gui.mtr.departure_time", mtr$DEPARTURE_TIME.format(Instant.ofEpochMilli(Math.abs(entry.departureMillis)).atZone(OperatingDay.getTimeZone())));
	}
}
