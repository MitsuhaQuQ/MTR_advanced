// U394 新版的编辑车厂面板（高）: the depot layer's "更多 ▾ → 编辑车厂…" opens a side panel: unticking a route the depot
// runs takes its column out of the sidings' table at once; a siding can be given only some routes, how many trains it
// keeps and the vehicle pool's fleet; "放进草稿" and applying sets them in the game. Logical lines drawn on the ownership
// layer as a draft are still in the draft after the depot panel (with the least time to change ends) is saved.
// Changes the world while it runs: the West depot's siding is given 测试线 only, at most 2 trains, its pool 3 trains, and
// that is applied; the same panel then puts back what the game had and that is applied too (the siding's max_trains
// field may keep 1 rather than 0: unused while the siding keeps any number, Siding.limitToFleet).
// Covered here: the menu, the panel's route chips and table columns, the siding's ticks and fleet, the pool's fleet, the
// network setting, the draft's depot entry and logical lines after saving, dropping the network part from the review
// panel, the server's siding (route_ids, fleet_size) and the client's pool fleet after applying, putting them back.
// Not covered: duties planned with the siding running only its route (the fixture has no timetable), the measured buffers
// (none recorded on the fixture).
import {test, expect} from "../lib/test";
import {fixture, serverData, sleep, waitFor} from "../lib/game";
import {openNextEditor} from "../lib/editor";
import type {Locator, Page} from "@playwright/test";

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

/** The logical map's stretch between two stations (see U389.spec.ts) */
async function stretch(page: Page, from: string, to: string): Promise<Locator> {
	const svg = page.locator('svg[aria-label="全网逻辑图"]');
	const xOf = async (name: string) => Number(await svg.getByRole("button", {name, exact: true}).locator("text").getAttribute("x"));
	const [xa, xb] = [await xOf(from), await xOf(to)];
	const corridors = svg.locator("> g:not([role])");
	const index = await corridors.evaluateAll((groups, [xa, xb]) => groups.findIndex(group => {
		const line = group.querySelector("line");
		if (!line) return false;
		const [x1, x2] = [Number(line.getAttribute("x1")), Number(line.getAttribute("x2"))];
		return Math.abs(Math.min(x1, x2) - Math.min(xa, xb)) < 3 && Math.abs(Math.max(x1, x2) - Math.max(xa, xb)) < 3;
	}), [xa, xb]);
	expect(index, `the stretch ${from} – ${to} on the logical map`).toBeGreaterThanOrEqual(0);
	return corridors.nth(index);
}

/** The depot layer, the West depot chosen, "更多 ▾ → 编辑车厂…" */
async function openDepotPanel(page: Page): Promise<Locator> {
	await page.getByRole("navigation", {name: "工作层"}).locator('a[href="#/depots"]').click();
	await page.getByRole("combobox", {name: "车厂"}).selectOption(String(fixture.depotWest));
	await page.getByRole("button", {name: "更多 ▾"}).click();
	await page.getByRole("menuitem", {name: "编辑车厂…"}).click();
	const panel = page.getByRole("dialog", {name: "编辑车厂：西车厂"});
	await expect(panel).toBeVisible();
	return panel;
}

const sidingOf = (data: any, id: unknown) => data.sidings.find((s: any) => String(s.id) === String(id));

test("U394 编辑车厂面板：负责线路、存车线种别和车数、应用；逻辑线路草稿保留 @U394", async ({page}) => {
	test.setTimeout(300_000);
	const {errors} = await openNextEditor(page, "owners");
	await clearDraft(page);
	const sidingId = String(fixture.sidingWest);
	const route = String(fixture.route);
	const game = await api(page, "data");
	const depot = game.depots.find((d: any) => String(d.id) === String(fixture.depotWest));
	expect(depot.routes, "the West depot runs 测试线").toEqual([route]);
	const pool = (depot.pools || [])[0];
	const before = sidingOf(await serverData(), sidingId);
	const beforePoolFleet = pool ? pool.fleet : null;

	// A logical line drawn as a draft on the ownership layer first
	await page.getByRole("group", {name: "视图"}).getByRole("button", {name: "逻辑图"}).click();
	await expect(page.locator('svg[aria-label="全网逻辑图"]').getByRole("button", {name: "甲站", exact: true})).toBeVisible({timeout: 20_000});
	const brushes = page.getByRole("complementary", {name: "归属画笔"});
	await brushes.getByRole("button", {name: "＋ 新建逻辑线路…"}).click();
	const newLine = page.getByRole("dialog", {name: "新建逻辑线路"});
	await newLine.getByLabel("名称").fill("U394");
	await newLine.getByRole("button", {name: "新建"}).click();
	await (await stretch(page, "甲站", "乙站")).dispatchEvent("click");
	await page.keyboard.press("Escape");
	const logical = await waitFor("the logical line and its section in the draft", async () => {
		const settings = (await api(page, "draft")).networkSettings;
		return settings?.logicalLines && Object.keys(settings.railLines || {}).length ? settings : undefined;
	});

	try {
		// The panel: a route ticked adds a column to the sidings' table, unticked takes it out again
		let panel = await openDepotPanel(page);
		const columns = panel.locator("thead th[title]");
		await expect(columns, "one column: the depot's one route").toHaveCount(1);
		const backChip = panel.locator("button[aria-pressed]").filter({hasText: "回程"});
		await expect(backChip).toHaveAttribute("aria-pressed", "false");
		await backChip.click();
		await expect(columns).toHaveCount(2);
		await backChip.click();
		await expect(columns, "unticked: its column goes at once").toHaveCount(1);

		// The siding runs 测试线 only and keeps at most 2; the pool has 3; the least time to change ends one second more
		const row = panel.locator(`tr[data-siding="${sidingId}"]`);
		await row.getByRole("checkbox").check();
		await row.locator('input[type="number"]').fill("2");
		if (pool) await panel.locator(`[data-pool="${pool.key}"]`).getByLabel("配属车数").fill("3");
		const reverse = panel.getByLabel("换端最短时间（全网，秒）");
		const reverseSeconds = Number(await reverse.inputValue());
		await reverse.fill(String(reverseSeconds + 1));
		await panel.getByRole("button", {name: "放进草稿"}).click();
		await expect(panel).toBeHidden();

		// The draft: the depot's entry, the network setting, and the logical line still there
		const draft = await waitFor("the depot in the draft", async () => {
			const now = await api(page, "draft");
			return now.depots && now.depots[String(fixture.depotWest)] ? now : undefined;
		});
		const entry = draft.depots[String(fixture.depotWest)];
		expect(entry.routes).toEqual([route]);
		expect(entry.sidings[sidingId]).toEqual({fleet: 2, routes: [route]});
		if (pool) expect(entry.poolFleets[pool.key]).toBe(3);
		expect(draft.networkSettings.minReverseMillis).toBe((reverseSeconds + 1) * 1000);
		expect(draft.networkSettings.logicalLines, "the logical line drawn before is still in the draft").toEqual(logical.logicalLines);
		expect(draft.networkSettings.railLines, "and its section").toEqual(logical.railLines);

		// The network part (logical line, change of ends) dropped; the depot applied
		await draftButton(page).click();
		const network = review(page).locator("section").filter({has: page.getByRole("heading", {name: "全网设置"})});
		await network.getByRole("button", {name: "放弃这项草稿"}).click();
		await expect(network).toHaveCount(0);
		await review(page).getByRole("button", {name: "关闭"}).click();
		await applyDraft(page);
		await expect.poll(async () => {
			const siding = sidingOf(await serverData(), sidingId);
			return [siding.route_ids.map(String), siding.fleet_size];
		}, {timeout: 30_000, message: "the server's siding"}).toEqual([[route], 2]);
		if (pool) {
			await expect.poll(async () => ((await api(page, "data")).depots.find((d: any) => String(d.id) === String(fixture.depotWest)).pools || [])
				.find((p: any) => p.key === pool.key)?.fleet, {timeout: 30_000}).toBe(3);
		}
		expect((await api(page, "data")).networkSettings.logicalLines, "no logical line went to the game").toEqual([]);
	} finally {
		// Back as the game had it: the siding all of the depot's routes and any number of trains, the pool as it was
		await page.keyboard.press("Escape");
		await clearDraft(page);
		const panel = await openDepotPanel(page);
		const row = panel.locator(`tr[data-siding="${sidingId}"]`);
		await row.getByRole("checkbox").setChecked(before.route_ids.length > 0);
		await row.locator('input[type="number"]').fill(before.fleet_size < 0 ? "" : String(before.fleet_size));
		if (pool) await panel.locator(`[data-pool="${pool.key}"]`).getByLabel("配属车数").fill(beforePoolFleet === null || beforePoolFleet < 0 ? "" : String(beforePoolFleet));
		await panel.getByRole("button", {name: "放进草稿"}).click();
		await expect(panel).toBeHidden();
		// Nothing to apply when the game already had it so (the test failed before applying)
		await sleep(800);
		if (/草稿 \d+ 项/.test((await draftButton(page).textContent()) || "")) await applyDraft(page);
		await expect.poll(async () => {
			const siding = sidingOf(await serverData(), sidingId);
			return [siding.route_ids.map(String), siding.fleet_size, siding.unlimited_trains];
		}, {timeout: 30_000, message: "the siding as it was"}).toEqual([before.route_ids.map(String), before.fleet_size, before.unlimited_trains]);
	}
	expect(errors).toEqual([]);
});
