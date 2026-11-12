// The six track-layout lines running for some minutes after lines.spec.ts put in their timetables: every second the
// position of every train. Per line: trains come out of the depot onto the line, reach both ends, turn back where the
// layout has them turn (the turn-back track at 三站 for the short routes of lines 5 and 6); per train: no jump between
// two samples; per moment: no two trains on the line in the same place.
// Line 7 (a 2000-block section, services every 30 seconds): trains follow each other into the section by its signal
// blocks, not waiting before it while one is in it. Lines 8 and 9 (through services): the train of a service running on
// as another route's runs that one too, leaving the boundary stop on the other route's time, joining part of the way
// along (line 9's B at 三站) on that route's time there. Line 10 (站后双折返, services every 150 s): both turn-back tracks
// behind 五站 are used, consecutive trains take them in turn, and never two trains are on one at once.
// Lines 1 and 4 get no trains (their depots have no way out that keeps off the line's platforms run against, see
// MTR-测试项目清单.md); they are recorded, not failed.
import {test, expect} from "../lib/test";
import {existsSync, readFileSync} from "fs";
import {server, serverData, sleep} from "../lib/game";
import {Line, lines, serviceLabels, ThroughLink, trainsNow, TrainsAt, TrainSample} from "../lib/lines";
import {matchEvents, platformSpots, readPlan, readRoutePlan, RoutePlan, stopEvents, stopEventsAt, turnBacks} from "../lib/timing";

const MINUTES = Number(process.env.MTM_RUN_MINUTES || 10);
/** How far (seconds) a departure, an arrival (late; early is fine) or a turn-back may be off its plan */
const TOLERANCE = Number(process.env.MTM_TIME_TOLERANCE || 15);
/** Blocks a train may move in a second, at most (trains here run at up to 80 km/h, about 22 blocks a second) */
const MAX_PER_SECOND = 45;
const EXPECTED_WITHOUT_TRAINS = [1, 4];

const lineOf = (train: TrainSample) => lines.find(line => train.z > line.z - 40 && train.z < line.z + 60);
/** Lines whose stops are found by the platforms' own positions (the first six by their fixed layout) */
const byPlatform = (line: Line) => line.layout >= 7;
const DAY = 86_400_000;
const ofDay = (millis: number) => ((millis % DAY) + DAY) % DAY;
const diff = (a: number, b: number) => ((a - b) % DAY + DAY * 1.5) % DAY - DAY / 2;
const hhmmss = (millis: number) => new Date(millis).toISOString().slice(11, 19) + "Z";

/** The k-th service of a route, as lines.spec.ts put them in (their labels in order, from the first) */
function serviceIndex(routeName: string, label: string): number {
	for (const line of lines) {
		const route = line.routes.find(r => r.name === routeName);
		if (route) {
			const file = `${process.env.MTM_RESULTS || "."}/through-links.json`;
			const start = JSON.parse(readFileSync(file, "utf8")).start;
			return serviceLabels(line, route, start).indexOf(label);
		}
	}
	return -1;
}

test("各条线实际运行 @lines @run", async ({}, testInfo) => {
	test.setTimeout((MINUTES + 5) * 60_000);
	// The planned times, read off the timetable screens before the trains start
	const data = await serverData();
	const plans: RoutePlan[] = [];
	for (const line of lines.filter(l => !EXPECTED_WITHOUT_TRAINS.includes(l.layout))) {
		for (const route of line.routes) {
			const saved = data.routes.find((r: any) => String(r.id) === String(route.id));
			if (byPlatform(line)) {
				const plan = await readRoutePlan(saved);
				if (plan) plans.push({...plan, line: line.layout});
			} else {
				plans.push(await readPlan(line, route, saved));
			}
		}
	}
	await testInfo.attach("plans", {body: JSON.stringify(plans, null, 1), contentType: "application/json"});
	const samples: TrainsAt[] = [];
	const end = Date.now() + MINUTES * 60_000;
	while (Date.now() < end) {
		samples.push(await trainsNow());
		await sleep(1000);
	}

	const report: string[] = [];
	const failures: string[] = [];
	for (const line of lines) {
		const seen = samples.flatMap(sample => sample.trains.filter(train => lineOf(train) === line).map(train => ({...train, millis: sample.millis})));
		const onLine = seen.filter(train => train.onRoute);
		const xs = onLine.map(train => train.x);
		const usedMiddleTurnBack = onLine.some(train => Math.abs(train.z - (line.z + 4)) < 1.5 && train.x > 520 && train.x < 595);
		const stats = `${line.layout}号线 ${line.name}: ${new Set(seen.map(t => t.id)).size} trains seen, ${new Set(onLine.map(t => t.id)).size} out on the line, x ${xs.length ? Math.round(Math.min(...xs)) : "-"}..${xs.length ? Math.round(Math.max(...xs)) : "-"}${line.layout === 5 || line.layout === 6 ? `, middle turn-back used: ${usedMiddleTurnBack}` : ""}`;
		report.push(stats);
		if (EXPECTED_WITHOUT_TRAINS.includes(line.layout)) {
			continue;
		}
		if (!onLine.length) {
			failures.push(`${line.layout}号线: no train came out onto the line`);
			continue;
		}
		const stationX = line.stationX || [0, 200, 400, 640, 840];
		if (Math.min(...xs) > stationX[0] + 60) failures.push(`${line.layout}号线: no train reached 一站`);
		if (Math.max(...xs) < stationX[stationX.length - 1]) failures.push(`${line.layout}号线: no train reached the last station`);
		if ((line.layout === 5 || line.layout === 6) && !usedMiddleTurnBack) failures.push(`${line.layout}号线: no train used the turn-back track at 三站`);
	}

	// No train jumps between two samples
	const last = new Map<string, TrainSample & {millis: number}>();
	for (const sample of samples) {
		for (const train of sample.trains) {
			const before = last.get(train.id);
			if (before && train.onRoute && before.onRoute) {
				const seconds = Math.max(0.5, (sample.millis - before.millis) / 1000);
				const moved = Math.hypot(train.x - before.x, train.z - before.z);
				if (moved > MAX_PER_SECOND * seconds) failures.push(`train ${train.id} (${lineOf(train)?.layout}号线) jumped ${Math.round(moved)} blocks in ${seconds.toFixed(1)} s, from ${Math.round(before.x)},${Math.round(before.z)} to ${Math.round(train.x)},${Math.round(train.z)}`);
			}
			last.set(train.id, {...train, millis: sample.millis});
		}
		// No two trains out on the line with their heads in the same place; a siding stores its trains on top of each other
		const inDepot = (train: TrainSample) => lines.some(l => l.depotBox && train.x >= l.depotBox[0] && train.x <= l.depotBox[2] && train.z >= l.z + l.depotBox[1] && train.z <= l.z + l.depotBox[3]);
		const out = sample.trains.filter(train => train.onRoute && !inDepot(train));
		for (let i = 0; i < out.length; i++) for (let j = i + 1; j < out.length; j++) {
			if (Math.hypot(out[i].x - out[j].x, out[i].z - out[j].z) < 3) failures.push(`trains ${out[i].id} and ${out[j].id} in the same place ${Math.round(out[i].x)},${Math.round(out[i].z)}`);
		}
	}

	// Trains out on the line standing in one place for four minutes or more (waiting for a service can be right; reported)
	const standing = new Map<string, {x: number, z: number, since: number}>();
	const longest = new Map<string, string>();
	for (const sample of samples) {
		for (const train of sample.trains.filter(t => t.onRoute)) {
			const at = standing.get(train.id);
			if (at && Math.hypot(train.x - at.x, train.z - at.z) < 1) {
				if (sample.millis - at.since >= 240_000) {
					longest.set(train.id, `train ${train.id} (${lineOf(train)?.layout}号线) stood at ${Math.round(train.x)},${Math.round(train.z - (lineOf(train)?.z || 0))} for ${Math.round((sample.millis - at.since) / 1000)} s`);
				}
			} else {
				standing.set(train.id, {x: train.x, z: train.z, since: sample.millis});
			}
		}
	}
	report.push(...longest.values());

	// Timing: every stop against its planned time, every turn-back against its planned time
	const spots = platformSpots(data);
	const platformLines = lines.filter(byPlatform);
	const events = [
		...stopEvents(samples, lines.filter(l => !byPlatform(l))),
		...stopEventsAt(samples, spots).map(event => {
			const spot = spots.find(s => s.id === event.platform)!;
			const line = platformLines.find(l => spot.z1 > l.z - 40 && spot.z1 < l.z + 60);
			return {...event, line: line ? line.layout : -1};
		}).filter(event => event.line > 0),
	];
	const matches = matchEvents(events, plans);
	const turns = turnBacks(matches);
	for (const line of lines.filter(l => !EXPECTED_WITHOUT_TRAINS.includes(l.layout))) {
		const here = matches.filter(m => m.plan.line === line.layout);
		const departs = here.filter(m => m.departLate !== null).map(m => m.departLate! / 1000);
		const arrives = here.filter(m => m.arriveLate !== null).map(m => m.arriveLate! / 1000);
		const range = (v: number[]) => v.length ? `${v.length}, ${Math.round(Math.min(...v))}..${Math.round(Math.max(...v))} s` : "0";
		report.push(`${line.layout}号线 timing: departures ${range(departs)} off plan, arrivals ${range(arrives)} late`);
		// Every service starts by standing at its first stop until its departure (U-L05: trains out of the depot ran through it)
		const origins = here.filter(m => m.stop === 0 && m.departLate !== null);
		if (here.length && !origins.length) failures.push(`${line.layout}号线: no train stood at a service's first stop`);
		for (const m of origins) {
			if (m.departLate! / 1000 < -3) failures.push(`${line.layout}号线 ${m.plan.name.split("|")[0]}: left its first stop ${Math.round(-m.departLate! / 1000)} s early`);
		}
		for (const m of here) {
			const where = `${m.plan.name.split("|")[0]} 第${m.stop + 1}站 ${new Date(m.event.arrive).toISOString().slice(11, 19)}Z`;
			if (m.departLate !== null && Math.abs(m.departLate / 1000) > TOLERANCE) failures.push(`${where}: departed ${Math.round(m.departLate / 1000)} s off plan`);
			if (m.arriveLate !== null && m.arriveLate / 1000 > TOLERANCE) failures.push(`${where}: arrived ${Math.round(m.arriveLate / 1000)} s late`);
		}
	}
	// Line 7: the long section between 二站 and 三站 (x 260..2200), each track on its own
	const long = lines.find(l => l.layout === 7);
	if (long) {
		const [, second, last] = long.stationX;
		let most = 0, closest = Infinity, waitedBefore = 0;
		const blocked = new Set<string>();
		for (const sample of samples) {
			for (const track of [0, 8]) {
				const here = sample.trains.filter(t => t.onRoute && lineOf(t) === long && Math.abs(t.z - long.z - track - 0.5) < 1.5);
				const inSection = here.filter(t => t.x > second + 60 && t.x < last).sort((a, b) => a.x - b.x);
				most = Math.max(most, inSection.length);
				for (let i = 1; i < inSection.length; i++) closest = Math.min(closest, inSection[i].x - inSection[i - 1].x);
				// A train standing just outside the section (not at a platform) while another is in it
				const entrance = track === 0 ? second + 60 : last;
				for (const t of here) {
					const outside = track === 0 ? t.x > entrance - 5 && t.x <= entrance + 40 : t.x >= entrance - 40 && t.x < entrance + 5;
					if (t.speed < 0.005 && outside && inSection.length) {
						waitedBefore++;
						blocked.add(t.id);
					}
				}
			}
		}
		report.push(`7号线 long section: at most ${most} trains in it on one track, closest ${closest === Infinity ? "-" : Math.round(closest)} blocks apart; ${waitedBefore} samples of a train standing at its entrance while another was in it (${blocked.size} trains)`);
		if (most < 2) failures.push(`7号线: never two trains in the long section on one track (services every 30 s should put several in it)`);
		if (waitedBefore > 0) failures.push(`7号线: trains stood at the long section's entrance while another train was in it (${blocked.size} trains)`);
	}

	// Line 10: the two tails behind 五站, by the trains' heads on them
	const doubleTurn = lines.find(l => l.layout === 10);
	if (doubleTurn?.tails) {
		const tails = doubleTurn.tails;
		const tailOf = (t: TrainSample) => tails.findIndex(([x1, x2, z]) => t.x >= x1 - 2 && t.x <= x2 + 2 && Math.abs(t.z - z - 0.5) < 1.5);
		// Each visit of a train to a tail, in the order they began
		const visits: {train: string, tail: number, from: number, to: number}[] = [];
		const current = new Map<string, {train: string, tail: number, from: number, to: number}>();
		const lastVisit = new Map<string, {train: string, tail: number, from: number, to: number}>();
		let shared = 0;
		for (const sample of samples) {
			const byTail = new Map<number, string[]>();
			for (const t of sample.trains.filter(t => t.onRoute && lineOf(t) === doubleTurn)) {
				const tail = tailOf(t);
				const open = current.get(t.id);
				if (tail < 0) {
					current.delete(t.id);
					continue;
				}
				byTail.set(tail, [...(byTail.get(tail) || []), t.id]);
				// The same train back on the same tail within two minutes is the same turn (its head changes ends as it reverses)
				const previous = lastVisit.get(t.id);
				if (open && open.tail === tail) {
					open.to = sample.millis;
				} else if (previous && previous.tail === tail && sample.millis - previous.to < 120_000) {
					previous.to = sample.millis;
					current.set(t.id, previous);
				} else {
					const visit = {train: t.id, tail, from: sample.millis, to: sample.millis};
					visits.push(visit);
					current.set(t.id, visit);
					lastVisit.set(t.id, visit);
				}
			}
			for (const [tail, ids] of byTail) {
				if (ids.length > 1) {
					shared++;
					failures.push(`10号线: trains ${ids.map(id => id.slice(-4)).join(", ")} on tail ${tail + 1} at once at ${hhmmss(sample.millis)}`);
				}
			}
			// Trains no longer seen at all leave their visit
			for (const id of [...current.keys()]) if (!sample.trains.some(t => t.id === id)) current.delete(id);
		}
		visits.sort((a, b) => a.from - b.from);
		const order = visits.map(v => `${v.tail + 1}(${v.train.slice(-4)} ${hhmmss(v.from)} ${Math.round((v.to - v.from) / 1000)} s)`);
		const repeats = visits.filter((v, i) => i > 0 && visits[i - 1].tail === v.tail);
		const both = new Set(visits.map(v => v.tail)).size;
		report.push(`10号线 tails: ${visits.length} visits, tail 1 ${visits.filter(v => v.tail === 0).length}, tail 2 ${visits.filter(v => v.tail === 1).length}, ${repeats.length} times the same tail twice in a row, ${shared} samples with two trains on one tail; ${order.join(" ")}`);
		if (visits.length >= 2 && both < 2) failures.push(`10号线: only tail ${visits[0].tail + 1} was used (${visits.length} visits)`);
		if (repeats.length) failures.push(`10号线: consecutive trains took the same tail ${repeats.length} times: ${repeats.map(v => `${v.tail + 1} at ${hhmmss(v.from)}`).join(", ")}`);
		if (visits.length < 2) failures.push(`10号线: ${visits.length} train(s) seen on the tails behind 五站`);
	}

	// Lines 8 and 9: the through links lines.spec.ts set
	const linksFile = `${process.env.MTM_RESULTS || "."}/through-links.json`;
	const links: ThroughLink[] = existsSync(linksFile) ? JSON.parse(readFileSync(linksFile, "utf8")).links : [];
	const first = samples.length ? ofDay(samples[0].millis) : 0, lastSample = samples.length ? ofDay(samples[samples.length - 1].millis) : 0;
	const seconds = (text: string) => text.split(":").map(Number).reduce((a, v) => a * 60 + v, 0);
	let checked = 0;
	for (const link of links) {
		const fromPlan = plans.find(p => p.routeId === link.from), toPlan = plans.find(p => p.routeId === link.to);
		if (!fromPlan || !toPlan) continue;
		// The services by their times: the labels are in the timetable's zone, the plans' services in UTC; the k-th of both
		const fromIndex = serviceIndex(link.fromName, link.fromService), toIndex = serviceIndex(link.toName, link.toService);
		const fromService = fromPlan.services[fromIndex], toService = toPlan.services[toIndex];
		if (fromService === undefined || toService === undefined) continue;
		const leave = link.leaveStop < 0 ? fromPlan.stops.length - 1 : link.leaveStop;
		const join = link.joinStop;
		const plannedArrive = fromService + fromPlan.arrive[leave]!;
		const plannedDepart = toService + (toPlan.depart[join] ?? 0);
		// Only links whose train should have left the boundary well inside the sampled time
		if (diff(ofDay(plannedDepart), first) < 0 || diff(lastSample, ofDay(plannedDepart)) < 120_000) continue;
		checked++;
		const name = `${link.fromName.split("|")[0]} ${link.fromService} → ${link.toName.split("|")[0]} ${link.toService}`;
		const ran = matches.filter(m => m.plan === fromPlan && m.service === fromService);
		if (!ran.length) {
			failures.push(`${name}: no train ran ${link.fromName.split("|")[0]}'s service`);
			continue;
		}
		const train = ran[0].event.train;
		const boundary = toPlan.stops[join].platform;
		const atBoundary = events.find(e => e.train === train && e.platform === boundary && Math.abs(diff(ofDay(e.arrive), ofDay(plannedArrive))) < 180_000);
		const after = matches.filter(m => m.event.train === train && m.plan === toPlan && m.service === toService && m.stop > join);
		const line = `${name}: train ${train.slice(-4)}`;
		if (!atBoundary) {
			failures.push(`${line} did not stop at the boundary`);
		} else {
			const arriveLate = Math.round(diff(ofDay(atBoundary.arrive), ofDay(plannedArrive)) / 1000);
			const departLate = atBoundary.depart === null ? null : Math.round(diff(ofDay(atBoundary.depart), ofDay(plannedDepart)) / 1000);
			report.push(`${line}: at the boundary arrived ${arriveLate} s off ${link.fromName.split("|")[0]}'s plan, left ${departLate} s off ${link.toName.split("|")[0]}'s plan, then ${after.length} stops of it`);
			if (arriveLate > TOLERANCE) failures.push(`${line} arrived at the boundary ${arriveLate} s late`);
			if (departLate !== null && Math.abs(departLate) > TOLERANCE) failures.push(`${line} left the boundary ${departLate} s off plan`);
		}
		// Ran on: stops of the route after joining it, or, leaving it again at its next stop, any stop after the boundary
		const carriedOn = after.length || atBoundary && matches.some(m => m.event.train === train && m.event.arrive > atBoundary.arrive && m.event.arrive - atBoundary.arrive < 300_000);
		if (!carriedOn) failures.push(`${line} did not run on as ${link.toName.split("|")[0]} ${link.toService}`);
	}
	if (links.length) report.push(`through links: ${links.length} set, ${checked} inside the sampled time checked`);

	// Timetable sensors by 三站's eastbound platform of line 2 (2026-11-12): each pulses about 10 s before the trains it is
	// set for, express trains running through without stopping and 上行 trains stopping, and not for the others
	const line2 = lines.find(l => l.layout === 2);
	if (line2?.sensors?.length) {
		const pulses: {sensor: number, millis: number}[] = (await server.call("/sensors")).pulses;
		const expressId = String(line2.routes.find(r => r.name.includes("快车"))?.id);
		const platformStart = line2.stationX[2], platformEnd = platformStart + 60;
		const comings: {kind: "passing" | "stopping", millis: number, train: string}[] = [];
		const seenTrain = new Map<string, number>();
		for (const sample of samples) {
			for (const t of sample.trains) {
				if (!t.onRoute || Math.abs(t.z - line2.z - 0.5) > 1.5 || t.x < platformStart - 5 || t.x > platformEnd) continue;
				const passing = t.route === expressId;
				if (passing ? t.speed <= 0.005 : t.speed > 0.005) continue;
				// Once per train and visit
				const last = seenTrain.get(t.id);
				if (last !== undefined && sample.millis - last < 60_000) continue;
				seenTrain.set(t.id, sample.millis);
				comings.push({kind: passing ? "passing" : "stopping", millis: sample.millis, train: t.id});
			}
		}
		const firstMillis = samples[0]?.millis ?? 0;
		const counted = comings.filter(c => c.millis - 20_000 > firstMillis);
		line2.sensors.forEach((sensor, index) => {
			const own = pulses.filter(p => p.sensor === index).map(p => p.millis);
			const takes = (kind: string) => sensor.kind === 0 || (sensor.kind === 2) === (kind === "passing");
			const name = `sensor ${index} (${sensor.trigger}, ${["all", "stopping", "passing"][sensor.kind]})`;
			const before = (c: {millis: number}) => own.find(p => c.millis - p >= 2_000 && c.millis - p <= 20_000);
			const offsets: string[] = [];
			for (const c of counted) {
				const pulse = before(c);
				if (takes(c.kind)) {
					if (pulse === undefined) failures.push(`${name}: no pulse before the ${c.kind} train ${c.train.slice(-4)} at ${hhmmss(c.millis)}`);
					else offsets.push(`${c.kind} ${Math.round((c.millis - pulse) / 1000)} s`);
				} else if (pulse !== undefined && !counted.some(other => other !== c && takes(other.kind) && before(other) === pulse)) {
					failures.push(`${name}: pulsed for the ${c.kind} train ${c.train.slice(-4)} at ${hhmmss(c.millis)}, which it isn't set for`);
				}
			}
			report.push(`${name}: ${own.length} pulses; before ${offsets.join(", ") || "-"}`);
		});
		report.push(`line 2 at 三站: ${counted.filter(c => c.kind === "passing").length} express trains through, ${counted.filter(c => c.kind === "stopping").length} stopping`);
		if (!counted.some(c => c.kind === "passing")) failures.push("line 2: no express train came through 三站 while sampled");
	}

	for (const turn of turns) {
		report.push(`${turn.line}号线 turn-back ${turn.from.split("|")[0]} → ${turn.to.split("|")[0]}: planned ${turn.plannedSeconds} s, took ${turn.actualSeconds} s, next departure ${turn.departLate} s off`);
		if (turn.actualSeconds > turn.plannedSeconds + TOLERANCE) failures.push(`${turn.line}号线 turn-back ${turn.from.split("|")[0]} → ${turn.to.split("|")[0]} took ${turn.actualSeconds} s, planned ${turn.plannedSeconds} s`);
	}
	await testInfo.attach("matches", {body: JSON.stringify(matches.map(m => ({train: m.event.train, route: m.plan.name, stop: m.stop, arrive: m.event.arrive, depart: m.event.depart, arriveLate: m.arriveLate, departLate: m.departLate}))), contentType: "application/json"});
	await testInfo.attach("lines", {body: report.join("\n"), contentType: "text/plain"});
	await testInfo.attach("samples", {body: JSON.stringify(samples), contentType: "application/json"});
	console.log(report.join("\n"));
	expect([...new Set(failures)].slice(0, 30)).toEqual([]);
});
