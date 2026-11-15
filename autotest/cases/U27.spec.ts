// U27 编辑器管理的线路（高）: a route the advanced editor manages can only be looked at in the game's timetable screen
// (tables, direction and services can be gone through, nothing changed), with a note at the bottom; the dashboard's
// "draw stops" button for it is greyed out; a copy of the route made in the game ("复制线路") can be edited as usual.
// Covered here: "改由高级编辑器管理" in the new editor's summary table, applied, reaches the server; the game's timetable
// screen for "测试线" then shows gui.mtr.timetable_editor_managed, its switches and edit buttons are off, a service can
// still be chosen and closing it changes nothing on the server; the dashboard's draw-area button is off on that row
// and on for "测试线回程"; "复制线路" makes a route that isn't managed, whose timetable screen takes a new service.
// Changes the world while it runs: "测试线" is managed and a copy of it exists; both are undone at the end (the copy
// deleted, the route handed back with "交回游戏内编辑").
// Not covered: the hint's wording beyond "高级编辑器" (the font wraps it), the text fields refusing input; switching the
// direction (the screen shows that button only for a line with more than one route, TimetableScreen.init; the fixture's
// "测试线" is the only route of its line, so the button is hidden and the step is skipped).
import {test, expect} from "../lib/test";
import type {Page} from "@playwright/test";
import {click, closeAll, fixture, list, move, openDashboard, serverData, sleep, state, switchTab, waitFor, waitScreen, waitText, widget} from "../lib/game";
import {openNextEditor} from "../lib/editor";
import {addService, setDashboardSearch, SERVICES, selectService} from "../lib/screens";

const LIST = "DashboardScreen.dashboardList";
const routeOf = (data: any, id: unknown) => data.routes.find((r: any) => String(r.id) === String(id));

test("U27 编辑器管理的线路：游戏里只读、画停站灰掉、复制的可以改 @U27", async ({page}) => {
	test.setTimeout(6 * 60_000);
	const routeId = String(fixture.route);
	await deleteRoutes("_duplicate", label => label.endsWith("_duplicate"));
	const before = routeOf(await serverData(), routeId);
	expect(before.editor_managed, "the fixture's route starts editable in the game").toBe(false);

	const {errors} = await openNextEditor(page, "timetable");
	await clearDraft(page);
	let managed = false;
	try {
		// Managed by the editor: drafted in the route's summary table, then applied
		const table = await openRouteTable(page, "测试线", routeId);
		await table.getByRole("button", {name: "改由高级编辑器管理"}).click();
		await expect(table.getByText("由高级编辑器管理（草稿）")).toBeVisible();
		managed = true;
		await applyDraft(page);
		await waitFor("the server to have the route managed", async () => routeOf(await serverData(), routeId).editor_managed === true, 15_000);

		// The game's timetable screen only shows it
		await openRouteEditExact("测试线 Test Line");
		await click("EditRouteScreen.buttonTimetable");
		await waitScreen("TimetableScreen");
		await waitText(/高级编辑器/);
		for (const id of ["checkboxEnabled", "buttonClear", "buttonAddService", "buttonReturnToDepot", "buttonLinkRoute"]) {
			expect((await widget(`TimetableScreen.${id}`)).active, `${id} is off`).toBe(false);
		}
		// Gone through: the other direction (only shown for a line with more than one route), a service
		const direction = await widget("TimetableScreen.buttonDirection");
		if (direction.visible) {
			expect(direction.active, "the direction can be switched").toBe(true);
			await click("TimetableScreen.buttonDirection");
			await sleep(300);
			await click("TimetableScreen.buttonDirection");
		}
		const first = (await list(SERVICES)).rows[0];
		if (first) await selectService(first.label);
		expect((await state()).screenName, "still the timetable screen").toBe("TimetableScreen");
		await closeAll();
		await sleep(1000);
		const after = routeOf(await serverData(), routeId);
		expect(after.timetable_services, "closing the screen changed nothing").toEqual(before.timetable_services);
		expect(after.timetable_enabled).toBe(before.timetable_enabled);

		// The dashboard: drawing the stops is off for the managed route only
		await openDashboard();
		await switchTab("buttonTabRoutes");
		await hoverExact("测试线 Test Line");
		await expect.poll(async () => (await widget(`${LIST}.buttonDrawArea`)).active, {message: "draw stops off for the managed route", timeout: 3000}).toBe(false);
		await hoverExact("测试线回程 Test Line Back");
		await expect.poll(async () => (await widget(`${LIST}.buttonDrawArea`)).active, {message: "and on for the other", timeout: 3000}).toBe(true);
		await closeAll();

		// A copy made in the game is the game's to edit
		const ids = new Set((await serverData()).routes.map((r: any) => String(r.id)));
		await openRouteEditExact("测试线 Test Line");
		await click("EditRouteScreen.buttonDuplicate");
		const copy = await waitFor("the copy on the server", async () =>
			(await serverData()).routes.find((r: any) => !ids.has(String(r.id)) && String(r.name).endsWith("_duplicate")), 15_000);
		expect(copy.editor_managed, "the copy isn't managed").toBe(false);
		await waitScreen("EditRouteScreen");
		await click("EditRouteScreen.buttonTimetable");
		await waitScreen("TimetableScreen");
		await sleep(500);
		expect((await state()).texts.some(t => /高级编辑器/.test(t.text)), "no read-only note for the copy").toBe(false);
		expect((await widget("TimetableScreen.checkboxEnabled")).active, "the copy's timetable can be switched").toBe(true);
		await addService("13:00:00");
		await closeAll();
		await waitFor("the copy's new service on the server", async () =>
			routeOf(await serverData(), copy.id).timetable_services.length === copy.timetable_services.length + 1, 10_000);
		expect(errors).toEqual([]);
	} finally {
		await closeAll();
		await deleteRoutes("_duplicate", label => label.endsWith("_duplicate"));
		if (managed) {
			await clearDraft(page);
			const table = await openRouteTable(page, "测试线", routeId);
			const handBack = table.getByRole("button", {name: "交回游戏内编辑"});
			if (await handBack.isVisible()) {
				await handBack.click();
				await applyDraft(page);
			}
			await waitFor("the route handed back to the game", async () => routeOf(await serverData(), routeId).editor_managed === false, 15_000);
		}
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

/** Applies the draft from its review panel and waits until it has gone to the game */
async function applyDraft(page: Page) {
	await draftButton(page).click();
	const review = page.getByRole("dialog", {name: "草稿的改动"});
	await review.getByRole("button", {name: "应用", exact: true}).click();
	await expect(draftButton(page)).toHaveText("没有草稿", {timeout: 30_000});
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

/** Moves the mouse over the dashboard row whose label is exactly this, searching for it first */
async function hoverExact(label: string) {
	await setDashboardSearch(label);
	// The list is filtered on the next frames: read before that, it still has the rows of the search before, the route
	// in another row
	await sleep(500);
	const row = await waitFor(`${label} in the list`, async () => (await list(LIST)).rows.find(r => !r.header && r.label === label));
	// Off the list first: the search leaves the route in the same row as the one before, and the list takes the row
	// under the mouse again only when the mouse moves
	await move(1, 1);
	await sleep(100);
	await move(row.x + 10, row.y + row.height / 2);
	await waitFor(`the buttons of ${label}'s row`, async () => (await widget(`${LIST}.buttonDrawArea`)).visible, 3000);
}

/** Dashboard → routes → the route whose label is exactly this → EditRouteScreen */
async function openRouteEditExact(label: string) {
	await openDashboard();
	await switchTab("buttonTabRoutes");
	await hoverExact(label);
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
