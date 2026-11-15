// U215 终点站的到和发、U216 运行图上的折返（高）: in the web editor's summary timetable a route's first stop has an extra
// "到" row and its last stop an extra "发" row, worked out from the train's turn (fn.terminalNow): on a turn at the same
// platform the last stop's "发" is the next service's departure and the next service's first "到" the arrival before;
// those cells are in italics and their tooltip says how they came about; a service with no following one counts its own
// stop only. The running diagram draws the same turns: a level line at a single-platform terminus, a dashed line from one
// platform's arrival to the other's departure (换站台), the turn-back track's wait, and those only guessed ("推测", no duty
// to say) drawn faint.
// Needs the six lines' timetables: this file belongs in autotest/cases-lines, named z-terminals.spec.ts and added to the
// observe project's list in playwright.config.ts. It only reads the page (no cell is clicked, so nothing is edited),
// so it may run beside run.spec.ts.
// Covered here, on lines 1–6 and 10, both ways: the extra rows' cells on every service, italics, their tooltips, "推测"
// with its faint class, the same-platform turns' "发" and "到" equal to the other service's departure and arrival, the
// kinds of turn the lines have (the same pairing the diagram draws), and that a line run by its depot's duties has no
// guessed turns. Each line's diagram is attached as a screenshot for review.
// Not covered: the strokes on the canvas themselves (level, dashed, faint: the page doesn't expose where it drew them),
// which the screenshots are for; a turn chosen by hand ("（指定）"), which the fixture has none of.
import {test, expect} from "../lib/test";
import type {Page} from "@playwright/test";
import {Line, lines} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

const LAYOUTS = [1, 2, 3, 4, 5, 6, 10];
/** A line whose trains run by its depot's duties (z-dispatch.spec.ts takes its trains first) */
const WITH_DUTIES = 3;

interface Cell {svc: string, stop: number, kind: string, text: string, title: string, classes: string, italic: boolean}
interface RouteTable {route: string, name: string, services: number, last: number, cells: Cell[]}
type Turn = "same" | "change" | "hold" | "loop" | "none" | "other";

/** The kind of turn, by the words terminalText uses (oldEditor.js terminalText) */
function turnOf(title: string): Turn {
	if (/没有接续的班次/.test(title)) return "none";
	if (/折返线上等/.test(title)) return "hold";
	if (/^换到 .+，共 /.test(title)) return "change";
	if (/环线续跑/.test(title)) return "loop";
	if (/^在 .+ 折返，停 /.test(title)) return "same";
	return "other";
}

/** Opens the line's row in the timetable layer's tree, so its routes show */
async function openLine(page: Page, layout: number) {
	const row = page.locator("#tree .node[data-line]:not([data-view])").filter({hasText: new RegExp(`^\\W*${layout}号线`)}).first();
	await expect(row, `line ${layout} in the tree`).toBeVisible({timeout: 20_000});
	if (await row.getAttribute("aria-expanded") !== "true") await row.click();
	return row;
}

/** A route's summary table (its 汇总时刻表 chip), read without clicking a cell; null if it has no services that day */
async function readRoute(page: Page, layout: number, route: {id: string, name: string}): Promise<RouteTable | null> {
	const id = String(route.id);
	const chip = page.locator(`#tree .node[data-route="${id}"][data-view="table"]`);
	if (await chip.count() === 0) {
		await openLine(page, layout);
		const row = page.locator(`#tree .node[data-route="${id}"]:not([data-view])`);
		await expect(row, `${route.name} in the tree`).toBeVisible();
		await row.click();
	}
	await chip.click();
	const table = page.locator("[data-summary-table]");
	await expect(table.locator(".tt-tools b").first()).toContainText(route.name.split("|")[0], {timeout: 20_000});
	const heads = table.locator(`th.svc-head[data-svc^="${id}@"]`);
	const empty = table.locator(".table-empty");
	await expect(heads.first().or(empty)).toBeVisible({timeout: 20_000});
	if (await empty.count()) return null;
	const cells: Cell[] = await table.locator("table.tt td[data-svc]").evaluateAll(tds => tds.map(td => {
		const el = td as HTMLElement;
		return {
			svc: el.dataset.svc || "", stop: Number(el.dataset.stop), kind: el.dataset.kind || "", text: (el.textContent || "").trim(),
			title: el.getAttribute("title") || "", classes: el.className, italic: getComputedStyle(el).fontStyle === "italic",
		};
	}));
	return {route: id, name: route.name, services: await heads.count(), last: Math.max(...cells.map(c => c.stop)), cells};
}

test("U215 U216 终点站的到和发、折返的种类 @lines @U215 @U216", async ({page}, testInfo) => {
	test.setTimeout(8 * 60_000);
	const chosen = LAYOUTS.map(layout => lines.find(l => l.layout === layout)).filter(Boolean) as Line[];
	expect(chosen.length, "the six lines' fixture (MTM_FIXTURE_NAME=lines)").toBeGreaterThan(0);
	const {errors} = await openNextEditor(page, "timetable");

	const kinds = new Map<Turn, number>();
	const report: string[] = [];
	let guessedWithDuties = 0, pairedWithDuties = 0;
	for (const line of chosen) {
		const tables: RouteTable[] = [];
		for (const route of line.routes) {
			const table = await readRoute(page, line.layout, route);
			if (table) tables.push(table);
		}
		if (!tables.length) {
			report.push(`line ${line.layout}: no services today`);
			continue;
		}
		const firstDeps = new Set(tables.flatMap(t => t.cells.filter(c => c.stop === 0 && c.kind === "dep").map(c => c.text)));
		const lastArrs = new Set(tables.flatMap(t => t.cells.filter(c => c.stop === t.last && c.kind === "arr").map(c => c.text)));
		for (const table of tables) {
			const where = `line ${line.layout} ${table.name}`;
			const derived = table.cells.filter(c => c.kind === "derived");
			const arrivals = derived.filter(c => c.stop === 0), departures = derived.filter(c => c.stop === table.last);
			// U215: the extra rows, one cell per service (a service starting or ending part of the way shows "—" instead)
			expect(arrivals.length, `${where}: a 到 at the first stop`).toBeGreaterThan(0);
			expect(departures.length, `${where}: a 发 at the last stop`).toBeGreaterThan(0);
			expect(arrivals.length, `${where}: at most one 到 per service`).toBeLessThanOrEqual(table.services);
			expect(departures.length, `${where}: at most one 发 per service`).toBeLessThanOrEqual(table.services);
			// Soft, and once per table, so the other checks still run when the style is missing on every cell (0.83.6: the
			// new page's legacy-theme.css sets td.derived to font-style normal)
			const upright = derived.filter(c => !c.italic);
			expect.soft(upright.map(c => `${c.svc} stop ${c.stop}`), `${where}: the worked out 到/发 cells in italics (${upright.length} of ${derived.length} not)`).toEqual([]);
			for (const cell of derived) {
				expect(cell.text, `${where} ${cell.svc}: a time`).toMatch(/^\d{2}:\d{2}(:\d{2})?$/);
				const turn = turnOf(cell.title.split("\n")[0]);
				expect(turn, `${where} ${cell.svc}: the tooltip says how it came about: ${cell.title}`).not.toBe("other");
				if (turn !== "none") {
					expect(cell.title, `${where} ${cell.svc}: which service it follows`).toMatch(cell.stop === 0 ? /跟着上一班，改那一班的时刻/ : /跟着下一班，改那一班的时刻/);
				}
				expect(cell.title.includes("（推测）"), `${where} ${cell.svc}: a guessed turn drawn faint (class inferred)`).toBe(cell.classes.split(" ").includes("inferred"));
				kinds.set(turn, (kinds.get(turn) || 0) + 1);
				if (line.layout === WITH_DUTIES && turn !== "none") {
					pairedWithDuties++;
					if (cell.title.includes("（推测）")) guessedWithDuties++;
				}
				// Turned at the same platform: the last stop's 发 is the next service's departure, the first stop's 到 the
				// service before's arrival
				if (turn === "same" && cell.stop === table.last) {
					expect(firstDeps.has(cell.text), `${where} ${cell.svc}: 发 ${cell.text} is a departure from the first stop`).toBe(true);
				}
				if (turn === "same" && cell.stop === 0) {
					expect(lastArrs.has(cell.text), `${where} ${cell.svc}: 到 ${cell.text} is an arrival at the last stop`).toBe(true);
				}
			}
			report.push(`${where}: ${table.services} services, ${arrivals.length} 到 / ${departures.length} 发 worked out`);
		}

		// U216: the line's diagram, with the same turns drawn, kept for review
		const lineRow = await openLine(page, line.layout);
		const key = (await lineRow.getAttribute("data-line"))!;
		await page.locator(`#tree .node[data-line=${JSON.stringify(key)}][data-view="diagram"]`).click();
		const canvas = page.locator('canvas[aria-label="运行图"]');
		await expect(canvas).toBeVisible({timeout: 20_000});
		await page.waitForTimeout(500);
		await testInfo.attach(`line ${line.layout} running diagram`, {body: await canvas.screenshot(), contentType: "image/png"});
	}
	report.push(`turns: ${[...kinds].map(([kind, count]) => `${kind} ${count}`).join(", ")}`);
	await testInfo.attach("terminal cells", {body: report.join("\n")});

	expect([...kinds.keys()].filter(kind => kind !== "none").length, "turns worked out on these lines").toBeGreaterThan(0);
	// The checklist's main case; reported on its own if these layouts plan none
	expect.soft(kinds.get("same") || 0, "a turn at the same platform somewhere (line 1 or 4 turning in front of 五站)").toBeGreaterThan(0);
	// U216: a line whose depot runs it by duties draws its turns by them, none guessed
	expect(pairedWithDuties, `line ${WITH_DUTIES}: turns paired`).toBeGreaterThan(0);
	expect.soft(guessedWithDuties, `line ${WITH_DUTIES}: no turn guessed where the duties say`).toBe(0);
	expect(errors).toEqual([]);
});
