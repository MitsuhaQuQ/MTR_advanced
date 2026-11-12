// T28 修改后的结果（中）: after a change to the times, services, passing or margin, the timetable screen asks for a new
// check; "检查冲突" then updates it; two services moved apart show no conflict.
// Covered here: adding a service, ticking "pass" and removing a service each ask for a new check; "检查冲突" replaces
// that request with a result, on both routes.
// Not covered yet: finding a conflict and losing it when moved apart. The fixture's single track with a depot at each end
// gives the routes no way back to their depots, so the server can't work out their times (plan times -1, "The path of a
// duty can't be found" in its log); it then reports "没有冲突", which T26 says it should not (to look at with the
// fixtures of real track layouts, next batch). Not covered either: the margin field, the red rows and station names.
import {test, expect} from "../lib/test";
import {click, state, waitFor, waitText} from "../lib/game";
import {addService, closeAll, deleteService, enableTimetable, openTimetable} from "../lib/screens";

const OUTDATED = /时刻已修改.*检查冲突/;

/** Presses "检查冲突" and waits for its result in the status line (not the request to check, not "checking") */
async function check(): Promise<string> {
	await click("TimetableScreen.buttonCheckConflicts");
	const result = await waitFor("the check to finish", async () =>
		(await state()).texts.find(t => t.y > 300 && /冲突/.test(t.text) && !OUTDATED.test(t.text) && !/正在检查/.test(t.text)), 20_000, 250);
	return result.text;
}

test("T28 修改时刻后提示重新检查 @T28", async () => {
	for (const [route, time] of [["测试线 Test Line", "08:00:00"], ["测试线回程 Test Line Back", "08:30:00"]]) {
		await openTimetable(route);
		await enableTimetable();
		await addService(time);
		await waitText(OUTDATED);
		expect(await check(), `${route}: the check gives a result`).not.toMatch(OUTDATED);

		// "Pass" at the middle station is a change
		await click("TimetableScreen.checkboxesPass[1]");
		await waitText(OUTDATED);
		await click("TimetableScreen.checkboxesPass[1]");
		await check();

		// So is taking a service away
		await addService("12:00:00");
		await check();
		await deleteService("12:00:00");
		await waitText(OUTDATED);
		await check();
		await closeAll();
	}
});
