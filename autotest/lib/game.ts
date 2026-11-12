// The game as the tests see it: one test client and one test server, each driven through MTM_TestKit's HTTP driver.
// C:/gtmp/fast/ui-test.sh starts both and passes where their endpoint files are; see MTR-自动化游戏内测试设计.md.
import {readFileSync, writeFileSync, mkdirSync} from "fs";
import {join} from "path";

type Json = any;

/**
 * JSON.parse, keeping integers too large for a double (MTR's 64-bit ids) as their exact digits in a string, so ids
 * compare correctly. Compare ids with String(id).
 */
export function parseJson(text: string): Json {
	return (JSON.parse as any)(text, (_key: string, value: unknown, context?: {source?: string}) =>
		typeof value === "number" && Number.isInteger(value) && !Number.isSafeInteger(value) && context?.source ? context.source : value);
}

export class Driver {
	constructor(private readonly side: string, private readonly endpointFile: string) {
	}

	private endpoint(): {port: number, token: string} {
		return JSON.parse(readFileSync(this.endpointFile, "utf8"));
	}

	async request(path: string, body: Json = {}, query: Record<string, string> = {}): Promise<Response> {
		const {port, token} = this.endpoint();
		const search = new URLSearchParams(query).toString();
		return fetch(`http://127.0.0.1:${port}${path}${search ? "?" + search : ""}`, {
			method: "POST",
			headers: {"X-TestKit-Token": token, "Content-Type": "application/json"},
			body: JSON.stringify(body),
		});
	}

	async call(path: string, body: Json = {}, query: Record<string, string> = {}): Promise<Json> {
		const response = await this.request(path, body, query);
		const json = parseJson(await response.text());
		if (!json.ok) {
			throw new Error(`${this.side} ${path} ${JSON.stringify(body)}: ${json.error}\n${json.stack || ""}`);
		}
		return json.result;
	}
}

const env = (name: string) => {
	const value = process.env[name];
	if (!value) {
		throw new Error(`${name} is not set: run the tests with C:/gtmp/fast/ui-test.sh`);
	}
	return value;
};

export const client = new Driver("client", env("MTM_CLIENT_ENDPOINT"));
export const server = new Driver("server", env("MTM_SERVER_ENDPOINT"));

/** The ids of what the fixture network built (TestKit's Fixture.java) */
export const fixture: Record<string, any> = parseJson(readFileSync(env("MTM_FIXTURE"), "utf8")).result;
export const FIXTURE_Y: number = fixture.y;

export async function sleep(ms: number) {
	await new Promise(resolve => setTimeout(resolve, ms));
}

/** Calls check until it returns something truthy (and returns that), or fails after timeout with the last error. */
export async function waitFor<T>(what: string, check: () => Promise<T | undefined | null | false>, timeout = 10_000, interval = 100): Promise<T> {
	const end = Date.now() + timeout;
	let lastError: unknown;
	while (Date.now() < end) {
		try {
			const result = await check();
			if (result) {
				return result;
			}
		} catch (e) {
			lastError = e;
		}
		await sleep(interval);
	}
	throw new Error(`timed out waiting for ${what}` + (lastError ? `: ${lastError}` : ""));
}

// ---- The client's screen ----

export interface Widget {
	id: string, type: string, text: string, x: number, y: number, width: number, height: number,
	visible: boolean, active: boolean, focused: boolean, value?: string | boolean | number
}

export interface ListRow {
	index: number, dataId: number, name: string, label: string, header: boolean, type: string, x: number, y: number, height: number
}

export interface DashboardListState {
	id: string, x: number, y: number, width: number, height: number, scroll: number, total: number, rowsShown: number, rows: ListRow[], error?: string
}

/** A text the font drew in the last frame, where (screen coordinates) and in what colour (#rrggbb) */
export interface DrawnText {text: string, x: number, y: number, color: string}

export interface ScreenState {
	guiScale: number, guiWidth: number, guiHeight: number, texts: DrawnText[],
	screen?: string | null, screenName?: string, title?: string, widgets: Widget[], lists: DashboardListState[]
}

export const state = (): Promise<ScreenState> => client.call("/state");

export async function screenName(): Promise<string | null> {
	const current = await state();
	return current.screen ? current.screenName! : null;
}

/** Waits until the open screen is the MTR screen class (simple name), or no screen when name is null. */
export async function waitScreen(name: string | null, timeout = 10_000): Promise<ScreenState> {
	return waitFor(`screen ${name}`, async () => {
		const current = await state();
		return (name === null ? !current.screen : current.screenName === name) ? current : undefined;
	}, timeout);
}

/** Waits until the font draws a text matching the pattern (text a screen draws itself, not a widget's) and returns it. */
export async function waitText(pattern: RegExp, timeout = 10_000): Promise<DrawnText> {
	return waitFor(`text ${pattern} on the screen`, async () => (await state()).texts.find(t => pattern.test(t.text)), timeout);
}

export async function widget(id: string): Promise<Widget> {
	const current = await state();
	const found = current.widgets.find(w => w.id === id);
	if (!found) {
		throw new Error(`no widget ${id} on ${current.screenName}; there are: ${current.widgets.map(w => w.id).join(", ")}`);
	}
	return found;
}

export const click = (target: string | {text: string} | {x: number, y: number}, extra: Json = {}) =>
	client.call("/click", {...(typeof target === "string" ? {id: target} : target), ...extra});
export const move = (x: number, y: number) => client.call("/move", {x, y});
export const scroll = (x: number, y: number, amount: number) => client.call("/scroll", {x, y, amount});
export const type = (id: string | null, text: string, clear = false) => client.call("/type", id ? {id, text, clear} : {text});
export const key = (name: string, modifiers: {ctrl?: boolean, shift?: boolean, alt?: boolean} = {}) => client.call("/key", {key: name, ...modifiers});
export const closeScreen = () => client.call("/close");

/** Closes screens until none is open (a screen may go back to its parent first). */
export async function closeAll() {
	for (let i = 0; i < 6; i++) {
		if (!(await state()).screen) {
			return;
		}
		await closeScreen();
		await sleep(100);
	}
	await waitScreen(null, 3000);
}

export async function list(id: string): Promise<DashboardListState> {
	const current = await state();
	const found = current.lists.find(l => l.id === id);
	if (!found) {
		throw new Error(`no list ${id} on ${current.screenName}`);
	}
	if (found.error) {
		throw new Error(`list ${id} could not be read: ${found.error}`);
	}
	return found;
}

const TAB_TYPES: Record<string, string> = {buttonTabStations: "Station", buttonTabRoutes: "Route", buttonTabDepots: "Depot"};

/** Clicks a dashboard tab and waits until its list shows that kind of data (the list is filled on the next frame). */
export async function switchTab(tab: "buttonTabStations" | "buttonTabRoutes" | "buttonTabDepots", _type?: string) {
	await click(`DashboardScreen.${tab}`);
	await waitFor(`the dashboard list to show ${TAB_TYPES[tab]}s`, async () => {
		const rows = (await list("DashboardScreen.dashboardList")).rows;
		// The route list puts a heading row before each line's routes; those don't count
		return rows.length > 0 && rows.every(r => r.type === TAB_TYPES[tab] || r.header);
	});
}

/** Moves the mouse over the row whose label contains text, so the list puts its row buttons there; returns the row. */
export async function hoverRow(listId: string, text: string, nth = 0): Promise<ListRow> {
	let current = await list(listId);
	// A folded line's header ("[-] 环线 Loop Line (2)") holds its routes' name too, and has no buttons: rows that aren't
	// headers first; of several rows with the name (routes named the same), the nth
	const find = (state: DashboardListState) => {
		const matching = state.rows.filter(r => r.label.includes(text) || r.name.includes(text));
		const rows = matching.filter(r => !r.header);
		return (rows.length ? rows : matching)[Math.min(nth, Math.max(0, rows.length - 1))];
	};
	let row = find(current);
	// Rows below the list's visible part: scroll it down with the mouse wheel, as a player does, until the row shows
	if (!row && current.rows.length && current.total > current.rows.length) {
		const anchor = current.rows[0];
		for (let i = 0; i < 40 && !row; i++) {
			const before = current.rows.map(r => r.label).join("\n");
			await scroll(anchor.x + 10, anchor.y + anchor.height / 2, -1);
			await sleep(50);
			current = await list(listId);
			row = find(current);
			if (!row && current.rows.map(r => r.label).join("\n") === before) break;
		}
	}
	if (!row) {
		throw new Error(`no row ${text} in ${listId}: ${current.rows.map(r => r.label).join(", ")}`);
	}
	await move(row.x + 10, row.y + row.height / 2);
	return row;
}

// ---- The player ----

export async function player(): Promise<string> {
	return (await client.call("/ping")).player;
}

/** Runs a command on the server as the console (permission level 4). */
export async function serverCommand(command: string): Promise<{result: number, output: string[]}> {
	return server.call("/command", {command});
}

/** Empties the player's inventory, gives the items and selects the first slot. */
export async function giveOnly(...items: string[]) {
	const name = await player();
	await serverCommand(`clear ${name}`);
	for (const item of items) {
		await serverCommand(`give ${name} ${item}`);
	}
	await client.call("/slot", {slot: 0});
	await waitFor("items in the hotbar", async () => true, 300);
	await sleep(300);
}

export async function teleport(x: number, y: number, z: number) {
	await serverCommand(`tp ${await player()} ${x} ${y} ${z}`);
	await sleep(300);
}

export const useItem = () => client.call("/use-item");
export const useOn = (x: number, y: number, z: number, face = "up") => client.call("/use", {x, y, z, face});

/** Opens the railway dashboard with the dashboard item, as a player would. */
export async function openDashboard(): Promise<ScreenState> {
	await closeAll();
	await giveOnly("mtr:dashboard");
	await useItem();
	const screen = await waitScreen("DashboardScreen");
	// The search box keeps what was typed while the game runs (lib/screens.ts searches routes): start each time unfiltered
	if ((await widget("DashboardScreen.dashboardList.textFieldSearch").catch(() => null))?.value) {
		await type("DashboardScreen.dashboardList.textFieldSearch", "", true);
		return waitScreen("DashboardScreen");
	}
	return screen;
}

// ---- Chat and logs ----

export interface ChatMessage {seq: number, text: string, overlay: boolean}

export async function chatSince(since: number): Promise<{last: number, messages: ChatMessage[]}> {
	return client.call("/chat-log", {}, {since: String(since)});
}

export const chat = (text: string) => client.call("/chat", {text});
export const suggest = async (text: string): Promise<string[]> => (await client.call("/suggest", {text})).suggestions;

export interface LogEvent {seq: number, level: string, logger: string, thread: string, message: string, thrown?: string}

export async function logsSince(driver: Driver, since: number): Promise<{last: number, events: LogEvent[]}> {
	return driver.call("/log", {}, {since: String(since)});
}

// Lines every run logs that say nothing about the change under test
const KNOWN_NOISE = [
	/No data fixer registered/,
	/Error loading class: team\/dovecotmc/,
	/MTM_TestKit\] loaded/,
	/Received passengers for unknown entity/,
	/Ignoring player info update for unknown player/,
];

/** Errors (and warnings with an exception) logged since a mark, apart from known noise. */
export async function newProblems(driver: Driver, since: number): Promise<LogEvent[]> {
	const {events} = await logsSince(driver, since);
	return events.filter(e => (e.level === "ERROR" || e.level === "FATAL" || e.thrown) && !KNOWN_NOISE.some(pattern => pattern.test(e.message)));
}

// ---- Data ----

export const serverData = (): Promise<Json> => server.call("/snapshot");
export const clientData = (): Promise<Json> => client.call("/client-data");

export function byName(items: Json[], name: string): Json {
	const found = items.find(item => String(item.name).includes(name));
	if (!found) {
		throw new Error(`nothing named ${name} among ${items.map(item => item.name).join(", ")}`);
	}
	return found;
}

// ---- Evidence ----

/** Saves a screenshot (whole window, or one widget) into this run's results and returns its path. */
export async function screenshot(name: string, widgetId?: string): Promise<string> {
	const response = await client.request("/screenshot", {}, widgetId ? {id: widgetId} : {});
	if (response.headers.get("content-type") !== "image/png") {
		throw new Error(`screenshot failed: ${await response.text()}`);
	}
	const folder = join(process.env.MTM_RESULTS || "test-results", "screenshots");
	mkdirSync(folder, {recursive: true});
	const path = join(folder, `${name}.png`);
	writeFileSync(path, Buffer.from(await response.arrayBuffer()));
	return path;
}
