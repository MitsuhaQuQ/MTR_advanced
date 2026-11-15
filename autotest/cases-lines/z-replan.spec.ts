// U82 驱动时改数据很快生效, U102 改时刻表后运用自动更新 (高): while trains run the component starts again from the game's
// trains only when something changed, and a timetable changed has its depot's duties planned again within seconds.
// Runs last, after the trains have been sampled and commanded.
// Covered here: no "starts again" for a minute while nothing changes; a service added to line 3's timetable through the
// timetable screen followed within 30 s by the component starting again and line 3's duties planned again. Not covered:
// the web's duties changing, a depot's frequency, a siding's settings or signals changed.
import {test, expect} from "../lib/test";
import {sleep} from "../lib/game";
import {clock, label, later, lines, serverLog} from "../lib/lines";
import {addService, closeAll, openTimetable} from "../lib/screens";

test("U82 U102 改时刻表后很快重排，不改时不重来 @lines @U82 @U102", async ({}, testInfo) => {
	// On top of the time waited for the sampling (lib/test.ts set the timeout to include it)
	test.setTimeout(test.info().timeout + 3 * 60_000);
	const quiet = serverLog().length;
	await sleep(60_000);
	const restarts = serverLog().slice(quiet).split("\n").filter(line => line.includes("starts again from the game's trains"));
	expect(restarts, "U82: nothing changed, the component didn't start again").toEqual([]);

	const line3 = lines.find(line => line.layout === 3)!;
	const route = line3.routes[0];
	const mark = serverLog().length;
	const changed = Date.now();
	// A service two hours from now: nothing running now changes
	await openTimetable(label(route.name));
	await addService(later((await clock()).time, 120));
	await closeAll();
	const english = line3.depotName.split("|")[1].replace(" Depot", "");
	const since = () => serverLog().slice(mark).split("\n");
	await expect.poll(() => since().some(line => line.includes("starts again from the game's trains")), {message: "U82: the component starts again", timeout: 30_000}).toBe(true);
	await expect.poll(() => since().some(line => line.includes("planned the duties of") && new RegExp(`${english}(?![0-9])`).test(line)), {message: "U102: line 3's duties planned again", timeout: 30_000}).toBe(true);
	await testInfo.attach("after the change", {body: `${Math.round((Date.now() - changed) / 1000)} s\n` + since().filter(line => /starts again|planned the duties|path generation/.test(line)).join("\n")});
});
