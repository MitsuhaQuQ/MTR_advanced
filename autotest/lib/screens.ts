// Ways through MTR's own screens that several tests take, as a player would take them.
import {click, closeAll, hoverRow, list, openDashboard, scroll, sleep, switchTab, type, waitFor, waitScreen, widget} from "./game";

const DASHBOARD_LIST = "DashboardScreen.dashboardList";

/**
 * Opens the folded group ("[+] 9号线C Line 9 C (2)") a route's row is in, in a list that groups routes by line: the group
 * whose line name the route's name starts with, or else, as a line's routes may be named anything ("Local Up" in line
 * "S1"), each folded group in turn until the route shows.
 */
export async function openGroupFor(listId: string, routeLabel: string) {
	const tried = new Set<string>();
	for (let i = 0; i < 20; i++) {
		const rows = (await list(listId)).rows;
		if (rows.some(r => !r.header && r.label.includes(routeLabel))) return;
		const folded = rows.filter(r => r.label.startsWith("[+]") && !tried.has(r.label))
			.map(r => ({row: r, line: r.label.replace("[+] ", "").split(" ")[0]}));
		const byName = folded.filter(g => routeLabel.startsWith(g.line)).sort((a, b) => b.line.length - a.line.length);
		// The group named so may be further down than the list shows: scroll down to it before opening others
		if (!byName.length && await scrollToGroup(listId, routeLabel, tried)) continue;
		const group = byName[0] || folded[0];
		if (!group) return;
		tried.add(group.row.label);
		await click({x: group.row.x + 10, y: group.row.y + group.row.height / 2});
		await waitFor("the group opened", async () => !(await list(listId)).rows.some(r => r.label === group.row.label));
	}
}

/**
 * Scrolls a list down, as a player does with the wheel, until a folded group whose line name the route's name starts
 * with shows.
 *
 * @return true if one shows
 */
async function scrollToGroup(listId: string, routeLabel: string, tried: Set<string>): Promise<boolean> {
	let current = await list(listId);
	if (!current.rows.length || current.total <= current.rows.length) return false;
	const has = () => current.rows.some(r => r.label.startsWith("[+]") && !tried.has(r.label) && routeLabel.startsWith(r.label.replace("[+] ", "").split(" ")[0]));
	const anchor = current.rows[0];
	for (let i = 0; i < 40 && !has(); i++) {
		const before = current.rows.map(r => r.label).join("|");
		await scroll(anchor.x + 10, anchor.y + anchor.height / 2, -1);
		await sleep(50);
		current = await list(listId);
		if (current.rows.map(r => r.label).join("|") === before) break;
	}
	return has();
}

/** Types into the dashboard's search box, which filters every tab's list and stays while the game runs */
export async function setDashboardSearch(text: string) {
	await type(`${DASHBOARD_LIST}.textFieldSearch`, text, true);
}

/**
 * Dashboard → routes → the route's edit button → EditRouteScreen
 *
 * @param nth which of several routes with the same name, in the list's order
 */
export async function openRouteEdit(routeLabel: string, nth = 0) {
	await openDashboard();
	await switchTab("buttonTabRoutes");
	// Routes are grouped by line, each group folded ("[+] 1号线 Line 1 (2)"), except while searching, when the dashboard
	// lists every route on its own (DashboardScreen.updateRouteRows): search for it, as a player finds a route in a long
	// list whatever line it is in (Q-world run: "Local Up" in line S1, the two routes named 环线 Loop Line)
	await setDashboardSearch(routeLabel);
	await waitFor("the search to list the route", async () => (await list(DASHBOARD_LIST)).rows.some(r => !r.header && r.label.includes(routeLabel)), 5000)
		.catch(() => openGroupFor(DASHBOARD_LIST, routeLabel));
	await hoverRow(DASHBOARD_LIST, routeLabel, nth);
	await click(`${DASHBOARD_LIST}.buttonEdit`);
	return waitScreen("EditRouteScreen");
}

/** … → "时刻表" → TimetableScreen */
export async function openTimetable(routeLabel: string, nth = 0) {
	await openRouteEdit(routeLabel, nth);
	await click("EditRouteScreen.buttonTimetable");
	return waitScreen("TimetableScreen");
}

/** Dashboard → depots → the depot's edit button → EditDepotScreen */
export async function openDepotEdit(depotLabel: string) {
	await openDashboard();
	await switchTab("buttonTabDepots");
	await hoverRow(DASHBOARD_LIST, depotLabel);
	await click(`${DASHBOARD_LIST}.buttonEdit`);
	return waitScreen("EditDepotScreen");
}

/** "刷新路径" in the depot's screen, as a player finds the depot's paths after building or changing it */
export async function refreshDepotPath(depotLabel: string) {
	await openDepotEdit(depotLabel);
	await click("EditDepotScreen.buttonGenerateRoute");
	await closeAll();
}

export const SERVICES = "TimetableScreen.servicesList";

/** Types a departure (hh:mm:ss, or "07:10:00 + 10 * 00:03:00") and adds it with "+" */
export async function addService(time: string) {
	const before = (await list(SERVICES)).total;
	await type("TimetableScreen.textFieldService", time, true);
	await waitFor("the add button to allow it", async () => (await widget("TimetableScreen.buttonAddService")).active);
	await click("TimetableScreen.buttonAddService");
	await waitFor(`service ${time} in the list`, async () => (await list(SERVICES)).total > before);
}

/** Shows a service: its row's "find" button (the list's first row button), as a player picks it */
export async function selectService(time: string) {
	await waitFor(`service ${time} in the list`, async () => (await list(SERVICES)).rows.find(r => r.label === time));
	await hoverRow(SERVICES, time);
	await click(`${SERVICES}.buttonFind`);
}

/** Ticks "这条线路按时刻表运行" if it is not ticked */
export async function enableTimetable() {
	if (!(await widget("TimetableScreen.checkboxEnabled")).value) {
		await click("TimetableScreen.checkboxEnabled");
		await waitFor("the timetable enabled", async () => (await widget("TimetableScreen.checkboxEnabled")).value === true);
	}
}

/** Deletes the service with its row's delete button */
export async function deleteService(time: string) {
	await hoverRow(SERVICES, time);
	await click(`${SERVICES}.buttonDelete`);
	await waitFor(`service ${time} gone`, async () => !(await list(SERVICES)).rows.some(r => r.label === time));
}

/** Closes screens back to the world; screens that save on closing (timetable, route) send their changes then */
export {closeAll};
