// U430 实际图（轨道编辑）还能用（高）: the real network map the old code still draws (#net-canvas, oldEditor.js), as the new
// page shows it: the physical layer's "轨道编辑" with 全图, zoom, refresh, transport mode and map layer; right-click a
// rail for its pathfinding boundary; the ownership layer's "实际图" painted with a brush; the floating map for choosing a
// new line's stations ("线路（在全网配线图上选站）…") and a depot rule's way out ("在图上点轨道…").
// Basic fixture (the straight line z = 0, stations 甲乙丙, depots 西/东). Every change goes into the editor's draft only,
// which is emptied before and after each test; nothing is applied to the game.
// Rails are found on the canvas with the mouse: the map names the rail under it in the canvas's title (netRailText).
// Covered here: the tool bar's 全图/−/＋/wheel changing the drawing and 全图 bringing it back, ↻ reading the network again,
// the mode and layer selects, the boundary set and taken off again through the right-click menu, a brush painting a rail
// on the ownership layer's real map, a station picked on the floating map and the picking cancelled, a rail clicked for
// a depot rule's way out on the floating map and cancelled, no page errors.
// Not covered: "留置位" from the right-click menu (the physical layer's menu has only the boundary, oldEditor.js:132;
// 留置位 is only drawn and named there); switching transport mode when the world has one mode only; the dispatch layer's
// real map with trains and their choosing (the basic fixture runs no trains; it belongs with z-dispatch.spec.ts on the
// six lines).
import {test, expect} from "../lib/test";
import type {Locator, Page} from "@playwright/test";
import {fixture} from "../lib/game";
import {openNextEditor} from "../lib/editor";

const draftButton = (page: Page) => page.locator("header:has(h1)").getByRole("button", {name: /^(草稿 \d+ 项|没有草稿)$/});

async function discardDrafts(page: Page) {
	const button = draftButton(page);
	await expect(button).toBeVisible();
	if (/没有草稿/.test(await button.innerText())) return;
	await button.click();
	const panel = page.getByRole("dialog", {name: "草稿的改动"});
	await panel.getByRole("button", {name: "清空全部草稿…"}).click();
	await panel.getByRole("alert").getByRole("button", {name: "确定"}).click();
	await expect(button).toHaveText(/没有草稿/);
	await panel.getByRole("button", {name: "关闭"}).click();
	await page.waitForTimeout(1000);
}

const canvasHash = (canvas: Locator) => canvas.evaluate((c: HTMLCanvasElement) => {
	const data = c.toDataURL();
	let h = 0;
	for (let i = 0; i < data.length; i++) h = (h * 31 + data.charCodeAt(i)) | 0;
	return h;
});

/**
 * Moves the mouse over the canvas, the middle row first and then rows further up and down, until the map names a rail
 * under it (the canvas's title) matching want; returns where.
 */
async function railAt(page: Page, canvas: Locator, want: RegExp = /\S/): Promise<{x: number, y: number, title: string}> {
	const box = (await canvas.boundingBox())!;
	const middle = box.y + box.height / 2;
	for (let k = 0; k <= 30; k++) {
		const y = middle + (k % 2 ? 1 : -1) * Math.ceil(k / 2) * 4;
		for (let x = box.x + 8; x < box.x + box.width - 8; x += 6) {
			await page.mouse.move(x, y);
			const title = (await canvas.getAttribute("title")) || "";
			if (title && want.test(title)) return {x, y, title};
		}
	}
	throw new Error(`no rail ${want} found on the map`);
}

test("U430 轨道编辑：全图、缩放、刷新、运输方式和图层、右键寻路边界 @U430", async ({page}) => {
	test.setTimeout(4 * 60_000);
	const {errors} = await openNextEditor(page, "physical");
	await discardDrafts(page);
	try {
		await page.getByRole("group", {name: "视图"}).getByRole("button", {name: "轨道编辑"}).click();
		const canvas = page.locator("#net-canvas");
		await expect(canvas).toBeVisible({timeout: 20_000});
		await expect(page.locator("#net-info"), "the network read").toHaveText(/^[1-9]\d* 段轨道，\d+ 个车站$/, {timeout: 20_000});

		// 全图, −, ＋ and the wheel each change the drawing; 全图 fits it as before (no mouse over a rail yet, so nothing is
		// drawn highlighted)
		await page.locator("#net-fit").click();
		// The page reads the map from the game again every 3 s (NET_POLL_MS), and what it draws can still change a moment
		// after it opens (after a full run of the earlier tests, the drawing changed once in that time): the fitted drawing
		// is taken once it stays the same over one more read
		let fitted = await canvasHash(canvas);
		await expect.poll(async () => {
			const now = await canvasHash(canvas);
			const same = now === fitted;
			fitted = now;
			return same;
		}, {message: "the map drawn the same over a read", intervals: [3500], timeout: 30_000}).toBe(true);
		await page.locator("#net-zoom-in").click();
		await expect.poll(() => canvasHash(canvas), {message: "＋ zooms in"}).not.toBe(fitted);
		let before = await canvasHash(canvas);
		await page.locator("#net-zoom-out").click();
		await page.locator("#net-zoom-out").click();
		await expect.poll(() => canvasHash(canvas), {message: "− zooms out"}).not.toBe(before);
		before = await canvasHash(canvas);
		const box = (await canvas.boundingBox())!;
		await page.mouse.move(box.x + 4, box.y + 4);
		await page.mouse.wheel(0, -300);
		await expect.poll(() => canvasHash(canvas), {message: "the wheel zooms"}).not.toBe(before);
		await page.locator("#net-fit").click();
		await expect.poll(() => canvasHash(canvas), {message: "全图 fits it again"}).toBe(fitted);

		// ↻ reads the network again from the game
		await page.locator("#net-refresh").click();
		await expect(page.locator("#net-info")).toHaveText(/^[1-9]\d* 段轨道，\d+ 个车站$/, {timeout: 20_000});

		// Transport mode: the railway at least (the fixture has rails only)
		const modes = await page.locator("#net-mode option").allTextContents();
		expect(modes, "transport modes to choose from").toContain("铁路");
		// Map layer: physical ↔ ownership, the legend following
		await page.locator("#net-layer").selectOption("owners");
		await expect(page.locator("#net-legend")).toHaveText(/线路色|逻辑线路的颜色/);
		await page.locator("#net-layer").selectOption("physical");
		await expect(page.locator("#net-legend")).toHaveText(/红：寻路边界/);

		// Right-click a rail: its pathfinding boundary set, into the draft, and taken off again
		const rail = await railAt(page, canvas);
		expect(rail.title, "the rail's tooltip says what right-click does").toContain("右键设寻路边界");
		await page.mouse.click(rail.x, rail.y, {button: "right"});
		await page.getByRole("menu").getByRole("menuitem", {name: "设为寻路边界"}).click();
		await expect(draftButton(page), "the boundary is in the draft").toHaveText("草稿 1 项");
		await page.mouse.click(rail.x, rail.y, {button: "right"});
		await page.getByRole("menu").getByRole("menuitem", {name: "取消寻路边界"}).click();
		await expect(draftButton(page), "taken off again, nothing left in the draft").toHaveText("没有草稿");
	} finally {
		await discardDrafts(page);
	}
	expect(errors).toEqual([]);
});

test("U430 归属层实际图：画笔点轨道 @U430", async ({page}) => {
	const {errors} = await openNextEditor(page, "owners");
	await discardDrafts(page);
	try {
		await page.getByRole("group", {name: "视图"}).getByRole("button", {name: "实际图"}).click();
		const canvas = page.locator("#net-canvas");
		await expect(canvas).toBeVisible({timeout: 20_000});
		await page.locator("#net-fit").click();
		const brushes = page.getByRole("complementary", {name: "归属画笔"});
		await expect(brushes).toBeVisible();
		// A logical line's brush where the world has logical lines, otherwise an operating line's ("只归 …")
		const brush = brushes.getByRole("button", {name: /^只归 /}).or(brushes.locator("li").first().getByRole("button").first()).first();
		await brush.click();
		await expect(brush, "the brush in hand").toHaveAttribute("aria-pressed", "true");
		const rail = await railAt(page, canvas);
		await page.mouse.click(rail.x, rail.y);
		await expect(draftButton(page), "the rail's section painted, in the draft").toHaveText(/草稿 [1-9]\d* 项/);
		await page.keyboard.press("Escape");
		await expect(brush, "Esc puts the brush down").toHaveAttribute("aria-pressed", "false");
	} finally {
		await discardDrafts(page);
	}
	expect(errors).toEqual([]);
});

test("U430 浮动地图：新建线路在图上选站 @U430", async ({page}) => {
	const {errors} = await openNextEditor(page, "timetable");
	await discardDrafts(page);
	try {
		await page.getByRole("button", {name: "新建 ▾"}).click();
		await page.getByRole("menu").getByRole("menuitem", {name: "线路（在全网配线图上选站）…"}).click();
		const floating = page.getByRole("dialog", {name: "在图上选择"});
		await expect(floating).toBeVisible();
		await expect(floating.getByText("在图上选站", {exact: true})).toBeVisible();
		const canvas = floating.locator("#net-canvas");
		await expect(canvas).toBeVisible({timeout: 20_000});
		await floating.locator("#net-fit").click();
		const picking = floating.getByRole("complementary", {name: "新建线路"});
		await expect(picking.getByText("还没选车站")).toBeVisible();
		// A platform's rail picks its station (one platform each in the fixture: no platform menu)
		const platform = await railAt(page, canvas, /^站台/);
		await page.mouse.click(platform.x, platform.y);
		await expect(picking.getByText("还没选车站"), "a station picked").toBeHidden();
		await expect(picking.locator("ol > li"), "one stop").toHaveCount(1);
		await floating.getByRole("button", {name: "取消"}).first().click();
		await expect(floating, "cancelled: the floating map goes").toBeHidden();
		await expect(draftButton(page), "nothing made").toHaveText("没有草稿");
	} finally {
		await discardDrafts(page);
	}
	expect(errors).toEqual([]);
});

test("U430 浮动地图：出入库规则在图上点轨道 @U430", async ({page}) => {
	const {errors} = await openNextEditor(page, "depots");
	await discardDrafts(page);
	try {
		const depotRow = page.locator(`#tree .node[data-depot="${String(fixture.depotWest)}"]`);
		await expect(depotRow).toBeVisible({timeout: 20_000});
		await depotRow.click({button: "right"});
		await page.getByRole("menu").getByRole("menuitem", {name: "出入库规则…"}).click();
		const rules = page.getByRole("dialog", {name: /^出入库规则：西车厂/});
		await expect(rules).toBeVisible();
		await rules.getByRole("button", {name: "新增规则"}).click();
		await rules.locator('section[data-rule="0"]').getByRole("button", {name: "在图上点轨道…"}).first().click();

		const floating = page.getByRole("dialog", {name: "在图上选择"});
		await expect(floating).toBeVisible();
		await expect(floating.getByText("在图上点出入库的轨道", {exact: true})).toBeVisible();
		const canvas = floating.locator("#net-canvas");
		await expect(canvas).toBeVisible({timeout: 20_000});
		await floating.locator("#net-fit").click();
		const way = floating.getByRole("complementary", {name: "选出口"});
		await expect(way).toBeVisible();
		const rail = await railAt(page, canvas);
		await page.mouse.click(rail.x, rail.y);
		await expect(way.getByText(/经过的轨道：[1-9]\d* 段/), "the rail clicked is on the way").toBeVisible();
		await floating.getByRole("button", {name: "取消"}).first().click();
		await expect(floating, "cancelled: the floating map goes").toBeHidden();
		// The rules panel may come back with the rule being edited: closed without 放进草稿
		const again = page.getByRole("dialog", {name: /^出入库规则：西车厂/});
		if (await again.isVisible()) await again.getByRole("button", {name: "关闭"}).click();
		await expect(draftButton(page), "nothing put in the draft").toHaveText("没有草稿");
	} finally {
		await discardDrafts(page);
	}
	expect(errors).toEqual([]);
});
