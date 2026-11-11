package cn.zbx1425.mtrsteamloco.mixin;

import mtr.data.ScheduleEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * YJCM's departure timer (Joban Client Mod 1.2.4) counts the platform's stop time down from the first train that has
 * arrived. A train standing longer, as for its timetable at its first stop, stood at the full stop time until its last
 * stop time began; it is left out of what the timer reads until then, so the timer stays blank and starts at the stop
 * time (0.67.3). Hooked by the local variable name of its render method; without YJCM nothing is changed.
 */
@Pseudo
@Mixin(targets = {
		"fabric.com.jsblock.render.RenderDepartureTimer",
		"forge.com.jsblock.render.RenderDepartureTimer"
}, remap = false)
public class YjcmDepartureTimerMixin {

	@ModifyVariable(method = "render", at = @At("STORE"), name = "scheduleList", require = 0, remap = false)
	private List<ScheduleEntry> mtr$leaveOutWaiting(List<ScheduleEntry> scheduleList) {
		if (scheduleList == null || scheduleList.stream().noneMatch(entry -> entry.waitingToCountDown)) {
			return scheduleList;
		}
		final List<ScheduleEntry> counting = new ArrayList<>();
		scheduleList.forEach(entry -> {
			if (!entry.waitingToCountDown) {
				counting.add(entry);
			}
		});
		return counting;
	}
}
