// U118 线路路径只找一次, U101 用时刻表的车厂自动生成运用 (高): the server finds each route's path once (when its rails or
// platforms change, not when a depot is refreshed); a depot running by timetable with no duties set plans them itself
// and the web editor shows it as 自动运用. Only reads the log and the web editor: runs beside the trains being sampled.
// Covered here: one "found the path of route" for every route (lines.spec.ts refreshed every depot after that);
// 自动运用 in the depots layer and every line's duties planned. Not covered: a route's platforms changed finding only
// that route again, routes shared by several depots.
import {test, expect} from "../lib/test";
import {lines, serverLog} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

test("U118 每条线路的路径只找一次 @lines @U118", async ({}, testInfo) => {
	const found = serverLog().split("\n").filter(line => line.includes("found the path of route"));
	await testInfo.attach("paths found", {body: found.join("\n")});
	for (const line of lines) {
		for (const route of line.routes) {
			// The log loses the Chinese half of a name: count by the English one, which no other route's ends with
			const english = route.name.split("|")[1];
			const times = found.filter(each => each.includes(`|${english} (`)).length;
			expect(times, `${route.name}: its path found once`).toBe(1);
		}
	}
});

test("U101 车厂显示自动运用 @lines @U101", async ({page}) => {
	const {errors} = await openNextEditor(page, "depots");
	await expect(page.getByText("自动运用").first(), "a depot planning its own duties is shown so").toBeVisible({timeout: 20_000});
	const log = serverLog();
	for (const line of lines) {
		const english = line.depotName.split("|")[1].replace(" Depot", "");
		expect(new RegExp(`planned the duties of line [^\\n]*${english}(?![0-9])`).test(log), `${line.name}: duties planned from its timetables`).toBe(true);
	}
	expect(errors).toEqual([]);
});
