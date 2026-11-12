// Timing accuracy of the six track-layout lines: the planned time of every stop of every service, read from the
// timetable screen, against when trains actually stood at the platforms, sampled every second from the server; and the
// turn-backs: from a train's arrival at the end of one service to its departure on the next, planned against actual.
import {closeAll, openTimetable, selectService} from "./screens";
import {list, state} from "./game";
import {label, Line, TrainsAt} from "./lines";

const DAY = 86_400_000;
/** Shorter stands are reversals, not stops (a service's stop opens and closes the doors, several seconds) */
export const MIN_STOP_MILLIS = 3000;
/** Where the stations' platforms start (x) and their length, as in TestKit's Lines.java */
export const STATION_X = [0, 200, 400, 640, 840];
const PLATFORM = 60;

export interface RoutePlan {
	routeId: string, name: string, line: number,
	/** For each stop in route order: station index (0 = 一站) and track (relative z) */
	stops: {station: number, track: number, platform?: string}[],
	/** Planned arrival and departure of each stop, in milliseconds after the service's departure from its first stop */
	arrive: (number | null)[], depart: (number | null)[],
	/** The services' departures from the first stop, milliseconds of the day (UTC, as the server keeps them) */
	services: number[],
}

const seconds = (text: string) => {
	const m = /^(\d\d):(\d\d):(\d\d)$/.exec(text.trim());
	return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) : null;
};

/**
 * Reads a route's planned times off its timetable screen (its first service shown): the 到 and 发 columns, one row a stop,
 * as offsets from the first departure.
 */
export async function readPlan(line: Line, route: {id: string, name: string}, saved: any): Promise<RoutePlan> {
	await openTimetable(label(route.name));
	const first = (await list("TimetableScreen.servicesList")).rows[0];
	await selectService(first.label);
	const texts = (await state()).texts;
	const headArrive = texts.find(t => t.text === "到")!, headDepart = texts.find(t => t.text === "发")!;
	const rows = texts.filter(t => t.x < headArrive.x - 100 && t.y > headArrive.y && t.y < 300 && /站/.test(t.text)).sort((a, b) => a.y - b.y);
	const at = (x: number, y: number) => texts.find(t => Math.abs(t.y - y) < 3 && Math.abs(t.x - x) < 8 && seconds(t.text) !== null);
	const base = seconds(first.label)!;
	const arrive: (number | null)[] = [], depart: (number | null)[] = [];
	for (const row of rows) {
		const a = at(headArrive.x, row.y), d = at(headDepart.x, row.y);
		const offset = (t: typeof a) => t ? ((seconds(t.text)! - base + 86400) % 86400) * 1000 : null;
		arrive.push(offset(a));
		depart.push(offset(d));
	}
	await closeAll();
	const stations = ["一站", "二站", "三站", "四站", "五站"];
	const down = route.name.includes("下行");
	const stops = rows.map(row => ({station: stations.findIndex(s => row.text.includes(s)), track: down ? 8 : 0}));
	return {routeId: String(route.id), name: route.name, line: line.layout, stops, arrive, depart, services: (saved.timetable_services as number[]).slice().sort((a, b) => a - b)};
}

export interface StopEvent {train: string, line: number, station: number, track: number, platform?: string, arrive: number, depart: number | null, trips?: string[]}

/** The route and service a sampled train runs, as "route:millis of the day" (the server says it, TestKit 0.69) */
const tripKey = (train: {route?: string, trip?: number}) => train.route && train.route !== "0" && train.trip ? `${train.route}:${((train.trip % 86_400_000) + 86_400_000) % 86_400_000}` : null;
const noteTrip = (event: StopEvent, train: {route?: string, trip?: number}) => {
	const key = tripKey(train);
	if (key && !(event.trips ||= []).includes(key)) event.trips.push(key);
};

/** When each train stood still at a platform: the first second at rest there, and the second it moved on */
export function stopEvents(samples: TrainsAt[], lines: Line[]): StopEvent[] {
	const events: StopEvent[] = [];
	const open = new Map<string, StopEvent>();
	for (const sample of samples) {
		const here = new Set<string>();
		for (const train of sample.trains) {
			const line = lines.find(l => train.z > l.z - 40 && train.z < l.z + 60);
			if (!line || !train.onRoute) continue;
			// Nodes are at block centres, so a track at relative z 0 is at 0.5: floor, not round (round(0.5) is 1)
			const track = Math.floor(train.z - line.z);
			const station = STATION_X.findIndex(x => train.x >= x - 10 && train.x <= x + PLATFORM + 12);
			const atRest = train.speed < 0.005 && station >= 0 && [0, 8, 16].includes(track);
			const current = open.get(train.id);
			if (atRest) {
				here.add(train.id);
				if (!current || current.station !== station || current.track !== track) {
					if (current) current.depart = sample.millis;
					const event: StopEvent = {train: train.id, line: line.layout, station, track, arrive: sample.millis, depart: null};
					events.push(event);
					open.set(train.id, event);
				}
				noteTrip(open.get(train.id)!, train);
			}
		}
		for (const [id, event] of open) {
			if (!here.has(id)) {
				event.depart = sample.millis;
				open.delete(id);
			}
		}
	}
	return events;
}

const ofDay = (millis: number) => ((millis % DAY) + DAY) % DAY;
/** The signed difference of two times of day, the short way round midnight */
const diff = (a: number, b: number) => ((a - b) % DAY + DAY * 1.5) % DAY - DAY / 2;

export interface Match {event: StopEvent, plan: RoutePlan, stop: number, service: number, arriveLate: number | null, departLate: number | null}

/** Each stop event matched to the planned stop of the service it fits best (by departure, else arrival) */
export function matchEvents(events: StopEvent[], plans: RoutePlan[]): Match[] {
	const matches: Match[] = [];
	// A train reversing at a platform on its way (out of the depot) stands there a second or so: not a stop of a service
	for (const event of events.filter(e => e.depart === null || e.depart - e.arrive >= MIN_STOP_MILLIS)) {
		let best: Match | null = null;
		let bestError = Infinity;
		for (const plan of plans.filter(p => p.line === event.line)) {
			plan.stops.forEach((stop, k) => {
				if (event.platform !== undefined ? stop.platform !== event.platform : stop.station !== event.station || stop.track !== event.track) return;
				for (const service of plan.services) {
					// Where the server said which service of these routes the train ran there, only that one
					if (event.trips?.length && plans.some(p => event.trips!.some(key => key.startsWith(p.routeId + ":"))) && !event.trips.includes(`${plan.routeId}:${service}`)) continue;
					const departLate = event.depart !== null && plan.depart[k] !== null && k < plan.stops.length - 1 ? diff(ofDay(event.depart), ofDay(service + plan.depart[k]!)) : null;
					const arriveLate = plan.arrive[k] !== null && k > 0 ? diff(ofDay(event.arrive), ofDay(service + plan.arrive[k]!)) : null;
					const error = Math.abs(departLate ?? arriveLate ?? Infinity);
					if (error < bestError) {
						bestError = error;
						best = {event, plan, stop: k, service, arriveLate, departLate};
					}
				}
			});
		}
		if (best) matches.push(best);
	}
	return matches;
}

export interface TurnBack {train: string, line: number, from: string, to: string, plannedSeconds: number, actualSeconds: number, departLate: number}

/** A train's arrival at the last stop of one service followed by its departure from the first stop of its next */
export function turnBacks(matches: Match[]): TurnBack[] {
	const byTrain = new Map<string, Match[]>();
	matches.forEach(m => byTrain.set(m.event.train, [...(byTrain.get(m.event.train) || []), m]));
	const result: TurnBack[] = [];
	for (const [train, list] of byTrain) {
		list.sort((a, b) => a.event.arrive - b.event.arrive);
		for (let i = 1; i < list.length; i++) {
			const end = list[i - 1], start = list[i];
			if (end.stop !== end.plan.stops.length - 1 || start.stop !== 0 || start.event.depart === null) continue;
			const plannedArrive = end.service + end.plan.arrive[end.stop]!;
			const plannedDepart = start.service + start.plan.depart[0]!;
			result.push({
				train, line: end.plan.line, from: end.plan.name, to: start.plan.name,
				plannedSeconds: Math.round(diff(ofDay(plannedDepart), ofDay(plannedArrive)) / 1000),
				actualSeconds: Math.round((start.event.depart - end.event.arrive) / 1000),
				departLate: Math.round((start.departLate ?? 0) / 1000),
			});
		}
	}
	return result;
}

// ---- Any world: stops by the platforms' own positions, plans by the routes' own platform lists ----

/** A block position as Minecraft packs it in a long (BlockPos.asLong): x 26 bits, z 26 bits, y 12 bits */
export function unpackPos(packed: string | number): {x: number, y: number, z: number} {
	const v = BigInt(packed);
	return {x: Number(BigInt.asIntN(64, v) >> 38n), y: Number(BigInt.asIntN(64, v << 52n) >> 52n), z: Number(BigInt.asIntN(64, v << 26n) >> 38n)};
}

export interface PlatformSpot {id: string, name: string, x1: number, z1: number, x2: number, z2: number, y: number}

export function platformSpots(snapshot: any): PlatformSpot[] {
	return snapshot.platforms.map((p: any) => {
		const a = unpackPos(p.pos_1), b = unpackPos(p.pos_2);
		return {id: String(p.id), name: String(p.name), x1: a.x + 0.5, z1: a.z + 0.5, x2: b.x + 0.5, z2: b.z + 0.5, y: a.y};
	});
}

const distanceToSpot = (x: number, z: number, p: PlatformSpot) => {
	const dx = p.x2 - p.x1, dz = p.z2 - p.z1, square = dx * dx + dz * dz;
	const t = square ? Math.max(0, Math.min(1, ((x - p.x1) * dx + (z - p.z1) * dz) / square)) : 0;
	return Math.hypot(x - p.x1 - t * dx, z - p.z1 - t * dz);
};

/** When each train stood still with its head at a platform (within a few blocks of it), as stopEvents for any world */
export function stopEventsAt(samples: TrainsAt[], spots: PlatformSpot[]): StopEvent[] {
	const events: StopEvent[] = [];
	const open = new Map<string, StopEvent>();
	for (const sample of samples) {
		const here = new Set<string>();
		for (const train of sample.trains) {
			if (!train.onRoute || train.speed >= 0.005) continue;
			let best: PlatformSpot | null = null, bestDistance = 4;
			for (const spot of spots) {
				const distance = distanceToSpot(train.x, train.z, spot);
				if (distance < bestDistance && Math.abs(train.y - spot.y) < 4) {
					best = spot;
					bestDistance = distance;
				}
			}
			if (!best) continue;
			here.add(train.id);
			const current = open.get(train.id);
			if (!current || current.platform !== best.id) {
				if (current) current.depart = sample.millis;
				const event: StopEvent = {train: train.id, line: 0, station: -1, track: -1, platform: best.id, arrive: sample.millis, depart: null};
				events.push(event);
				open.set(train.id, event);
			}
			noteTrip(open.get(train.id)!, train);
		}
		for (const [id, event] of open) {
			if (!here.has(id)) {
				event.depart = sample.millis;
				open.delete(id);
			}
		}
	}
	return events;
}

/** A route's planned times off its timetable screen, its stops as its own platform list (any world) */
export async function readRoutePlan(saved: any): Promise<RoutePlan | null> {
	await openTimetable(label(String(saved.name)));
	const rows = (await list("TimetableScreen.servicesList")).rows;
	if (!rows.length) {
		await closeAll();
		return null;
	}
	await selectService(rows[0].label);
	const texts = (await state()).texts;
	const headArrive = texts.find(t => t.text === "到"), headDepart = texts.find(t => t.text === "发");
	const platformIds = (saved.platform_ids as any[]).map(String);
	if (!headArrive || !headDepart) {
		await closeAll();
		return null;
	}
	// One row a stop, in order, under the headings: the arrival column's times by row height
	const rowYs = [...new Set(texts.filter(t => t.y > headArrive.y && t.y < 300 && Math.abs(t.x - headArrive.x) < 8 && seconds(t.text) !== null).map(t => t.y))].sort((a, b) => a - b);
	const allRowYs = [...new Set(texts.filter(t => t.y > headArrive.y && t.y < 300 && t.x < headArrive.x - 100).map(t => t.y))].sort((a, b) => a - b).slice(0, platformIds.length);
	const at = (x: number, y: number) => texts.find(t => Math.abs(t.y - y) < 3 && Math.abs(t.x - x) < 8 && seconds(t.text) !== null);
	const base = seconds(rows[0].label)!;
	const offset = (t: {text: string} | undefined) => t ? ((seconds(t.text)! - base + 86400) % 86400) * 1000 : null;
	const stationRowYs = [...new Set(texts.filter(t => t.y > headArrive.y && t.y < 300 && t.x < headArrive.x - 100 && /站/.test(t.text)).map(t => t.y))].sort((a, b) => a - b);
	const ys = stationRowYs.length === platformIds.length ? stationRowYs : allRowYs.length === platformIds.length ? allRowYs : rowYs;
	await closeAll();
	return {
		routeId: String(saved.id), name: String(saved.name), line: 0,
		stops: platformIds.map(id => ({station: -1, track: -1, platform: id})),
		arrive: ys.map(y => offset(at(headArrive.x, y))), depart: ys.map(y => offset(at(headDepart.x, y))),
		services: (saved.timetable_services as number[]).slice().sort((a, b) => a - b),
	};
}
