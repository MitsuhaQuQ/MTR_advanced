// A player's own world (a copy, MTM_WORLD; MTM_FIXTURE_NAME=world): what its settings make the server say and how its
// timetables run. Nothing here is built: the depots' paths are found again through their screens, every route with a
// timetable is checked (fastest times, conflicts), then the trains are sampled for some minutes and every stop at a
// platform is matched to the planned time of the service it fits best, turn-backs included.
// Findings are reported (attachments and console); only errors in the server's log fail a test, as lib/test.ts does.
import {test, expect} from "../lib/test";
import {click, serverData, sleep, state, waitFor} from "../lib/game";
import {label, serverLog, trainsNow, TrainsAt} from "../lib/lines";

/** Which of the routes named the same this one is, in the order the server lists them (the dashboard lists them so too) */
const nthOfName = (routes: any[], route: any) => routes.filter(r => String(r.name) === String(route.name)).indexOf(route);
import {closeAll, openTimetable, refreshDepotPath} from "../lib/screens";
import {matchEvents, platformSpots, readRoutePlan, RoutePlan, stopEventsAt, turnBacks} from "../lib/timing";

const MINUTES = Number(process.env.MTM_RUN_MINUTES || 10);
const TOLERANCE = Number(process.env.MTM_TIME_TOLERANCE || 15);
const PROBLEM = /can't be found|cannot be found|failed|no way|with no train|Exception|against/i;

test("设置问题：车厂路径与排班 @world", async () => {
	test.setTimeout(1_200_000);
	const data = await serverData();
	const mark = serverLog().length;
	const report: string[] = [];
	for (const depot of data.depots) {
		try {
			await refreshDepotPath(label(String(depot.name)));
		} catch (e) {
			report.push(`depot ${depot.name}: could not refresh through its screen (${String(e).split("\n")[0]})`);
			await closeAll();
		}
	}
	// Path finding runs in the background: wait until the log has been quiet for ten seconds
	let size = -1;
	await waitFor("path finding to settle", async () => {
		await sleep(10_000);
		const now = serverLog().length;
		const quiet = now === size;
		size = now;
		return quiet;
	}, 600_000, 0);
	const log = serverLog().slice(mark).split("\n");
	const problems = log.filter(l => PROBLEM.test(l));
	const planned = log.filter(l => l.includes("planned the duties"));
	report.push(`${data.depots.length} depots, ${data.routes.length} routes; ${problems.length} problem lines, ${planned.length} duty plans`);
	await test.info().attach("problems", {body: problems.join("\n"), contentType: "text/plain"});
	await test.info().attach("duties planned", {body: planned.join("\n"), contentType: "text/plain"});
	await test.info().attach("report", {body: report.join("\n"), contentType: "text/plain"});
	console.log([...report, ...problems.slice(0, 60)].join("\n"));
});

test("时刻表：最快时刻与冲突 @world", async () => {
	test.setTimeout(1_800_000);
	const data = await serverData();
	const report: string[] = [];
	for (const route of data.routes.filter((r: any) => r.timetable_enabled && r.timetable_services?.length)) {
		const name = label(String(route.name));
		try {
			// Routes in a line group open through it (lib/screens.ts openGroupFor); routes named the same by their order
			await openTimetable(name, nthOfName(data.routes, route));
			await click("TimetableScreen.buttonCheckConflicts");
			const status = await waitFor("the check to finish", async () =>
				(await state()).texts.find(t => t.y > 300 && /冲突|未检查/.test(t.text) && !/时刻已修改|正在检查/.test(t.text)), 60_000, 250);
			const texts = (await state()).texts;
			const head = texts.find(t => t.text === "最快");
			const fastest = head ? texts.filter(t => Math.abs(t.x - head.x) < 3 && t.y > head.y && t.y < 300).sort((a, b) => a.y - b.y).map(t => t.text) : [];
			const missing = fastest.filter(t => t === "-").length;
			report.push(`${name} (${route.timetable_services.length} services, ${route.platform_ids.length} stops): ${status.text}; fastest ${fastest.length ? fastest.join(" ") : "none"}${missing ? ` — ${missing} stops without a time` : ""}`);
		} catch (e) {
			report.push(`${name}: could not check (${String(e).split("\n")[0]})`);
		}
		await closeAll();
	}
	await test.info().attach("routes", {body: report.join("\n"), contentType: "text/plain"});
	console.log(report.join("\n"));
});

test("实际运行：到发时刻与折返 @world @run", async ({}, testInfo) => {
	test.setTimeout((MINUTES + 30) * 60_000);
	const data = await serverData();
	const plans: RoutePlan[] = [];
	for (const route of data.routes.filter((r: any) => r.timetable_enabled && r.timetable_services?.length)) {
		try {
			const plan = await readRoutePlan(route, nthOfName(data.routes, route));
			if (plan) plans.push(plan);
		} catch (e) {
			console.log(`${route.name}: plan not read (${String(e).split("\n")[0]})`);
			await closeAll();
		}
	}
	await testInfo.attach("plans", {body: JSON.stringify(plans, null, 1), contentType: "application/json"});
	const samples: TrainsAt[] = [];
	const end = Date.now() + MINUTES * 60_000;
	while (Date.now() < end) {
		samples.push(await trainsNow());
		await sleep(1000);
	}
	const spots = platformSpots(data);
	const events = stopEventsAt(samples, spots);
	const matches = matchEvents(events, plans);
	const turns = turnBacks(matches);
	const report: string[] = [`${new Set(samples.flatMap(s => s.trains.filter(t => t.onRoute).map(t => t.id))).size} trains on routes, ${events.length} stops at platforms, ${matches.length} matched to a plan`];
	const out: string[] = [];
	for (const plan of plans) {
		const here = matches.filter(m => m.plan === plan);
		if (!here.length) continue;
		const departs = here.filter(m => m.departLate !== null).map(m => m.departLate! / 1000);
		const arrives = here.filter(m => m.arriveLate !== null).map(m => m.arriveLate! / 1000);
		const range = (v: number[]) => v.length ? `${v.length}, ${Math.round(Math.min(...v))}..${Math.round(Math.max(...v))} s` : "0";
		report.push(`${plan.name}: departures ${range(departs)} off plan, arrivals ${range(arrives)} late`);
		for (const m of here) {
			const spot = spots.find(s => s.id === m.event.platform);
			const where = `${plan.name.split("|")[0]} 第${m.stop + 1}站(${spot?.name ?? "?"}) ${new Date(m.event.arrive).toISOString().slice(11, 19)}Z`;
			if (m.departLate !== null && Math.abs(m.departLate / 1000) > TOLERANCE) out.push(`${where}: departed ${Math.round(m.departLate / 1000)} s off plan`);
			if (m.arriveLate !== null && m.arriveLate / 1000 > TOLERANCE) out.push(`${where}: arrived ${Math.round(m.arriveLate / 1000)} s late`);
		}
	}
	for (const turn of turns) {
		report.push(`turn-back ${turn.from.split("|")[0]} → ${turn.to.split("|")[0]}: planned ${turn.plannedSeconds} s, took ${turn.actualSeconds} s, next departure ${turn.departLate} s off`);
	}
	report.push(...out);
	await testInfo.attach("report", {body: report.join("\n"), contentType: "text/plain"});
	await testInfo.attach("matches", {body: JSON.stringify(matches.map(m => ({train: m.event.train, route: m.plan.name, stop: m.stop, platform: m.event.platform, arrive: m.event.arrive, depart: m.event.depart, arriveLate: m.arriveLate, departLate: m.departLate}))), contentType: "application/json"});
	await testInfo.attach("samples", {body: JSON.stringify(samples), contentType: "application/json"});
	console.log(report.join("\n"));
	expect(samples.length).toBeGreaterThan(0);
});
