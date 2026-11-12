// U24 切换仪表盘列表（高）: switching between the dashboard's lists, or from a long list to a short one, while the mouse
// moves does not crash, and the row buttons appear on the row under the mouse.
// (2026-11-02 crash: DashboardList.mouseMoved out of bounds.) The fixture's station list is longer than a page, its
// route and depot lists are two rows each.
import {test, expect} from "../lib/test";
import {list, move, openDashboard, scroll, state, switchTab, waitFor, widget, ScreenState} from "../lib/game";

const LIST = "DashboardScreen.dashboardList";
const ROW_BUTTONS = ["buttonFind", "buttonDrawArea", "buttonEdit", "buttonUp", "buttonDown", "buttonAdd", "buttonDelete"].map(name => `${LIST}.${name}`);

/** Moves the mouse down the whole list, row by row and past its end; at each row, every row button shown is on it. */
async function sweep(where: string) {
	const current = await list(LIST);
	const top = current.y;
	const bottom = current.y + current.height + 40;
	let buttonsSeen = 0;
	for (let y = top; y <= bottom; y += 7) {
		await move(current.x + 10, y);
		const after: ScreenState = await state();
		const shown = after.lists.find(l => l.id === LIST)!;
		const row = shown.rows.find(r => y >= r.y && y < r.y + r.height);
		for (const button of after.widgets.filter(w => ROW_BUTTONS.includes(w.id) && w.visible)) {
			buttonsSeen++;
			expect(row, `${where}: ${button.id} is shown at y=${button.y} with the mouse at y=${y}, on no row`).toBeTruthy();
			expect(button.y, `${where}: ${button.id} on the row under the mouse (${row!.label})`).toBe(row!.y);
		}
	}
	expect(buttonsSeen, `${where}: row buttons appeared while the mouse was over the rows`).toBeGreaterThan(0);
}

test("U24 切换仪表盘列表 @U24", async () => {
	await openDashboard();
	// The dashboard opens on the tab it was last on
	if ((await widget("DashboardScreen.buttonTabStations")).active) {
		await switchTab("buttonTabStations");
	}
	// The list is filled on the screen's first frames
	await waitFor("the station list", async () => (await list(LIST)).total > 0);

	const stations = await list(LIST);
	expect(stations.total, "the station list is longer than a page").toBeGreaterThan(stations.rowsShown);
	await sweep("stations");

	// Scrolled to the end of the long list, then to the short ones, moving the mouse each time
	for (let i = 0; i < 5; i++) {
		await scroll(stations.x + 20, stations.y + 60, -3);
	}
	expect((await list(LIST)).scroll, "the station list scrolled").toBeGreaterThan(0);
	await move(stations.x + 10, stations.y + stations.height - 5);

	for (const tab of ["buttonTabRoutes", "buttonTabDepots", "buttonTabStations", "buttonTabDepots", "buttonTabRoutes"]) {
		await switchTab(tab as any);
		await sweep(tab);
	}

	const routes = await list(LIST);
	expect(routes.rows.map(r => r.label).sort()).toEqual(["测试线 Test Line", "测试线回程 Test Line Back"].sort());
	await switchTab("buttonTabDepots");
	const depots = await list(LIST);
	expect(depots.rows.map(r => r.label).sort()).toEqual(["东车厂 East Depot", "西车厂 West Depot"].sort());
});
