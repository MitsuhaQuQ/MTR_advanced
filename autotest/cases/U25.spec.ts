// U25 高级编辑器（只读）（高）: the dashboard's "advanced editor" opens the page on this computer; the tree has the lines,
// each line's summary timetable and running diagram, the depots and their sidings; switching the running day shows that
// day's table; a timetable changed in the game shows on the page within seconds; a service chosen is highlighted in the
// diagram and the table together; works in single player and multiplayer.
// Covered here (new editor, basic fixture): the timetable layer's tree has both fixture lines and their routes; the route
// "测试线" opens as its summary table (its title, its stations in order) and as its running diagram (the canvas); a
// service added in the game's timetable screen shows as a column within seconds; clicking its head marks it in the table
// and shows it as "选中的班次" beside, which stays chosen in the diagram view (the diagram's own drawing is a canvas: a
// screenshot is attached for review); the running day switch changes the day the table is for; the depot layer's tree
// has both depots, each with its siding. The service added in the game is deleted again at the end.
// Not covered: the dashboard button itself (the address is the client's /editor-url, as U427); a day run by a different
// table (the fixture's routes have one table each: U377 makes a second one in the draft); the highlight drawn on the
// canvas; multiplayer (needs a second player).
import {test, expect} from "../lib/test";
import type {Page} from "@playwright/test";
import {click, closeAll, fixture, list, move, openDashboard, serverData, switchTab, waitFor, waitScreen} from "../lib/game";
import {openNextEditor} from "../lib/editor";
import {addService, deleteService, setDashboardSearch} from "../lib/screens";

test("U25 高级编辑器只读：树、汇总表、运行图、运行日、游戏里改了跟着变 @U25", async ({page}, testInfo) => {
	test.setTimeout(4 * 60_000);
	const data = await serverData();
	const routeOf = (id: unknown) => data.routes.find((r: any) => String(r.id) === String(id));
	expect(routeOf(fixture.route), "the fixture's route").toBeTruthy();
	expect(routeOf(fixture.routeBack), "the fixture's way back").toBeTruthy();

	const {errors} = await openNextEditor(page, "timetable");
	const tree = page.locator(".nav-tree");
	let added = false;
	try {
		// The tree: both lines of the fixture, each with its route under it
		for (const name of ["测试线", "测试线回程"]) {
			await expect(lineRow(page, name), `the line ${name} in the tree`).toBeVisible({timeout: 20_000});
		}

		// The route's summary table: its title and its stations in order
		const table = await openRouteTable(page, "测试线", String(fixture.route));
		await expect(table.locator(".tt-tools b").first()).toHaveText("测试线 → 丙站");

		// Changed in the game: a service added in the timetable screen shows as a column within seconds
		await openTimetableExact("测试线 Test Line");
		await addService("10:00:00");
		added = true;
		await closeAll();
		const head = serviceHead(page, "10:00");
		// (The page shows the game's own time of day, as the timetable screen does)
		await expect(head.first(), "the service added in the game shows on the page").toBeVisible({timeout: 15_000});
		await expect(table.locator("tbody th.st")).toContainText(["甲站", "乙站", "丙站"]);

		// The service chosen: marked in the table and shown beside it (a click on the head starts editing its time, which
		// puts an input where the time was: the head is found again by its service; Esc leaves the editing)
		const service = await head.first().getAttribute("data-svc");
		expect(service, "the head names its service").toBeTruthy();
		await head.first().click();
		await expect(table.locator(`th.svc-head[data-svc="${service}"]`)).toHaveClass(/\bhl\b/);
		const chosen = page.getByRole("heading", {name: "选中的班次"});
		await expect(chosen, "the chosen service shown beside").toBeVisible();
		await page.keyboard.press("Escape");

		// The running diagram of the same route: the canvas, the service still chosen
		const views = page.getByRole("group", {name: "视图"});
		await views.getByRole("button", {name: "运行图", exact: true}).click();
		const canvas = page.locator('canvas[aria-label="运行图"]');
		await expect(canvas).toBeVisible();
		await expect(chosen, "still chosen in the diagram").toBeVisible();
		await testInfo.attach("running diagram with the service chosen", {body: await page.screenshot(), contentType: "image/png"});
		await views.getByRole("button", {name: "汇总时刻表", exact: true}).click();
		await expect(page.locator("[data-summary-table] .tt-tools b").first()).toHaveText("测试线 → 丙站");

		// The running day: the table is for the day chosen
		const day = page.locator("header").first().locator("select");
		const original = await day.inputValue();
		const next = String((Number(original) + 1) % 7);
		await day.selectOption(next);
		const dayName = ((await day.locator(`option[value="${next}"]`).textContent()) || "").trim();
		await expect(page.locator("[data-summary-table] .tt-tools .hint").first(), "the table is for the day chosen").toContainText(dayName);
		await day.selectOption(original);

		// The depots and their sidings
		await page.getByRole("navigation", {name: "工作层"}).locator('a[href="#/depots"]').click();
		for (const [depot, siding, name] of [[fixture.depotWest, fixture.sidingWest, "西车厂"], [fixture.depotEast, fixture.sidingEast, "东车厂"]]) {
			const depotRow = tree.locator(`.node[data-depot="${String(depot)}"]`);
			await expect(depotRow, `${name} in the tree`).toContainText(name, {timeout: 20_000});
			const sidingRow = tree.locator(`.node[data-siding="${String(siding)}"][data-of-depot="${String(depot)}"]:not([data-slot])`);
			for (let i = 0; i < 3 && !(await sidingRow.isVisible()); i++) {
				await depotRow.click();
				await page.waitForTimeout(300);
			}
			await expect(sidingRow, `${name}'s siding under it`).toBeVisible();
			await expect(sidingRow).toContainText(/停车不限|最多停 \d+ 列/);
		}
		expect(errors).toEqual([]);
	} finally {
		if (added) {
			await openTimetableExact("测试线 Test Line");
			await deleteService("10:00:00");
			await closeAll();
		}
	}
});

// ---- The page ----

/** A line's row in the timetable layer's tree, by the name it shows */
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
	// A click opens or closes them, depending on what is open already
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

/** A service's column head in the summary table, by its departure */
function serviceHead(page: Page, time: string) {
	return page.locator("[data-summary-table] th.svc-head").filter({has: page.locator(".time", {hasText: new RegExp(`^${time}`)})});
}

// ---- The game ----

const LIST = "DashboardScreen.dashboardList";

/**
 * Dashboard → routes → the route whose label is exactly this → edit → timetable. (lib/screens' openTimetable takes the
 * first row whose label contains the text, and "测试线 Test Line" is in "测试线 Test Line Back" too.)
 */
async function openTimetableExact(label: string) {
	await openDashboard();
	await switchTab("buttonTabRoutes");
	await setDashboardSearch(label);
	const row = await waitFor(`${label} in the route list`, async () => (await list(LIST)).rows.find(r => !r.header && r.label === label));
	await move(row.x + 10, row.y + row.height / 2);
	await click(`${LIST}.buttonEdit`);
	await waitScreen("EditRouteScreen");
	await click("EditRouteScreen.buttonTimetable");
	return waitScreen("TimetableScreen");
}
