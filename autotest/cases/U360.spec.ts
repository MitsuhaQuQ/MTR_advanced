// U360 车型设置（高）: in the depot layer's navigator, a depot opened, right-click "车型 X" → "车型设置…": the vehicle's
// acceleration and deceleration, top speed, driving by hand, switching back and the types it may run; with "统一设置"
// ticked, changed and put in the draft, the vehicle's row gets the draft dot; "编辑车厂…" then shows only a summary line
// for it (no inputs), and saving the depot keeps the vehicle's change; applied, the depot's trains of that vehicle run
// with it. Owners changed by hand on the ownership layer stay in the draft meanwhile.
// Changes the world while it runs: the West depot's vehicle gets its own settings (1.2 m/s², 120 km/h) and that is
// applied; then the same panel puts back what the game had (the first siding's own settings, "统一设置" unticked) and
// that is applied too.
// Covered here: the menu, the panel's fields as the game has them, the draft dot, the depot panel without the fields and
// with the summary, the draft's vehicle settings after saving the depot, the owner brush's draft kept, the server's
// depot vehicle_settings (acceleration, top speed) after applying, putting them back.
// Not covered: trains actually running with the new acceleration (no trains run on the fixture), the types a vehicle may
// run (left unchanged: changing them would stop the fixture's routes being run).
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

const depotId = () => String(fixture.depotWest);

/** The depot layer with the West depot opened in the navigator; returns its vehicle's row */
async function vehicleRow(page: Page): Promise<Locator> {
	await page.getByRole("navigation", {name: "工作层"}).locator('a[href="#/depots"]').click();
	const navigator = page.getByRole("complementary", {name: "导航"});
	const depot = navigator.locator(`[data-depot="${depotId()}"]`);
	await expect(depot).toBeVisible({timeout: 20_000});
	if (await depot.getAttribute("aria-expanded") !== "true") await depot.click();
	const row = navigator.locator(`[data-pool][data-of-depot="${depotId()}"]`).first();
	await expect(row).toBeVisible();
	return row;
}

async function vehiclePanel(page: Page): Promise<Locator> {
	await (await vehicleRow(page)).click({button: "right"});
	await page.getByRole("menuitem", {name: "车型设置…"}).click();
	const panel = page.getByRole("dialog", {name: /^车型设置：/});
	await expect(panel).toBeVisible();
	return panel;
}

/** The depot's vehicle settings as the server keeps them: [acceleration, deceleration, manual, manual speed, switch back, top speed] */
async function serverVehicle(poolKey: string): Promise<any[] | undefined> {
	const depot = (await serverData()).depots.find((d: any) => String(d.id) === depotId());
	return (depot.vehicle_settings || {})[poolKey];
}

test("U360 车型设置：统一设置加速度和最高速度，编辑车厂不丢，应用；手改归属草稿保留 @U360", async ({page}) => {
	test.setTimeout(300_000);
	const {errors} = await openNextEditor(page, "owners");
	await clearDraft(page);
	const game = await api(page, "data");
	const pool = game.depots.find((d: any) => String(d.id) === depotId()).pools[0];
	expect(pool, "the West depot has a vehicle").toBeTruthy();
	const before = await serverVehicle(pool.key);

	// Owners changed by hand on the ownership layer, as a draft
	await page.getByRole("group", {name: "视图"}).getByRole("button", {name: "逻辑图"}).click();
	await expect(page.locator('svg[aria-label="全网逻辑图"]').getByRole("button", {name: "甲站", exact: true})).toBeVisible({timeout: 20_000});
	await page.getByRole("complementary", {name: "归属画笔"}).getByRole("button", {name: "只归 测试线", exact: true}).click();
	await (await stretch(page, "甲站", "乙站")).dispatchEvent("click");
	await page.keyboard.press("Escape");
	const overrides = await waitFor("the owners in the draft", async () => {
		const value = (await api(page, "draft")).networkSettings?.ownerOverrides;
		return value && Object.keys(value).length ? value : undefined;
	});

	try {
		// The vehicle's panel shows what the game has
		let panel = await vehiclePanel(page);
		const shown = pool.vehicleSettings;
		const together = panel.getByLabel(/统一设置这个车型/);
		await expect(together).toBeChecked({checked: Boolean(shown.set)});
		const acceleration = panel.getByLabel("加速度 m/s²", {exact: true});
		await expect(acceleration).toHaveValue(String(Math.round(shown.acceleration * 400 * 100) / 100));
		await expect(panel.getByLabel("最高速度 km/h（空着不限）", {exact: true})).toHaveValue(shown.maxSpeed > 0 ? String(shown.maxSpeed) : "");
		await expect(panel.getByLabel("允许手动驾驶", {exact: true})).toBeChecked({checked: Boolean(shown.manual)});
		await expect(panel.getByLabel("种别（逗号隔开，空着不限）")).toHaveValue(pool.serviceTypes || "");

		// Set for the vehicle: 1.2 m/s² (1.6 if it already has that), 120 km/h. The game keeps the acceleration in steps of
		// 0.001 blocks/tick² (VehicleSettings rounds it), 0.4 m/s² each: 1.3 would be kept as 1.2
		const value = Math.abs(shown.acceleration * 400 - 1.2) < 0.01 ? 1.6 : 1.2;
		await together.check();
		await acceleration.fill(String(value));
		await panel.getByLabel("最高速度 km/h（空着不限）", {exact: true}).fill("120");
		await panel.getByRole("button", {name: "放进草稿"}).click();
		await expect(panel).toBeHidden();
		await expect((await vehicleRow(page)).getByTitle("有没应用的草稿"), "the vehicle's row has the draft dot").toBeVisible();

		// The depot panel: no vehicle fields, a summary; saving it keeps the vehicle's change
		await page.getByRole("complementary", {name: "导航"}).locator(`[data-depot="${depotId()}"]`).click({button: "right"});
		await page.getByRole("menuitem", {name: "编辑车厂…"}).click();
		const depotPanel = page.getByRole("dialog", {name: "编辑车厂：西车厂"});
		await expect(depotPanel).toBeVisible();
		await expect(depotPanel.getByLabel("加速度 m/s²", {exact: true})).toHaveCount(0);
		await expect(depotPanel.getByLabel("最高速度 km/h（空着不限）", {exact: true})).toHaveCount(0);
		const summary = depotPanel.locator(`[data-pool="${pool.key}"] span.text-dim`).last();
		await expect(summary, "a summary line of the vehicle").not.toHaveText("");
		await depotPanel.getByRole("button", {name: "放进草稿"}).click();
		await expect(depotPanel).toBeHidden();
		const draft = await waitFor("the vehicle in the draft", async () => {
			const now = await api(page, "draft");
			const settings = now.depots?.[depotId()]?.vehicleSettings?.[pool.key];
			return settings ? now : undefined;
		});
		const drafted = draft.depots[depotId()].vehicleSettings[pool.key];
		expect(drafted.acceleration).toBeCloseTo(value / 400, 6);
		expect(drafted.maxSpeed).toBe(120);
		expect(draft.networkSettings?.ownerOverrides, "the owners changed by hand are still in the draft").toEqual(overrides);

		// Only the vehicle applied: the network part dropped first
		await draftButton(page).click();
		const network = review(page).locator("section").filter({has: page.getByRole("heading", {name: "全网设置"})});
		await network.getByRole("button", {name: "放弃这项草稿"}).click();
		await expect(network).toHaveCount(0);
		await review(page).getByRole("button", {name: "关闭"}).click();
		await applyDraft(page);
		await expect.poll(async () => {
			const settings = await serverVehicle(pool.key);
			return settings ? [Math.round(settings[0] * 400 * 100) / 100, settings[5]] : null;
		}, {timeout: 30_000, message: "the server's depot has the vehicle's settings"}).toEqual([value, 120]);
		expect(Object.keys((await api(page, "data")).networkSettings.ownerOverrides || {}), "no owners went to the game").toEqual([]);
	} finally {
		// Back as the game had it
		await page.keyboard.press("Escape");
		await clearDraft(page);
		const panel = await vehiclePanel(page);
		if (before) {
			const together = panel.getByLabel(/统一设置这个车型/);
			await together.check();
			await panel.getByLabel("加速度 m/s²", {exact: true}).fill(String(Math.round(before[0] * 400 * 100) / 100));
			await panel.getByLabel("最高速度 km/h（空着不限）", {exact: true}).fill(before[5] > 0 ? String(before[5]) : "");
		} else {
			await panel.getByLabel(/统一设置这个车型/).uncheck();
		}
		await panel.getByRole("button", {name: "放进草稿"}).click();
		await expect(panel).toBeHidden();
		await sleep(800);
		if (/草稿 \d+ 项/.test((await draftButton(page).textContent()) || "")) await applyDraft(page);
		await expect.poll(async () => {
			const settings = await serverVehicle(pool.key);
			return settings ? [Math.round(settings[0] * 400 * 100) / 100, settings[5]] : null;
		}, {timeout: 30_000, message: "the vehicle as it was"}).toEqual(before ? [Math.round(before[0] * 400 * 100) / 100, before[5]] : null);
	}
	expect(errors).toEqual([]);
});
