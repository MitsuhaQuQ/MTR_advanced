// U565 归属层多选区段加入逻辑线路（中，0.99.0）: the ownership layer's select tool takes sections into a choice by hand
// (a click on the logical map takes a corridor in, marked yellow), the panel counts them, and the choice goes into a
// logical line picked there, into the draft. Basic fixture. A logical line is made through the editor's API first and
// taken away again after; the draft is emptied before and after.
// Not covered: the real map's gestures (drag, box, Shift+click) with the select tool, "新建逻辑线路并加入…".
import {test, expect} from "../lib/test";
import {openNextEditor} from "../lib/editor";
import type {Page} from "@playwright/test";

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
	await expect.poll(async () => isEmptyDraft(await api(page, "draft")) === ((await draftButton(page).textContent()) || "").trim().startsWith("没有草稿"),
		{message: "the page's draft saved", timeout: 15_000}).toBe(true);
	if (isEmptyDraft(await api(page, "draft"))) return;
	await draftButton(page).click();
	await review(page).getByRole("button", {name: "清空全部草稿…"}).click();
	await review(page).getByRole("button", {name: "确定"}).click();
	await expect(draftButton(page)).toHaveText("没有草稿");
	await review(page).getByRole("button", {name: "关闭"}).click();
	await expect.poll(async () => isEmptyDraft(await api(page, "draft"))).toBe(true);
}

const LINE = "990065", NAME = "多选测试线";

test("U565 归属层多选区段、加入逻辑线路 @U565", async ({page}) => {
	const {errors} = await openNextEditor(page, "owners");
	await clearDraft(page);
	await api(page, "apply", {operations: [{type: "networkSettings", logicalLines: {[LINE]: {name: NAME, color: 0x16a34a}}}]});
	const panel = page.getByRole("complementary", {name: "归属画笔"});
	await expect(panel.getByRole("button", {name: NAME})).toBeVisible({timeout: 30_000});
	const logicalView = page.getByRole("group", {name: "视图"}).getByRole("button", {name: "逻辑图"});
	if (await logicalView.getAttribute("aria-pressed") !== "true") await logicalView.click();

	// The select tool, then a corridor clicked: chosen, marked, counted
	await panel.getByRole("button", {name: "多选：先选区段，再加入逻辑线路"}).click();
	const corridor = page.locator('svg g.cursor-crosshair > polyline[stroke="transparent"]').first();
	await expect(corridor).toBeAttached({timeout: 20_000});
	await corridor.click({force: true});
	await expect(page.locator("[data-chosen]").first(), "the chosen corridor marked").toBeAttached();
	const chosen = panel.getByRole("group", {name: "选中的区段"});
	await expect(chosen.getByText(/已选 \d+ 段/)).toBeVisible();
	// Clicked again: out of the choice
	await corridor.click({force: true});
	await expect(chosen).toHaveCount(0);
	await corridor.click({force: true});
	await expect(chosen).toBeVisible();

	// Into the logical line picked: in the draft, the choice cleared
	await chosen.getByRole("combobox", {name: "加入哪条逻辑线路"}).selectOption({label: NAME});
	await chosen.getByRole("button", {name: "加入", exact: true}).click();
	await expect(chosen).toHaveCount(0);
	await expect(draftButton(page)).toHaveText(/草稿 \d+ 项/);
	await expect.poll(async () => JSON.stringify((await api(page, "draft"))?.networkSettings?.railLines || {})).toContain(LINE);

	await clearDraft(page);
	await api(page, "apply", {operations: [{type: "networkSettings", logicalLines: {[LINE]: null}}]});
	expect(errors).toEqual([]);
});
