// U429 运行图和汇总表只有新画法（高）: the timetable layer's tool bar has no "old drawing" switch; on the running diagram
// (a canvas the new page draws, views/RunningDiagram.tsx) a click chooses a service, a drag moves it, a right-click offers
// "选中这一班和之后的 n 班", the wheel and the tool bar's −/＋ zoom the time axis; a service clicked in the summary table
// (views/SummaryTable.tsx) is centred on the diagram; "显示回送" puts the runs out of and back to the depot in the table's
// heads; "上下显示" shows the diagram and the table together.
// Needs the six lines' timetables (the basic fixture has none, and its routes no stop times): this file belongs in
// autotest/cases-lines, named z-u429.spec.ts and added to AFTER in playwright.config.ts, as it changes the editor's draft
// (shared by every page of the test client) and must not run beside the observe stage. The draft is never applied: the
// game's timetables are left as they are, and the draft is emptied at the start and the end.
// Covered here: no "旧版画法"; the three views and both shown at once; choosing a service on the canvas (its head marked in
// the table); wheel and −/＋ change the drawing; a table head clicked brings its service under the diagram's middle (found
// with the mouse, by the tooltip naming it); the right-click menu choosing the later services; a drag moving them (the
// drag's tip, a draft item); the deadhead notes in the heads; no page errors.
// Not covered: dragging a stop's dwell handle (the squares' places on the canvas are not exposed to the page, and a drag
// near them moves the service instead), how the strokes look (the canvas is attached as a screenshot for review), the
// phone width.
import {test, expect} from "../lib/test";
import type {Locator, Page} from "@playwright/test";
import {lines} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

/** core/diagram.ts: the station names' column and the time axis' height, where no service is drawn */
const LABEL_W = 120, AXIS_H = 28;
/** Line 3 runs on time with its depot's duties (z-dispatch.spec.ts takes its trains first) */
const LAYOUT = 3;

const draftButton = (page: Page) => page.getByRole("button", {name: /^(草稿 \d+ 项|没有草稿)$/});

/** Empties the editor's draft through the review panel ("清空全部草稿…", then its confirmation) */
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
	// The draft is saved to the client 400 ms after the last change
	await page.waitForTimeout(1000);
}

/** Opens a line's running diagram from the timetable layer's tree ("N号线" row, then its 运行图 chip) */
async function openLineDiagram(page: Page, layout: number) {
	const row = page.locator("#tree .node[data-line]:not([data-view])").filter({hasText: new RegExp(`^\\W*${layout}号线`)}).first();
	await expect(row, `line ${layout} in the tree`).toBeVisible({timeout: 20_000});
	const key = (await row.getAttribute("data-line"))!;
	const chip = page.locator(`#tree .node[data-line=${JSON.stringify(key)}][data-view="diagram"]`);
	if (await chip.count() === 0) await row.click();
	await chip.click();
	await expect(chip).toHaveAttribute("aria-current", "true");
}

/** A short hash of what the canvas shows */
const canvasHash = (canvas: Locator) => canvas.evaluate((c: HTMLCanvasElement) => {
	const data = c.toDataURL();
	let h = 0;
	for (let i = 0; i < data.length; i++) h = (h * 31 + data.charCodeAt(i)) | 0;
	return h;
});

const tooltipShown = (page: Page) => page.locator("#tooltip").evaluate(el => !(el as HTMLElement).hidden);
const tooltipFirstLine = async (page: Page) => ((await page.locator("#tooltip").innerText()) || "").split("\n")[0].trim();

/**
 * Moves the mouse down the canvas at a fraction of the drawing's width and returns where the tooltip named a service
 * (the diagram shows its tooltip only over a service's line, within 6 px); all of them, or the first only.
 */
async function servicesAlong(page: Page, canvas: Locator, fraction: number, all = false): Promise<{x: number, y: number, text: string}[]> {
	const box = (await canvas.boundingBox())!;
	const x = box.x + LABEL_W + (box.width - LABEL_W) * fraction;
	const found: {x: number, y: number, text: string}[] = [];
	let last = "";
	for (let y = box.y + AXIS_H + 4; y < box.y + box.height - 2; y += 4) {
		await page.mouse.move(x, y);
		if (!(await tooltipShown(page))) {
			last = "";
			continue;
		}
		const text = await tooltipFirstLine(page);
		if (text && text !== last) {
			found.push({x, y, text});
			if (!all) break;
		}
		last = text;
	}
	return found;
}

/** The first service found, trying several places across the drawing */
async function anyService(page: Page, canvas: Locator, fractions = [0.5, 0.3, 0.7, 0.15, 0.85]) {
	for (const fraction of fractions) {
		const [hit] = await servicesAlong(page, canvas, fraction);
		if (hit) return hit;
	}
	throw new Error("no service drawn on the running diagram");
}

test("U429 运行图和汇总表：新画法的选中、拖动、右键、缩放、居中、显示回送 @lines @U429", async ({page}, testInfo) => {
	test.setTimeout(6 * 60_000);
	const line = lines.find(l => l.layout === LAYOUT);
	expect(line, "the six lines' fixture (MTM_FIXTURE_NAME=lines)").toBeTruthy();
	const {errors} = await openNextEditor(page, "timetable");
	await discardDrafts(page);

	try {
		await openLineDiagram(page, LAYOUT);
		const canvas = page.locator('canvas[aria-label="运行图"]');
		await expect(canvas).toBeVisible({timeout: 20_000});

		// No switch back to the old drawing; the three views (the window is large enough for both at once)
		expect(await page.getByText("旧版画法").count(), "no 旧版画法 switch").toBe(0);
		const views = page.getByRole("group", {name: "视图"});
		for (const name of ["运行图", "汇总时刻表", "上下显示"]) await expect(views.getByRole("button", {name})).toBeVisible();
		await views.getByRole("button", {name: "上下显示"}).click();
		await expect(views.getByRole("button", {name: "上下显示"})).toHaveAttribute("aria-pressed", "true");
		const table = page.locator("[data-summary-table]");
		await expect(canvas, "上下显示: the diagram").toBeVisible();
		await expect(table, "上下显示: and the table").toBeVisible();

		// Chosen on the canvas: its head in the table is marked
		// Around the time now first; the whole day if none is there
		let hit = await anyService(page, canvas).catch(() => null);
		if (!hit) {
			for (let i = 0; i < 14; i++) await page.getByRole("button", {name: "缩小时间轴"}).click();
			hit = await anyService(page, canvas);
		}
		await page.mouse.click(hit.x, hit.y);
		await expect(table.locator("th.svc-head.hl").first(), `the service clicked (${hit.text}) is chosen in the table`).toBeVisible();
		await testInfo.attach("diagram, a service chosen", {body: await canvas.screenshot(), contentType: "image/png"});

		// The wheel and the tool bar's −/＋ zoom the time axis
		let before = await canvasHash(canvas);
		const box = (await canvas.boundingBox())!;
		await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
		await page.mouse.wheel(0, -200);
		await expect.poll(() => canvasHash(canvas), {message: "the wheel zooms"}).not.toBe(before);
		before = await canvasHash(canvas);
		await page.getByRole("button", {name: "放大时间轴"}).click();
		await expect.poll(() => canvasHash(canvas), {message: "＋ zooms in"}).not.toBe(before);
		before = await canvasHash(canvas);
		await page.getByRole("button", {name: "缩小时间轴"}).click();
		await expect.poll(() => canvasHash(canvas), {message: "− zooms out"}).not.toBe(before);

		// A head clicked in the table: zoomed in so that its service is off the drawing first, then it is brought under
		// the diagram's middle, where the mouse finds it by its time
		for (let i = 0; i < 6; i++) await page.getByRole("button", {name: "放大时间轴"}).click();
		const heads = table.locator("th.svc-head");
		const target = heads.nth((await heads.count()) - 1);
		const time = ((await target.locator("span.time").innerText()) || "").trim().replace(/\s*\*$/, "");
		expect(time, "the last service's time").toMatch(/^\d{2}:\d{2}/);
		await target.click();
		// The click also opens the head's time for editing: left unchanged
		await page.keyboard.press("Escape");
		await expect(target).toHaveClass(/\bhl\b/);
		const middle = await servicesAlong(page, canvas, 0.5, true);
		expect(middle.map(m => m.text).some(text => text.includes(time)), `the diagram centred on ${time}: ${middle.map(m => m.text).join(" | ")}`).toBe(true);

		// Right-click: the later services; the whole day shown first, and early services tried first, so there are some
		for (let i = 0; i < 14; i++) await page.getByRole("button", {name: "缩小时间轴"}).click();
		let chosenLater = false;
		for (const fraction of [0.08, 0.15, 0.25, 0.35, 0.5]) {
			const [early] = await servicesAlong(page, canvas, fraction);
			if (!early) continue;
			await page.mouse.click(early.x, early.y, {button: "right"});
			const item = page.getByRole("menu").getByRole("menuitem", {name: /^选中这一班和之后的 \d+ 班$/});
			if (await item.count() === 0) {
				// Closed without choosing: Esc, or a click on the page's title away from the diagram
				await page.keyboard.press("Escape");
				if (await page.getByRole("menu").count()) await page.getByRole("heading", {name: "MTR 高级编辑器"}).click();
				continue;
			}
			await expect(page.getByRole("menu").getByRole("menuitem", {name: /^这一班和之后的 \d+ 班一起平移…$/}), "and moving them").toBeVisible();
			await item.click();
			await expect.poll(() => table.locator("th.svc-head.hl").count(), {message: "the service and the later ones chosen"}).toBeGreaterThan(1);
			chosenLater = true;

			// A drag along the time axis moves them: the tip says how many and how far; the move goes into the draft
			await page.mouse.move(early.x, early.y);
			await page.mouse.down();
			await page.mouse.move(early.x + 10, early.y);
			await page.mouse.move(early.x + 60, early.y, {steps: 6});
			await expect.poll(() => tooltipFirstLine(page), {message: "the drag's tip"}).toMatch(/^\d+ 班，[+−]/);
			await page.mouse.up();
			await expect(draftButton(page), "the move is in the draft").toHaveText(/草稿 [1-9]\d* 项/);
			break;
		}
		expect(chosenLater, "a service with later ones offered 选中这一班和之后的 n 班").toBe(true);
		await testInfo.attach("diagram, services moved in the draft", {body: await canvas.screenshot(), contentType: "image/png"});

		// 显示回送: the runs out of and back to the depot in the heads
		const deadheads = table.getByLabel("显示回送");
		await deadheads.check();
		await expect(table.locator('th.svc-head span.note[title="出库回送"], th.svc-head span.note[title="回库回送"]').first(), "deadhead notes in the heads").toBeVisible();
		await deadheads.uncheck();
		await expect(table.locator('th.svc-head span.note[title="出库回送"], th.svc-head span.note[title="回库回送"]')).toHaveCount(0);
	} finally {
		await discardDrafts(page);
	}
	expect(errors).toEqual([]);
});
