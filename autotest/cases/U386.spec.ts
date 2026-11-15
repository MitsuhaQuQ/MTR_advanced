// U386 新版的配线图示意图（高）: a route type's "线路配线图…" in the new navigator: a lane per track, the route's own
// tracks thick in its colour, other routes' in thin coloured lines, track no route runs grey; platforms beside their
// track with their numbers, the route's in its colour; station names and "第 N 站" on top; ＋ − 全图 zoom; right-click a
// track "设为寻路边界" adds a draft item and shows a purple dotted line at once; a line's "线路组配线图…" draws along the
// type with the most stops.
// U45.spec.ts already covers the basic drawing, ＋ and −, and a station name opening its stop. Covered here, on
// "测试线回程" (its tracks also run by 测试线): the thin lines of the other route, a grey base under every track, the
// route's platforms in its colour, 全图 fitting again, the boundary set and taken back through the right-click menu (the
// draft count, the draft's boundaries, the dotted line), and "线路组配线图…" offered only for a line of several types.
// Draft only: the boundary is taken back and the draft is empty at the end. A screenshot is kept for review.
// Not covered (the fixture has none): passing loops, turn-back tracks, crossovers and switches, long runs shortened
// with //, a line group's drawing (each fixture line has one type, so the item is greyed); and judging the lanes of real
// stations with many tracks, which needs a person looking at a real save.
import {test, expect} from "../lib/test";
import {fixture} from "../lib/game";
import {openNextEditor, routeMenu} from "../lib/editor";
import type {Page} from "@playwright/test";

/** An editor endpoint called from the page itself, with the key and origin the page has */
async function api(page: Page, path: string): Promise<any> {
	return page.evaluate(async path => {
		const key = sessionStorage.getItem("mtr-editor-key") || "";
		return (await fetch("/api/" + path, {headers: {"X-MTR-Editor-Key": key}, cache: "no-store"})).json();
	}, path);
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

test("U386 线路配线图：别的种别细线、站台颜色、全图、右键设寻路边界 @U386", async ({page}, testInfo) => {
	const {errors} = await openNextEditor(page, "lines");
	await clearDraft(page);
	await routeMenu(page, fixture.routeBack, "线路配线图…");
	const dialog = page.getByRole("dialog", {name: /线路配线图：测试线回程/});
	const svg = dialog.getByRole("img", {name: "线路配线图"});
	await expect(svg, "the layout loads").toBeVisible({timeout: 20_000});

	// Every track has its grey base; 测试线 runs the same tracks, so thin lines in its colour (0xFB8C00) beside the thick
	const hits = svg.locator('line[stroke="transparent"]');
	const tracks = await hits.count();
	expect(tracks, "tracks drawn").toBeGreaterThan(0);
	await expect(svg.locator('line[stroke="var(--grid-strong)"]')).toHaveCount(tracks);
	const thin = svg.locator('line[opacity="0.85"]');
	expect(await thin.count(), "the other route drawn thin").toBeGreaterThan(0);
	for (const stroke of await thin.evaluateAll(lines => lines.map(l => l.getAttribute("stroke")))) expect(stroke).toBe("#fb8c00");
	expect(await svg.locator('line[stroke-width="5"]').count(), "its own tracks thick").toBeGreaterThan(0);

	// Its platforms in its colour, named
	const ours = svg.locator('rect[fill="#fb8c00"]');
	expect(await ours.count(), "the route's three platforms in its colour").toBeGreaterThanOrEqual(3);

	// 全图 fits it again after zooming
	const width = async () => Number(await svg.getAttribute("width"));
	const fitted = await width();
	await dialog.getByRole("button", {name: "放大"}).click();
	await dialog.getByRole("button", {name: "放大"}).click();
	await expect.poll(width).toBeGreaterThan(fitted);
	await dialog.getByRole("button", {name: "全图"}).click();
	await expect.poll(width).toBe(fitted);

	// Right-click one of its own tracks: a boundary in the draft, dotted purple at once; taken back the same way
	const own = svg.locator("g", {has: page.locator('line[stroke-width="5"]')}).first();
	const hit = own.locator('line[stroke="transparent"]');
	const box = (await hit.boundingBox())!;
	const rightClick = () => hit.dispatchEvent("contextmenu", {clientX: box.x + box.width / 2, clientY: box.y + box.height / 2, button: 2});
	await expect(draftButton(page)).toHaveText("没有草稿");
	await rightClick();
	await page.getByRole("menuitem", {name: "设为寻路边界"}).click();
	await expect(draftButton(page), "one more item in the draft").toHaveText("草稿 1 项");
	await expect(own.locator('line[stroke="var(--boundary)"][stroke-dasharray="2 4"]'), "dotted purple at once").toHaveCount(1);
	await expect.poll(async () => Object.values((await api(page, "draft")).boundaries || {})).toEqual([true]);
	await expect(hit.locator("title")).toContainText("寻路边界");
	await testInfo.attach("route diagram with a boundary", {body: await dialog.screenshot(), contentType: "image/png"});

	await rightClick();
	await page.getByRole("menuitem", {name: "取消寻路边界"}).click();
	await expect(own.locator('line[stroke="var(--boundary)"]')).toHaveCount(0);
	await expect(draftButton(page)).toHaveText("没有草稿");
	await dialog.getByRole("button", {name: "关闭"}).click();
	await expect(dialog).toBeHidden();

	// "线路组配线图…" needs a line of several types; each of the fixture's lines has one
	const line = page.locator('[data-section="lines"][data-line]').filter({hasText: "测试线回程"});
	await line.click({button: "right"});
	await expect(page.getByRole("menuitem", {name: "线路组配线图…"})).toBeDisabled();
	await page.keyboard.press("Escape");

	await clearDraft(page);
	expect(errors).toEqual([]);
});
