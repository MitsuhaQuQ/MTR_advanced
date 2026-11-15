// U355 手改归属生效（高）: on the ownership layer's real map, right-click a section owned by one line, "归属线路…", tick
// the other line too and apply: the server works the owners out again and the section is owned by both (striped); "对所有
// 线路开放" opens it to every line (grey dashes); "恢复自动归属" gives it back to the boundaries and platforms.
// The basic fixture has no pathfinding boundary, so every section is owned by both lines (测试线, 测试线回程). The test
// first makes the rail from the West siding to 甲站's platform (x -110 → -90, run only by the West depot's way out, which
// serves 测试线) owned by 测试线 alone, then ticks 测试线回程 as well, opens it to every line, and gives it back.
// Changes the world while it runs (four applies); the last apply puts it back as found (no owners set by hand).
// Covered here: the right-click menu on the real map's rail, the "区段归属" panel, the hover text, the draft's
// ownerOverrides, the server log line after each apply, the client's copy of the network settings and of the owners.
// Not covered: the drawing itself (stripes, dark edge, grey dashes: a screenshot is attached), that 测试线回程 then finds
// paths and duties through the section (the fixture has no other track to compare with), the owners after a restart.
import {test, expect} from "../lib/test";
import {waitFor} from "../lib/game";
import {serverLog} from "../lib/lines";
import {openNextEditor} from "../lib/editor";
import type {Page} from "@playwright/test";

// ---- The editor's draft and endpoints (shared shape with the other owner-layer specs) ----

/** An editor endpoint called from the page itself, with the key and origin the page has */
async function api(page: Page, path: string, body?: unknown): Promise<any> {
	return page.evaluate(async ([path, body]) => {
		const key = sessionStorage.getItem("mtr-editor-key") || "";
		const response = await fetch("/api/" + path, body === undefined
			? {headers: {"X-MTR-Editor-Key": key}, cache: "no-store"}
			: {method: "POST", headers: {"X-MTR-Editor-Key": key, "Content-Type": "application/json"}, body: JSON.stringify(body), cache: "no-store"});
		return response.json();
	}, [path, body] as const);
}

const network = (page: Page) => api(page, "network", {mode: "TRAIN", fresh: false, version: -1});
const isEmptyDraft = (draft: any) => !draft || Object.values(draft).every(value => value === null || value === undefined
	|| (Array.isArray(value) ? value.length === 0 : typeof value === "object" ? Object.keys(value as object).length === 0 : true));
const draftButton = (page: Page) => page.locator("header:has(h1)").getByRole("button", {name: /^(没有草稿|草稿 \d+ 项|正在应用…)$/});
const review = (page: Page) => page.getByRole("dialog", {name: "草稿的改动"});

/** Empties the draft through the review panel, if the server has one */
async function clearDraft(page: Page) {
	// The page reads the saved draft a moment after it opens, and saves its own a moment after each change (an apply
	// empties it): wait until the button and the saved draft agree
	await expect.poll(async () => isEmptyDraft(await api(page, "draft")) === ((await draftButton(page).textContent()) || "").trim().startsWith("没有草稿"),
		{message: "the page's draft saved", timeout: 15_000}).toBe(true);
	if (isEmptyDraft(await api(page, "draft"))) return;
	await expect(draftButton(page)).toHaveText(/草稿 \d+ 项/);
	await draftButton(page).click();
	await review(page).getByRole("button", {name: "清空全部草稿…"}).click();
	await review(page).getByRole("button", {name: "确定"}).click();
	await expect(draftButton(page)).toHaveText("没有草稿");
	await review(page).getByRole("button", {name: "关闭"}).click();
	await expect.poll(async () => isEmptyDraft(await api(page, "draft"))).toBe(true);
}

/** Applies the whole draft through the review panel and waits until it has gone to the game */
async function applyDraft(page: Page) {
	await expect(draftButton(page)).toHaveText(/草稿 \d+ 项/);
	await draftButton(page).click();
	await review(page).getByRole("button", {name: "应用", exact: true}).click();
	await expect(draftButton(page)).toHaveText("没有草稿", {timeout: 30_000});
	await review(page).getByRole("button", {name: "关闭"}).click();
}

/** Waits for the server's next "worked out the owners" line after a mark in its log, and returns it */
async function ownersLine(since: number): Promise<string> {
	return waitFor("the server to work the owners out again", async () =>
		serverLog().slice(since).split("\n").find(line => line.includes("worked out the owners of the track sections")), 60_000, 500);
}

// ---- The real map ----

/** The index of the rail between two nodes at these x (z = 0) in the network the page reads */
function railBetween(net: any, xa: number, xb: number): number {
	const near = (node: number, x: number) => Math.abs(net.nodes[node][0] - (x + 0.5)) < 0.6 && Math.abs(net.nodes[node][1] - 0.5) < 0.6;
	return net.rails.findIndex((rail: number[]) => (near(rail[0], xa) && near(rail[1], xb)) || (near(rail[0], xb) && near(rail[1], xa)));
}

/** A place on the screen, worked out when it is used */
type Where = () => Promise<{x: number, y: number}>;

/**
 * Opens the real map centred on a station: the logical map, the station, "在实际图上看" (which puts the station in the
 * middle at scale 2). Returns where on the screen a point of the world is.
 */
async function realMapAt(page: Page, station: string) {
	await page.getByRole("group", {name: "视图"}).getByRole("button", {name: "逻辑图"}).click();
	const logical = page.locator('svg[aria-label="全网逻辑图"]');
	await expect(logical.getByRole("button", {name: station, exact: true})).toBeVisible({timeout: 20_000});
	await logical.getByRole("button", {name: station, exact: true}).dispatchEvent("click");
	await page.getByRole("dialog", {name: `${station} 股道运用`}).getByRole("button", {name: "在实际图上看"}).click();
	await expect(page.locator("#net-canvas")).toBeVisible();
	const net = await network(page);
	const centre = net.stations.find((s: any) => String(s.name).split("|")[0] === station);
	expect(centre, `${station} in the network`).toBeTruthy();
	// The map's place is read again each time: a banner above it ("已应用 n 项。" after an apply) moves it down, and the
	// map keeps the same point of the world in its middle
	const wrap = page.locator("#net-wrap");
	const at = (x: number, z: number): Where => async () => {
		const box = (await wrap.boundingBox())!;
		return {x: box.x + box.width / 2 + (x - centre.x) * 2, y: box.y + box.height / 2 + (z - centre.z) * 2};
	};
	const away: Where = async () => {
		const box = (await wrap.boundingBox())!;
		return {x: box.x + 8, y: box.y + 8};
	};
	return {at, away};
}

/** The real map's hover text over a point, read again (the canvas only changes it when another rail is under the mouse) */
async function hoverText(page: Page, point: Where, away: Where): Promise<string> {
	const [p, a] = [await point(), await away()];
	await page.mouse.move(a.x, a.y);
	await page.mouse.move(p.x, p.y);
	return (await page.locator("#net-canvas").getAttribute("title")) || "";
}

async function railMenu(page: Page, point: Where, item: string) {
	const p = await point();
	await page.mouse.click(p.x, p.y, {button: "right"});
	await page.getByRole("menuitem", {name: item, exact: true}).click({timeout: 15_000});
}

test("U355 手改归属：只归一条、勾上另一条、对所有线路开放、恢复自动 @U355", async ({page}, testInfo) => {
	test.setTimeout(300_000);
	const {errors} = await openNextEditor(page, "owners");
	await clearDraft(page);
	const data = await api(page, "data");
	const lineKeys: string[] = [...new Set<string>(data.routes.map((r: any) => r.lineKey))];
	const back = lineKeys.find(key => key.includes("回程"))!;
	const main = lineKeys.find(key => key !== back && key.includes("测试线"))!;
	expect(main && back, `the fixture's two lines: ${lineKeys.join(", ")}`).toBeTruthy();
	expect(Object.keys(data.networkSettings.ownerOverrides || {}), "no owners set by hand before").toEqual([]);

	// The rail from the West siding to 甲站's platform
	const map = await realMapAt(page, "甲站");
	const net = await network(page);
	const rail = railBetween(net, -110, -90);
	expect(rail, "the rail from the West siding to 甲站").toBeGreaterThanOrEqual(0);
	const [a, b] = [net.nodes[net.rails[rail][0]], net.nodes[net.rails[rail][1]]];
	const point = map.at((a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
	const ownersOf = async () => {
		const now = await network(page);
		const index = railBetween(now, -110, -90);
		const entry = now.owners.rails.find((e: any[]) => e[0] === index);
		return entry ? {manual: entry[1] === 1, owners: entry.slice(4).map((i: number) => now.owners.names[i]).sort()} : {manual: false, owners: []};
	};
	await expect.poll(() => hoverText(page, point, map.away), "the hover names the rail and its owners").toMatch(/轨道 · 归属：.*（自动）/);
	expect((await ownersOf()).owners, "worked out: both lines").toEqual([main, back].sort());

	let since = 0;
	try {
		// Only 测试线
		await railMenu(page, point, "归属线路…");
		let panel = page.getByRole("dialog", {name: "区段归属"});
		await panel.getByLabel("测试线回程", {exact: true}).uncheck();
		await expect(panel.getByLabel("测试线", {exact: true})).toBeChecked();
		await panel.getByRole("button", {name: "确定"}).click();
		await expect(draftButton(page)).toHaveText("草稿 1 项");
		const drafted = await waitFor("the draft saved", async () => {
			const overrides = (await api(page, "draft")).networkSettings?.ownerOverrides;
			return overrides && Object.keys(overrides).length ? overrides : undefined;
		});
		const key = Object.keys(drafted)[0];
		expect(Object.values(drafted), "the section, by one rail, owned by 测试线 only").toEqual([[main]]);
		expect(await hoverText(page, point, map.away)).toContain("（草稿：手改）");
		since = serverLog().length;
		await applyDraft(page);
		expect(await ownersLine(since)).not.toContain("by their logical lines");
		await expect.poll(async () => (await api(page, "data")).networkSettings.ownerOverrides[key], {timeout: 30_000}).toEqual([main]);
		await expect.poll(ownersOf, {timeout: 30_000}).toEqual({manual: true, owners: [main]});

		// 测试线回程 ticked as well: both, set by hand (the stripes). The panel starts from the owners the page's map has,
		// which it reads from the game every few seconds: wait until it has the ones applied
		await expect.poll(() => hoverText(page, point, map.away), {message: "the page's map has the owners applied", timeout: 30_000}).toMatch(/归属：测试线（手改）/);
		await railMenu(page, point, "归属线路…");
		panel = page.getByRole("dialog", {name: "区段归属"});
		await expect(panel.getByLabel("测试线回程", {exact: true}), "the panel starts from the owners as they are").not.toBeChecked();
		await panel.getByLabel("测试线回程", {exact: true}).check();
		await panel.getByRole("button", {name: "确定"}).click();
		since = serverLog().length;
		await applyDraft(page);
		await ownersLine(since);
		await expect.poll(ownersOf, {timeout: 30_000}).toEqual({manual: true, owners: [main, back].sort()});
		await expect.poll(() => hoverText(page, point, map.away), {timeout: 30_000}).toMatch(/归属：测试线、测试线回程（手改）|归属：测试线回程、测试线（手改）/);
		await testInfo.attach("both lines, set by hand", {body: await page.locator("#net-wrap").screenshot(), contentType: "image/png"});

		// Open to every line
		await railMenu(page, point, "对所有线路开放");
		expect(await hoverText(page, point, map.away)).toContain("所有线路都能走（草稿：手改）");
		since = serverLog().length;
		await applyDraft(page);
		await ownersLine(since);
		await expect.poll(async () => (await api(page, "data")).networkSettings.ownerOverrides[key], {timeout: 30_000}).toEqual([]);
		await expect.poll(async () => (await ownersOf()).owners, {timeout: 30_000}).toEqual([]);
		await testInfo.attach("open to every line", {body: await page.locator("#net-wrap").screenshot(), contentType: "image/png"});
	} finally {
		// Worked out again: as the fixture had it
		await page.keyboard.press("Escape");
		await clearDraft(page);
		if (Object.keys((await api(page, "data")).networkSettings.ownerOverrides || {}).length) {
			await railMenu(page, point, "恢复自动归属");
			expect(await hoverText(page, point, map.away)).toContain("草稿：恢复自动");
			since = serverLog().length;
			await applyDraft(page);
			await ownersLine(since);
		}
		await expect.poll(async () => Object.keys((await api(page, "data")).networkSettings.ownerOverrides || {}), {timeout: 30_000}).toEqual([]);
	}
	await expect.poll(ownersOf, {timeout: 30_000}).toEqual({manual: false, owners: [main, back].sort()});
	expect(errors).toEqual([]);
});
