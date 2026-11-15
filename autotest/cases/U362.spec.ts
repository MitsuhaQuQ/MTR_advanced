// U362 划入和移出逻辑线路（高）: on the ownership layer's real map, right-click a rail: "划入逻辑线路 ▸ X" puts its whole
// section (to the next switch or boundary) in X as a draft, the hover saying "逻辑线路：X（草稿）"; "移出逻辑线路" takes it
// out; "新建逻辑线路并划入…" makes a line and puts the section in it. A logical line's right-click menu renames and
// recolours it. Applied, the server works the owners out "by their logical lines".
// The rail used is the one between 甲站 and 乙站 (x -60 → -15). So the fixture's two lines can still run there, both are
// ticked for the logical line before applying.
// Changes the world while it runs: the logical line is applied, then deleted and that applied too, leaving the world
// without logical lines as the fixture has it (U364 needs that).
// Covered here: the three menu items and the hover text after each, the draft's parts, renaming and recolouring from the
// navigator, the panel's table, the server log line, the client's copy of the settings and of the section's logical line.
// Not covered: "设为联络线" on a logical line, which the editor no longer offers (2026-11-15: a connecting stretch is set
// on the track with "对所有线路开放", see AskPanels.tsx LogicalLinePanel; only "改为普通逻辑线路" is left for old marks);
// the drawing (colour and dark dashed edge: a screenshot is attached); the logical line after a restart.
import {test, expect} from "../lib/test";
import {waitFor} from "../lib/game";
import {serverLog} from "../lib/lines";
import {openNextEditor} from "../lib/editor";
import type {Page} from "@playwright/test";

// ---- The editor's draft and endpoints ----

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

async function applyDraft(page: Page) {
	await expect(draftButton(page)).toHaveText(/草稿 \d+ 项/);
	await draftButton(page).click();
	await review(page).getByRole("button", {name: "应用", exact: true}).click();
	await expect(draftButton(page)).toHaveText("没有草稿", {timeout: 30_000});
	await review(page).getByRole("button", {name: "关闭"}).click();
}

async function ownersLine(since: number): Promise<string> {
	return waitFor("the server to work the owners out again", async () =>
		serverLog().slice(since).split("\n").find(line => line.includes("worked out the owners of the track sections")), 60_000, 500);
}

/** Deletes every logical line in the panel (✕, then 确定) and applies that, if the game has any */
async function removeLogicalLines(page: Page) {
	const lines: any[] = (await api(page, "data")).networkSettings.logicalLines || [];
	if (!lines.length) return;
	const panel = page.getByRole("complementary", {name: "归属画笔"});
	for (const line of lines) {
		const row = panel.locator("li", {has: page.getByRole("button", {name: line.name, exact: true})});
		await row.getByRole("button", {name: "✕"}).click();
		await row.getByRole("alert").getByRole("button", {name: "确定"}).click();
		await expect(row).toHaveCount(0);
	}
	const since = serverLog().length;
	await applyDraft(page);
	expect(await ownersLine(since), "worked out without logical lines again").not.toContain("by their logical lines");
	await expect.poll(async () => (await api(page, "data")).networkSettings.logicalLines, {timeout: 30_000}).toEqual([]);
}

// ---- The real map ----

function railBetween(net: any, xa: number, xb: number): number {
	const near = (node: number, x: number) => Math.abs(net.nodes[node][0] - (x + 0.5)) < 0.6 && Math.abs(net.nodes[node][1] - 0.5) < 0.6;
	return net.rails.findIndex((rail: number[]) => (near(rail[0], xa) && near(rail[1], xb)) || (near(rail[0], xb) && near(rail[1], xa)));
}

/** A place on the screen, worked out when it is used */
type Where = () => Promise<{x: number, y: number}>;

/** The real map centred on a station at scale 2, through the logical map's station and "在实际图上看" */
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

async function hoverText(page: Page, point: Where, away: Where): Promise<string> {
	const [p, a] = [await point(), await away()];
	await page.mouse.move(a.x, a.y);
	await page.mouse.move(p.x, p.y);
	return (await page.locator("#net-canvas").getAttribute("title")) || "";
}

/** Right-clicks the map there and picks an item, or an item of a submenu */
async function railMenu(page: Page, point: Where, item: string | RegExp, sub?: string) {
	const p = await point();
	await page.mouse.click(p.x, p.y, {button: "right"});
	await page.getByRole("menuitem", typeof item === "string" ? {name: item, exact: true} : {name: item}).click({timeout: 15_000});
	if (sub) await page.getByRole("menuitem", {name: sub, exact: true}).click({timeout: 15_000});
}

async function newLogicalLine(page: Page, name: string) {
	const dialog = page.getByRole("dialog", {name: "新建逻辑线路"});
	await dialog.getByLabel("名称").fill(name);
	await dialog.getByRole("button", {name: "新建"}).click();
	await expect(dialog).toBeHidden();
}

/** The drafted logical line of that name, once the draft is saved */
async function drafted(page: Page, name: string): Promise<{id: string, line: any, settings: any}> {
	return waitFor(`${name} in the draft`, async () => {
		const settings = (await api(page, "draft")).networkSettings;
		const found = Object.entries(settings?.logicalLines || {}).find(([, line]: [string, any]) => line && line.name === name);
		return found ? {id: found[0], line: found[1], settings} : undefined;
	});
}

test("U362 划入、移出、新建并划入逻辑线路，改名改色，应用 @U362", async ({page}, testInfo) => {
	test.setTimeout(300_000);
	const {errors} = await openNextEditor(page, "owners");
	await clearDraft(page);
	expect((await api(page, "data")).networkSettings.logicalLines, "no logical lines before").toEqual([]);
	const map = await realMapAt(page, "甲站");
	const net = await network(page);
	const rail = railBetween(net, -60, -15);
	expect(rail, "the rail from 甲站 to 乙站").toBeGreaterThanOrEqual(0);
	const [a, b] = [net.nodes[net.rails[rail][0]], net.nodes[net.rails[rail][1]]];
	const point = map.at((a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
	await expect.poll(() => hoverText(page, point, map.away), "the hover is on the rail").toMatch(/^轨道/);

	try {
		// A first logical line, from the navigator: the rail's menu then offers it
		const navigator = page.getByRole("complementary", {name: "导航"});
		await navigator.locator("[data-new-logical]").click();
		await newLogicalLine(page, "U362甲");
		const first = await drafted(page, "U362甲");

		// 划入 ▸ U362甲
		// The item's name ends in the submenu's "▸"
		await railMenu(page, point, /^划入逻辑线路/, "U362甲");
		await expect.poll(() => hoverText(page, point, map.away)).toContain("逻辑线路：U362甲（草稿）");
		await expect.poll(async () => Object.values((await api(page, "draft")).networkSettings?.railLines || {})).toEqual([first.id]);

		// 移出
		await railMenu(page, point, "移出逻辑线路“U362甲”");
		await expect.poll(() => hoverText(page, point, map.away)).toContain("不属于任何逻辑线路");
		await expect.poll(async () => Object.keys((await api(page, "draft")).networkSettings?.railLines || {})).toEqual([]);

		// 新建并划入
		await railMenu(page, point, "新建逻辑线路并划入…");
		await newLogicalLine(page, "U362乙");
		const second = await drafted(page, "U362乙");
		await expect.poll(() => hoverText(page, point, map.away)).toContain("逻辑线路：U362乙（草稿）");
		await expect.poll(async () => Object.values((await api(page, "draft")).networkSettings?.railLines || {})).toEqual([second.id]);

		// Renamed and recoloured from the navigator's right-click menu
		await navigator.locator(`[data-logical="${second.id}"]`).click({button: "right"});
		await page.getByRole("menuitem", {name: "改名和颜色…"}).click();
		const edit = page.getByRole("dialog", {name: "逻辑线路：U362乙"});
		await edit.getByLabel("名称").fill("U362丙");
		await edit.getByLabel("颜色").fill("#00a000");
		await edit.getByRole("button", {name: "确定"}).click();
		const renamed = await drafted(page, "U362丙");
		expect(renamed.id, "the same line").toBe(second.id);
		expect(renamed.line.color).toBe(0x00a000);
		await expect.poll(() => hoverText(page, point, map.away)).toContain("逻辑线路：U362丙（草稿）");
		await testInfo.attach("section in a drafted logical line", {body: await page.locator("#net-wrap").screenshot(), contentType: "image/png"});

		// The unused first line goes; both operating lines run on the renamed one
		const panel = page.getByRole("complementary", {name: "归属画笔"});
		const unused = panel.locator("li", {has: page.getByRole("button", {name: "U362甲", exact: true})});
		await unused.getByRole("button", {name: "✕"}).click();
		await unused.getByRole("alert").getByRole("button", {name: "确定"}).click();
		await expect(unused).toHaveCount(0);
		for (const line of ["测试线", "测试线回程"]) {
			// A click, then the box as the draft has it: the page draws the box again from the draft a moment later
			const tick = panel.getByRole("checkbox", {name: `${line} 走 U362丙`});
			await tick.click();
			await expect(tick).toBeChecked();
		}

		// Applied: worked out by the logical lines; the section is in it on the client too
		const since = serverLog().length;
		await applyDraft(page);
		expect(await ownersLine(since)).toContain("by their logical lines");
		await expect.poll(async () => ((await api(page, "data")).networkSettings.logicalLines || []).map((line: any) => [line.name, line.color]), {timeout: 30_000})
			.toEqual([["U362丙", 0x00a000]]);
		const applied = (await api(page, "data")).networkSettings;
		const id = applied.logicalLines[0].id;
		expect(Object.values(applied.railLines)).toEqual([id]);
		await expect.poll(async () => {
			const now = await network(page);
			const index = railBetween(now, -60, -15);
			return (now.owners.rails.find((entry: any[]) => entry[0] === index) || [])[3];
		}, {timeout: 30_000, message: "the section's logical line as the server worked it out"}).toBe(id);
		const hover = await hoverText(page, point, map.away);
		expect(hover).toContain("逻辑线路：U362丙");
		expect(hover).not.toContain("（草稿）");
	} finally {
		await page.keyboard.press("Escape");
		await clearDraft(page);
		await removeLogicalLines(page);
	}
	expect(errors).toEqual([]);
});
