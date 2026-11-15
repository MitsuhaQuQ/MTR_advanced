// U389 新版的归属画笔（高）: the ownership layer has a brush panel beside its map. Without logical lines the brushes are
// "只归 X", "对所有线路开放" and "恢复自动归属": choose one, click a section on the map, and its whole section takes it, the
// draft counting one more; dragging the map doesn't paint. With logical lines each logical line is a brush, with the
// eraser; their section counts follow; "◎" shows only that one; the table ticks the logical lines each operating line
// runs on; deleting a logical line takes two clicks and takes its sections and ticks with it.
// Painted here on the logical map (SVG; the brush is shared with the real map), on the stretch between 甲站 and 乙站
// and the one between 乙站 and 丙站. Draft only: nothing is applied, and the draft is empty again at the end.
// Covered here: the brushes' pressed state, a drag not painting, the draft's ownerOverrides for each owner brush and back
// to no draft, a new logical line becoming the brush, the section count after painting and erasing, the stretch drawn in
// the logical line's colour, ◎, a tick in the table and the draft's lineTracks, the two-click delete emptying the draft.
// Not covered: the dark dashed edge on the real map (a screenshot is attached), pathfinding after applying (U355, U361
// and U362 apply owners and logical lines).
import {test, expect} from "../lib/test";
import {sleep, waitFor} from "../lib/game";
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

const ownerOverrides = async (page: Page) => (await api(page, "draft")).networkSettings?.ownerOverrides || {};

/**
 * The logical map's stretch between two stations: the corridor group whose line runs between the two station glyphs'
 * centres (StationGlyph draws its name at the station's x; LogicalCorridor's lines run between the places' x).
 */
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

test("U389 归属画笔：没有逻辑线路时按线路画 @U389", async ({page}, testInfo) => {
	const {errors} = await openNextEditor(page, "owners");
	await clearDraft(page);
	const data = await api(page, "data");
	expect(data.networkSettings.logicalLines, "the fixture has no logical lines").toEqual([]);
	const main = (data.routes.find((r: any) => !String(r.name).includes("回程")) || {}).lineKey;
	await page.getByRole("group", {name: "视图"}).getByRole("button", {name: "逻辑图"}).click();
	const svg = page.locator('svg[aria-label="全网逻辑图"]');
	await expect(svg.getByRole("button", {name: "甲站", exact: true})).toBeVisible({timeout: 20_000});
	const panel = page.getByRole("complementary", {name: "归属画笔"});
	await expect(panel.getByRole("heading", {name: "归属画笔"})).toBeVisible();

	try {
		// Only 测试线: in hand, the stretch painted
		const only = panel.getByRole("button", {name: "只归 测试线", exact: true});
		await only.click();
		await expect(only).toHaveAttribute("aria-pressed", "true");
		let ab = await stretch(page, "甲站", "乙站");

		// A drag starting on the stretch pans the map and doesn't paint
		const box = (await ab.boundingBox())!;
		await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
		await page.mouse.down();
		for (let i = 1; i <= 8; i++) await page.mouse.move(box.x + box.width / 2 + i * 10, box.y + box.height / 2 + i * 4);
		await page.mouse.up();
		await sleep(1500);
		expect(await ownerOverrides(page), "a drag paints nothing").toEqual({});
		await expect(draftButton(page)).toHaveText("没有草稿");

		ab = await stretch(page, "甲站", "乙站");
		await ab.dispatchEvent("click");
		await expect(draftButton(page)).toHaveText("草稿 1 项");
		const painted = await waitFor("the section in the draft", async () => {
			const overrides = await ownerOverrides(page);
			return Object.keys(overrides).length ? overrides : undefined;
		});
		expect(new Set(Object.values(painted).map(value => JSON.stringify(value))), "the section owned by 测试线 alone").toEqual(new Set([JSON.stringify([main])]));
		await testInfo.attach("painted 只归 测试线", {body: await svg.screenshot(), contentType: "image/png"});

		// Open to every line
		const open = panel.getByRole("button", {name: "对所有线路开放", exact: true});
		await open.click();
		await expect(open).toHaveAttribute("aria-pressed", "true");
		await expect(only).toHaveAttribute("aria-pressed", "false");
		await (await stretch(page, "甲站", "乙站")).dispatchEvent("click");
		await expect.poll(async () => new Set(Object.values(await ownerOverrides(page)).map(value => JSON.stringify(value)))).toEqual(new Set(["[]"]));
		await expect(draftButton(page)).toHaveText("草稿 1 项");

		// Worked out again: as the game has it, so no draft left
		await panel.getByRole("button", {name: "恢复自动归属", exact: true}).click();
		await (await stretch(page, "甲站", "乙站")).dispatchEvent("click");
		await expect(draftButton(page)).toHaveText("没有草稿");
		await expect.poll(async () => ownerOverrides(page)).toEqual({});

		// Esc puts the brush down
		await page.keyboard.press("Escape");
		await expect(panel.getByRole("button", {name: "恢复自动归属", exact: true})).toHaveAttribute("aria-pressed", "false");
	} finally {
		await page.keyboard.press("Escape");
		await clearDraft(page);
	}
	expect(errors).toEqual([]);
});

test("U389 归属画笔：逻辑线路画笔、橡皮、只看这条、勾选表、删除 @U389", async ({page}) => {
	const {errors} = await openNextEditor(page, "owners");
	await clearDraft(page);
	await page.getByRole("group", {name: "视图"}).getByRole("button", {name: "逻辑图"}).click();
	const svg = page.locator('svg[aria-label="全网逻辑图"]');
	await expect(svg.getByRole("button", {name: "甲站", exact: true})).toBeVisible({timeout: 20_000});
	const panel = page.getByRole("complementary", {name: "归属画笔"});

	try {
		// A new logical line from the panel is the brush at once
		await panel.getByRole("button", {name: "＋ 新建逻辑线路…"}).click();
		const dialog = page.getByRole("dialog", {name: "新建逻辑线路"});
		await dialog.getByLabel("名称").fill("U389甲");
		await dialog.getByRole("button", {name: "新建"}).click();
		await expect(dialog).toBeHidden();
		await expect(panel.getByRole("heading", {name: "逻辑线路画笔"})).toBeVisible();
		const brush = panel.getByRole("button", {name: "U389甲", exact: true});
		await expect(brush).toHaveAttribute("aria-pressed", "true");
		// The inner locator of "has" is looked for inside each row, so it doesn't start from the panel
		const row = panel.locator("li", {has: page.getByRole("button", {name: "U389甲", exact: true})});
		const count = row.locator('[title="区段数"]');
		await expect(count).toHaveText("0");
		const id = await waitFor("the logical line in the draft", async () => {
			const lines = (await api(page, "draft")).networkSettings?.logicalLines || {};
			return Object.keys(lines).find(key => lines[key] && lines[key].name === "U389甲");
		});

		// Two stretches in, one erased
		await (await stretch(page, "甲站", "乙站")).dispatchEvent("click");
		await expect(count).not.toHaveText("0");
		const one = Number(await count.textContent());
		await (await stretch(page, "乙站", "丙站")).dispatchEvent("click");
		await expect.poll(async () => Number(await count.textContent())).toBeGreaterThan(one);
		const two = Number(await count.textContent());
		await expect((await stretch(page, "乙站", "丙站")).locator('line[stroke="#3b82f6"]').first(), "drawn in the logical line's colour").toBeAttached();
		const eraser = panel.getByRole("button", {name: "橡皮：移出逻辑线路", exact: true});
		await eraser.click();
		await expect(eraser).toHaveAttribute("aria-pressed", "true");
		await (await stretch(page, "甲站", "乙站")).dispatchEvent("click");
		await expect.poll(async () => Number(await count.textContent())).toBe(two - one);
		await expect((await stretch(page, "甲站", "乙站")).locator('line[stroke="#3b82f6"]'), "erased: no longer in its colour").toHaveCount(0);
		await expect.poll(async () => Object.values((await api(page, "draft")).networkSettings?.railLines || {}).filter(value => value === id).length).toBeGreaterThan(0);
		await page.keyboard.press("Escape");

		// ◎ shows only this line, and again shows all
		const focus = row.getByRole("button", {name: "◎"});
		await focus.click();
		await expect(focus).toHaveAttribute("aria-pressed", "true");
		await focus.click();
		await expect(focus).toHaveAttribute("aria-pressed", "false");

		// The table: 测试线 runs on it
		await expect(panel.getByText("运营线路走哪些逻辑线路")).toBeVisible();
		const tick = panel.getByRole("checkbox", {name: "测试线 走 U389甲"});
		// A click, then the box as the draft has it: the page draws the box again from the draft a moment later
		await tick.click();
		await expect(tick).toBeChecked();
		await expect.poll(async () => Object.values((await api(page, "draft")).networkSettings?.lineTracks || {})).toEqual([[id]]);

		// Deleting asks first, then takes the line, its sections and its tick: nothing left in the draft
		await row.getByRole("button", {name: "✕"}).click();
		await expect(row.getByRole("alert")).toContainText("删除“U389甲”？");
		// Still there (the question squeezes the brush's name out of sight while it shows, and goes by itself after 6 s,
		// so this doesn't wait for the name to be visible)
		await expect(brush, "not deleted on the first click").toHaveCount(1);
		await row.getByRole("alert").getByRole("button", {name: "确定"}).click();
		await expect(panel.getByRole("button", {name: "U389甲", exact: true})).toHaveCount(0);
		await expect(panel.getByRole("heading", {name: "归属画笔"})).toBeVisible();
		await expect(draftButton(page)).toHaveText("没有草稿");
		await expect.poll(async () => isEmptyDraft(await api(page, "draft"))).toBe(true);
	} finally {
		await page.keyboard.press("Escape");
		await clearDraft(page);
	}
	expect(errors).toEqual([]);
});
