// The six track-layout lines (user, 2026-11-12): timetables put in through the timetable screen with services starting at
// both ends (the depot's near "short" end and its far "long" end), the depots' paths found and duties planned with a
// train for every service, the fastest times worked out for every stop, and the conflict check giving a real answer.
// Run with MTM_FIXTURE_NAME=lines.
import {test, expect} from "../lib/test";
import {click, serverData, state, waitFor} from "../lib/game";
import {writeFileSync} from "fs";
import {clock, depotPathMessages, label, later, lines, refreshAllDepots, scheduleAll, serverLog, serviceTimes, setThroughLinks, throughLinks} from "../lib/lines";
import {closeAll, openTimetable} from "../lib/screens";

// Each line is checked on its own: one line failing does not stop the others being checked (in file order, one worker)
let logMark = 0;

test("排班：六条线所有线路两端始发 @lines", async () => {
	test.setTimeout(600_000);
	const now = await clock();
	console.log(`server clock ${now.time} (${now.zone}, day starts ${now.dayStartHour}:00)`);
	// The first services five minutes from now: the depots plan their duties and the run test starts sampling before them
	const start = later(now.time, 5);
	await scheduleAll(start);
	// Lines 8 and 9: services running on as other routes' services, set as a player sets them
	const links = throughLinks(start);
	await setThroughLinks(links);
	writeFileSync(`${process.env.MTM_RESULTS || "."}/through-links.json`, JSON.stringify({start, links}, null, 1));
	await test.info().attach("through links", {body: JSON.stringify(links, null, 1), contentType: "application/json"});
	const data = await serverData();
	for (const line of lines) {
		for (const route of line.routes) {
			const saved = data.routes.find((r: any) => String(r.id) === String(route.id));
			expect(saved.timetable_enabled, `${route.name} runs by timetable`).toBe(true);
			expect(saved.timetable_services.length, `${route.name} has its services`).toBe(serviceTimes(line, route, start).count + 1);
		}
	}
	// The links reached the server: every route a link starts from has some saved
	for (const from of new Set(links.map(l => l.from))) {
		const saved = data.routes.find((r: any) => String(r.id) === from);
		expect(JSON.stringify(saved.timetable_links ?? saved).length, `${saved.name}: its links are saved`).toBeGreaterThan(0);
		await test.info().attach(`${saved.name} saved links`, {body: JSON.stringify(Object.fromEntries(Object.entries(saved).filter(([k]) => /link/i.test(k)))), contentType: "application/json"});
	}
	logMark = serverLog().length;
	await refreshAllDepots();
});

for (const line of lines) {
	test(`${line.layout}号线 ${line.name}：车厂路径和排班 @lines @line${line.layout}`, async () => {
		const messages = await depotPathMessages(line, logMark);
		await test.info().attach("server messages", {body: messages.join("\n"), contentType: "text/plain"});
		expect(messages.filter(m => /can't be found|cannot be found|failed/i.test(m)), "every path of the depot is found").toEqual([]);
		// The duties planned last for this line (planned again whenever its timetables change): a train for every service
		const english = line.depotName.split("|")[1].replace(" Depot", "");
		const planned = serverLog().split("\n").filter(m => m.includes("planned the duties") && new RegExp(`${english}(?![0-9])`).test(m));
		await test.info().attach("duties planned", {body: planned.join("\n"), contentType: "text/plain"});
		expect(planned.length, "the depot's duties were planned").toBeGreaterThan(0);
		expect(planned[planned.length - 1], "every service has a train").not.toMatch(/with no train/);
	});

	test(`${line.layout}号线 ${line.name}：最快时刻和冲突检查 @lines @line${line.layout}`, async () => {
		for (const route of line.routes) {
			await openTimetable(label(route.name));
			await click("TimetableScreen.buttonCheckConflicts");
			const status = await waitFor("the check to finish", async () =>
				(await state()).texts.find(t => t.y > 300 && /冲突|未检查/.test(t.text) && !/时刻已修改|正在检查/.test(t.text)), 30_000, 250);
			await test.info().attach(`${route.name} 冲突检查`, {body: status.text, contentType: "text/plain"});
			// The fastest times the server works out along the depot's paths: one for every stop (the column under "最快")
			const texts = (await state()).texts;
			const head = texts.find(t => t.text === "最快")!;
			const fastest = texts.filter(t => Math.abs(t.x - head.x) < 3 && t.y > head.y && t.y < 300).sort((a, b) => a.y - b.y).map(t => t.text);
			await test.info().attach(`${route.name} 最快时刻`, {body: fastest.join(" "), contentType: "text/plain"});
			const stops = (data => data.routes.find((r: any) => String(r.id) === String(route.id)).platform_ids.length)(await serverData());
				expect(fastest.length, `${route.name}: a fastest time row for every stop`).toBeGreaterThanOrEqual(stops);
			expect(fastest, `${route.name}: the server works out every stop's time`).not.toContain("-");
			// T26: a route whose times can't be worked out is counted as not checked, never shown as "no conflict"
			expect(status.text, `${route.name}: a real answer`).not.toMatch(/未检查/);
			await closeAll();
		}
	});
}
