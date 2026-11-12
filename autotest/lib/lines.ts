// The six track-layout lines (TestKit's Lines.java, MTM_FIXTURE_NAME=lines): what the fixture built, the server's clock
// in the timetables' time zone, timetables put in through the timetable screen, and the server's own log.
import {readFileSync, existsSync} from "fs";
import {click, fixture, hoverRow, list, server, type, waitFor, waitScreen, widget} from "./game";
import {addService, closeAll, enableTimetable, openGroupFor, openTimetable, refreshDepotPath, selectService} from "./screens";

export interface LineRoute {id: string, name: string}
export interface Sensor {x: number, y: number, z: number, trigger: string, kind: number}
export interface Line {layout: number, name: string, z: number, depot: string, depotName: string, routes: LineRoute[], stations: string[], sidings: string[], stationX: number[], sensors?: Sensor[], depotBox?: number[], tails?: number[][]}

export const lines: Line[] = fixture.lines || [];

/** A route's or depot's row label in the dashboard ("name|English" shown as "name English") */
export const label = (name: string) => name.replace("|", " ");

export interface Clock {millis: number, zone: string, time: string, dayStartHour: number}
export const clock = (): Promise<Clock> => server.call("/clock");

/** hh:mm:ss some minutes after a time of day, wrapping at midnight */
export function later(time: string, minutes: number): string {
	const [h, m, s] = time.split(":").map(Number);
	const total = ((h * 3600 + m * 60 + s + minutes * 60) % 86400 + 86400) % 86400;
	const minute = Math.ceil(total / 60) * 60 % 86400;
	return [Math.floor(minute / 3600), Math.floor(minute / 60) % 60, 0].map(v => String(v).padStart(2, "0")).join(":");
}

/** hh:mm:ss some seconds after a time of day */
export function plusSeconds(time: string, seconds: number): string {
	const [h, m, s] = time.split(":").map(Number);
	const total = ((h * 3600 + m * 60 + s + seconds) % 86400 + 86400) % 86400;
	return [Math.floor(total / 3600), Math.floor(total / 60) % 60, total % 60].map(v => String(v).padStart(2, "0")).join(":");
}

const interval = (seconds: number) => [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60].map(v => String(v).padStart(2, "0")).join(":");

/**
 * When a route's services leave (its first stop), as scheduleAll puts them in: lines 1–6 every 6 minutes from both ends,
 * the short routes of lines 5 and 6 between the long ones; line 7 every 30 seconds; lines 8 and 9 every 6 minutes, each
 * route a minute or so after the one it carries on from, so a train running through waits a little at the boundary;
 * line 10 every 150 seconds.
 */
export function serviceTimes(line: Line, route: LineRoute, start: string): {first: string, headway: number, count: number} {
	const name = route.name.split("|")[0];
	if (line.layout === 7) return {first: later(start, name.includes("下行") ? 0 : 1), headway: 30, count: 20};
	if (line.layout === 8 || line.layout === 9) {
		const offsets: Record<string, number> = line.layout === 8
			? {A上行: 0, B上行: 1, B下行: 1, A下行: 2}
			: {A上行: 0, B上行: 1, C上行: 3, C下行: 1, B下行: 2, A下行: 3};
		const key = Object.keys(offsets).find(k => name.endsWith(k))!;
		return {first: later(start, offsets[key]), headway: 360, count: 7};
	}
	// Line 10 (站后双折返): every two and a half minutes, so trains come in at 五站 while the one before is still turning
	if (line.layout === 10) return {first: later(start, name.includes("下行") ? 1 : 0), headway: 150, count: 24};
	const short = name.includes("短");
	const down = name.includes("下行");
	// Line 2's express two minutes after 上行, so it comes through 三站 while the run test samples
	return {first: later(start, (down ? 1 : 0) + (short ? 3 : 0) + (name.includes("快车") ? 2 : 0)), headway: 360, count: 15};
}

/** The departure times of a route's services as hh:mm:ss, in the timetable's own time zone */
export function serviceLabels(line: Line, route: LineRoute, start: string): string[] {
	const times = serviceTimes(line, route, start);
	return Array.from({length: times.count + 1}, (_, k) => plusSeconds(times.first, k * times.headway));
}

/**
 * Puts services in through the timetable screen, as a player would: every route of every line, as serviceTimes says.
 * The first services leave a few minutes from now.
 */
export async function scheduleAll(start: string) {
	for (const line of lines) {
		for (const route of line.routes) {
			const times = serviceTimes(line, route, start);
			await openTimetable(label(route.name));
			await enableTimetable();
			await addService(`${times.first} + ${times.count} * ${interval(times.headway)}`);
			await closeAll();
		}
	}
}

/** A service running on as another route's: which, and where it leaves and joins (stop numbers; -1 is the last stop) */
export interface ThroughLink {from: string, fromName: string, fromService: string, to: string, toName: string, toService: string, leaveStop: number, joinStop: number}

/** Which services of lines 8 and 9 run through: line 8 every other one, line 9 all, A→B→C and back C→B→A */
export function throughLinks(start: string): ThroughLink[] {
	const links: ThroughLink[] = [];
	for (const line of lines.filter(l => l.layout === 8 || l.layout === 9)) {
		const route = (suffix: string) => line.routes.find(r => r.name.split("|")[0].endsWith(suffix))!;
		const pairs: [string, string, number, number][] = line.layout === 8
			? [["A上行", "B上行", -1, 0], ["B下行", "A下行", -1, 0]]
			: [["A上行", "B上行", -1, 1], ["B上行", "C上行", -1, 0], ["C下行", "B下行", -1, 0], ["B下行", "A下行", 1, 0]];
		for (const [fromSuffix, toSuffix, leaveStop, joinStop] of pairs) {
			const from = route(fromSuffix), to = route(toSuffix);
			const fromTimes = serviceLabels(line, from, start), toTimes = serviceLabels(line, to, start);
			fromTimes.forEach((time, k) => {
				if (line.layout === 8 && k % 2) return;
				links.push({from: String(from.id), fromName: from.name, fromService: time, to: String(to.id), toName: to.name, toService: toTimes[k], leaveStop, joinStop});
			});
		}
	}
	return links;
}

/**
 * Sets the links through the timetable screen, as a player would: the service, "下一段" and the other route, its service,
 * and "转出站"/"接入站" clicked on to the boundary stop where the train leaves or joins part of the way along.
 */
export async function setThroughLinks(links: ThroughLink[]) {
	const byRoute = new Map<string, ThroughLink[]>();
	links.forEach(link => byRoute.set(link.from, [...(byRoute.get(link.from) || []), link]));
	for (const routeLinks of byRoute.values()) {
		await openTimetable(label(routeLinks[0].fromName));
		for (const link of routeLinks) {
			await selectService(link.fromService);
			await click("TimetableScreen.buttonLinkRoute");
			await waitScreen("DashboardListSelectorScreen");
			// Its search box brings the route up whatever group it is folded in
			await type("DashboardListSelectorScreen.availableList.textFieldSearch", link.toName.split("|")[0], true);
			// The list is filtered on the next frame: wait for it to show only what the search finds
			const wanted = link.toName.split("|")[0];
			await waitFor("the search to filter the list", async () => (await list("DashboardListSelectorScreen.availableList")).rows.every(r => r.label.startsWith("[") || r.label.includes(wanted)), 5000).catch(() => undefined);
			await openGroupFor("DashboardListSelectorScreen.availableList", label(link.toName));
			await hoverRow("DashboardListSelectorScreen.availableList", label(link.toName));
			await click("DashboardListSelectorScreen.availableList.buttonAdd");
			await click("DashboardListSelectorScreen.buttonDone");
			await waitScreen("TimetableScreen");
			// The button shows the route chosen, shortened to fit: it no longer shows "无" (none)
			await waitFor("the next route on the button", async () => (await widget("TimetableScreen.textFieldLinkService")).visible);
			await type("TimetableScreen.textFieldLinkService", link.toService, true);
			if (link.leaveStop > 0) await click("TimetableScreen.buttonLinkStop");
			if (link.joinStop > 0) await click("TimetableScreen.buttonLinkEntry");
		}
		await closeAll();
	}
}

/** "刷新路径" in every line's depot screen */
export async function refreshAllDepots() {
	for (const line of lines) {
		await refreshDepotPath(label(line.depotName));
	}
}

/** The server's console output (its log file), for messages the game prints rather than logs as warnings */
export function serverLog(): string {
	const file = process.env.MTM_SERVER_LOG || "C:/gtmp/ui/server/server.log";
	return existsSync(file) ? readFileSync(file, "utf8") : "";
}

/** Waits until the server says it finished finding the depot's paths, and returns what it said about that depot */
export async function depotPathMessages(line: Line, since: number): Promise<string[]> {
	const english = line.depotName.split("|")[1];
	await waitFor(`path generation of ${english}`, async () => serverLog().slice(since).includes(`Finished path generation for`) &&
		serverLog().slice(since).split("\n").some(l => l.includes("Finished path generation") && l.includes(english)), 60_000, 1000);
	const routeIds = line.routes.map(r => String(r.id));
	return serverLog().slice(since).split("\n").filter(l => l.includes(english) || routeIds.some(id => l.includes(id)) || l.includes(String(line.depot)));
}

export interface TrainSample {id: string, siding: string, depot: string, x: number, y: number, z: number, speed: number, onRoute: boolean, doors: number, progress: number, route?: string, trip?: number}
export interface TrainsAt {millis: number, tick: number, trains: TrainSample[]}
export const trainsNow = (): Promise<TrainsAt> => server.call("/trains");

/** Moved to screens.ts, where opening a route's screens uses it too */
export {openGroupFor};
