// U361 逻辑线路初稿（高）: with no logical lines yet, "按现在的归属生成初稿…" makes a logical line of each set of operating
// lines owning sections (named after them; a connecting line's own sections become a logical line marked connecting) and
// ticks each operating line's; applied, the server works the owners out by the logical lines and every route finds the
// same path as before.
// Changes the world while it runs: the draft is applied, then its logical lines are deleted and that applied too, so the
// world is left without logical lines, as the basic fixture has it (U364 runs after this and needs that).
// Covered here: the button in the panel, the question panel and its count, the draft's three parts, the panel's brushes
// and table after it, the server log line with "by their logical lines", the client's copy of the settings, each
// route's path (the nodes the network read gives) before and after.
// Not covered: a connecting line's own logical line (the fixture has none), the duties planned the same (the fixture has
// no timetable), the row of the same name in the navigator (same action, old click handler).
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

/** Each route's path as the network read has it: its nodes' ids in order */
async function paths(page: Page): Promise<Record<string, string[]>> {
	const net = await network(page);
	return Object.fromEntries(net.routes.map((r: any) => [r.id, r.nodes.map((node: number) => net.nodes[node][2])]));
}

test("U361 按现在的归属生成逻辑线路初稿，应用后寻路不变 @U361", async ({page}) => {
	test.setTimeout(240_000);
	const {errors} = await openNextEditor(page, "owners");
	await clearDraft(page);
	const data = await api(page, "data");
	expect(data.networkSettings.logicalLines, "no logical lines before").toEqual([]);
	const nameOf = (key: string) => String((data.routes.find((r: any) => r.lineKey === key) || {}).line || key).split("||")[0].split("|")[0];

	// What the draft should hold: one logical line for each set of owners a section has
	await expect.poll(async () => (await network(page)).owners.ready, {timeout: 20_000}).toBe(true);
	const net = await network(page);
	const sets = new Map<string, string[]>();
	net.owners.rails.forEach((entry: any[]) => {
		const owners = entry.slice(4).map((i: number) => net.owners.names[i]).sort();
		if (owners.length) sets.set(owners.join("\n"), owners);
	});
	expect(sets.size, "sections with owners").toBeGreaterThan(0);
	const expectedNames = [...sets.values()].map(owners => owners.map(nameOf).join("、")).sort();
	const before = await paths(page);
	expect(Object.keys(before).length, "the fixture's routes have paths").toBeGreaterThanOrEqual(2);

	// The question, and the draft it makes
	const panel = page.getByRole("complementary", {name: "归属画笔"});
	const generate = panel.getByRole("button", {name: "按现在的归属生成初稿…"});
	await expect(generate).toBeEnabled({timeout: 20_000});
	await generate.click();
	const ask = page.getByRole("dialog", {name: "按现在的归属生成逻辑线路初稿"});
	await expect(ask).toContainText(`共 ${sets.size} 条`);
	await ask.getByRole("button", {name: "生成"}).click();
	await expect(ask).toBeHidden();
	await expect(draftButton(page)).toHaveText("草稿 1 项");
	const drafted = await waitFor("the draft saved", async () => {
		const settings = (await api(page, "draft")).networkSettings;
		return settings && settings.logicalLines ? settings : undefined;
	});
	const made = Object.entries(drafted.logicalLines) as [string, any][];
	expect(made.map(([, line]) => line.name).sort(), "named after the lines owning them").toEqual(expectedNames);
	expect(made.every(([, line]) => !line.connecting), "no connecting line in the fixture").toBe(true);
	expect(Object.keys(drafted.railLines).length, "sections put in them").toBeGreaterThan(0);
	expect(Object.values(drafted.railLines).every(id => made.some(([madeId]) => madeId === id))).toBe(true);
	const lineKeys = [...new Set<string>(data.routes.map((r: any) => r.lineKey))];
	for (const key of lineKeys) {
		expect(drafted.lineTracks[key], `${key} ticks the logical lines it owns`).toEqual(expect.arrayContaining([expect.any(String)]));
	}

	// The panel now has the logical lines as brushes, and the table ticked
	await expect(panel.getByRole("heading", {name: "逻辑线路画笔"})).toBeVisible();
	for (const name of expectedNames) await expect(panel.getByRole("button", {name, exact: true})).toBeVisible();
	await expect(panel.getByText("运营线路走哪些逻辑线路")).toBeVisible();
	for (const key of lineKeys) {
		for (const id of drafted.lineTracks[key]) {
			await expect(panel.getByRole("checkbox", {name: `${nameOf(key)} 走 ${drafted.logicalLines[id].name}`})).toBeChecked();
		}
	}

	try {
		// Applied: owners by the logical lines, every route on the same path
		const since = serverLog().length;
		await applyDraft(page);
		expect(await ownersLine(since)).toContain("by their logical lines");
		await expect.poll(async () => ((await api(page, "data")).networkSettings.logicalLines || []).map((line: any) => line.name).sort(), {timeout: 30_000}).toEqual(expectedNames);
		await expect.poll(async () => (await network(page)).owners.rails.filter((entry: any[]) => entry[3] === "").length, {timeout: 30_000, message: "every owned section in a logical line"}).toBe(0);
		await expect.poll(() => paths(page), {timeout: 30_000, message: "the same paths as before"}).toEqual(before);
	} finally {
		await page.keyboard.press("Escape");
		await clearDraft(page);
		await removeLogicalLines(page);
	}
	expect(await paths(page), "the same paths after taking them away").toEqual(before);
	expect(errors).toEqual([]);
});
