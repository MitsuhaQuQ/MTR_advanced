// U377 新版里对话框的改动和撤销（高）: in the new editor, right-click a route type, "新建时刻表…", tick "全部线路一起建" and
// confirm: the top bar's "草稿 N 项" gains every type of the line, the running day switches to the new table's day; one
// Ctrl+Z takes the new tables of all those types out together; Ctrl+Y brings them back; the review panel shows them and
// drops them one at a time.
// The basic fixture's lines have one route each, so the box would be off: a copy of "测试线" is made in the game first
// ("复制线路": the copy is named "…_duplicate", which the line name leaves out, so it is the same line), and deleted at
// the end. The new tables only ever go into the draft, which is emptied again.
// Covered here: all of the above, on the line "测试线" with its two routes; the new table's chip in the summary table.
// Not covered: a dialog other than the new table (the other panels write to the draft the same way).
import {test, expect} from "../lib/test";
import type {Page} from "@playwright/test";
import {click, closeAll, fixture, list, move, openDashboard, serverData, sleep, switchTab, waitFor, waitScreen} from "../lib/game";
import {openNextEditor} from "../lib/editor";
import {setDashboardSearch} from "../lib/screens";

const LIST = "DashboardScreen.dashboardList";

test("U377 新建时刻表全部线路一起建，一次撤销、重做、逐个放弃 @U377", async ({page}) => {
	test.setTimeout(5 * 60_000);
	const routeId = String(fixture.route);
	await deleteRoutes("_duplicate", label => label.endsWith("_duplicate"));

	// A second route of the line "测试线": a copy made in the game
	const ids = new Set((await serverData()).routes.map((r: any) => String(r.id)));
	await openRouteEditExact("测试线 Test Line");
	await click("EditRouteScreen.buttonDuplicate");
	const copy = await waitFor("the copy on the server", async () =>
		(await serverData()).routes.find((r: any) => !ids.has(String(r.id)) && String(r.name).endsWith("_duplicate")), 15_000);
	await closeAll();

	const {errors} = await openNextEditor(page, "timetable");
	const day = page.locator("header").first().locator("select");
	let originalDay: string | null = null;
	try {
		await clearDraft(page);
		originalDay = await day.inputValue();
		const table = await openRouteTable(page, "测试线", routeId);
		// The copy is in the same line, once the page has read it
		await expect(page.locator(`.nav-tree .node[data-route="${String(copy.id)}"]:not([data-view])`), "the copy under the line").toBeVisible({timeout: 15_000});

		// Right-click the route type: "新建时刻表…"
		await page.locator(`.nav-tree .node[data-route="${routeId}"]:not([data-view])`).click({button: "right"});
		await page.getByText("新建时刻表…", {exact: true}).click();
		const dialog = page.getByRole("dialog", {name: /^新建时刻表：/});
		await expect(dialog).toBeVisible();
		await dialog.locator("label", {hasText: /^表名/}).locator("input").fill("U377表");
		// Sunday, the last of the week's buttons
		await dialog.getByRole("group", {name: "运行的星期"}).getByRole("button").nth(6).click();
		const all = dialog.locator("label", {hasText: "全部线路一起建"}).locator('input[type="checkbox"]');
		await expect(all, "the line has two routes: the box can be ticked").toBeEnabled();
		if (!(await all.isChecked())) await all.check();
		await dialog.getByRole("button", {name: "新建", exact: true}).click();
		await expect(dialog).toBeHidden();

		// Both routes in the draft, the day the new table's
		await expect(draftButton(page)).toHaveText("草稿 2 项");
		await expect(day, "the running day switched to the new table's").toHaveValue("6");
		await expect(table.locator(".table-chip", {hasText: "U377表"}), "the new table's chip").toBeVisible();

		// One Ctrl+Z takes both out; Ctrl+Y puts them back (not in a text field, where the keys are the field's)
		await page.locator("body").click({position: {x: 5, y: 5}}).catch(() => undefined);
		await page.keyboard.press("Control+z");
		await expect(draftButton(page), "one undo for both routes").toHaveText("没有草稿");
		await page.keyboard.press("Control+y");
		await expect(draftButton(page), "redo brings both back").toHaveText("草稿 2 项");

		// The review panel lists them and drops them one by one
		await draftButton(page).click();
		const review = page.getByRole("dialog", {name: "草稿的改动"});
		const drops = review.getByRole("button", {name: "放弃这条的草稿"});
		await expect(drops).toHaveCount(2);
		await drops.first().click();
		await expect(draftButton(page)).toHaveText("草稿 1 项");
		await expect(drops).toHaveCount(1);
		await drops.first().click();
		await expect(draftButton(page)).toHaveText("没有草稿");
		await page.keyboard.press("Escape");
		await expect(review).toBeHidden();
		expect(errors).toEqual([]);
	} finally {
		await clearDraft(page).catch(() => undefined);
		if (originalDay !== null) await day.selectOption(originalDay).catch(() => undefined);
		await deleteRoutes("_duplicate", label => label.endsWith("_duplicate"));
	}
});

// ---- The page ----

const draftButton = (page: Page) => page.locator("header:has(h1)").getByRole("button", {name: /^(草稿 \d+ 项|没有草稿|正在应用…)$/});

/** Empties the draft (it is shared by every page of this game and kept between tests) */
async function clearDraft(page: Page) {
	// The draft comes from the game a moment after the page connects
	await page.waitForTimeout(2000);
	if (((await draftButton(page).textContent()) || "").trim() === "没有草稿") return;
	await draftButton(page).click();
	const review = page.getByRole("dialog", {name: "草稿的改动"});
	await review.getByRole("button", {name: "清空全部草稿…"}).click();
	await review.getByRole("button", {name: "确定", exact: true}).click();
	await expect(draftButton(page)).toHaveText("没有草稿");
	await page.keyboard.press("Escape");
	await expect(review).toBeHidden();
}

function lineRow(page: Page, name: string) {
	return page.locator(".nav-tree .node[data-line]:not([data-view])")
		.filter({has: page.locator("span.font-medium", {hasText: new RegExp(`^${name}$`)})});
}

/** Opens a route's summary timetable from the timetable layer's tree: its line, the route, its "汇总时刻表" chip */
async function openRouteTable(page: Page, lineName: string, routeId: string) {
	const tree = page.locator(".nav-tree");
	const line = lineRow(page, lineName);
	const route = tree.locator(`.node[data-route="${routeId}"]:not([data-view])`);
	const chip = tree.locator(`.node[data-route="${routeId}"][data-view="table"]`);
	await expect(line).toBeVisible({timeout: 20_000});
	for (let i = 0; i < 3 && !(await route.isVisible()); i++) {
		await line.click();
		await page.waitForTimeout(300);
	}
	await expect(route).toBeVisible();
	for (let i = 0; i < 3 && !(await chip.isVisible()); i++) {
		await route.click();
		await page.waitForTimeout(300);
	}
	await chip.click();
	const table = page.locator("[data-summary-table]");
	await expect(table.locator(".tt-tools b").first()).toBeVisible();
	return table;
}

// ---- The game ----

/** Dashboard → routes → the route whose label is exactly this → EditRouteScreen */
async function openRouteEditExact(label: string) {
	await openDashboard();
	await switchTab("buttonTabRoutes");
	await setDashboardSearch(label);
	const row = await waitFor(`${label} in the list`, async () => (await list(LIST)).rows.find(r => !r.header && r.label === label));
	await move(row.x + 10, row.y + row.height / 2);
	await click(`${LIST}.buttonEdit`);
	return waitScreen("EditRouteScreen");
}

/** Deletes, from the dashboard, the routes the search finds whose label matches */
async function deleteRoutes(search: string, match: (label: string) => boolean) {
	await openDashboard();
	await switchTab("buttonTabRoutes");
	await setDashboardSearch(search);
	await sleep(300);
	for (let i = 0; i < 6; i++) {
		const row = (await list(LIST)).rows.find(r => !r.header && match(r.label));
		if (!row) break;
		await move(row.x + 10, row.y + row.height / 2);
		await click(`${LIST}.buttonDelete`);
		await waitScreen("DeleteConfirmationScreen");
		await click("DeleteConfirmationScreen.buttonYes");
		await waitScreen("DashboardScreen");
		await sleep(500);
	}
	await setDashboardSearch("");
	await closeAll();
	await waitFor(`no route matching ${search} left`, async () =>
		!(await serverData()).routes.some((r: any) => String(r.name).includes(search)), 10_000);
}
