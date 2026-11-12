// Ways through MTR's own screens that several tests take, as a player would take them.
import {click, closeAll, hoverRow, list, openDashboard, switchTab, type, waitFor, waitScreen, widget} from "./game";

const DASHBOARD_LIST = "DashboardScreen.dashboardList";

/** Dashboard → routes → the route's edit button → EditRouteScreen */
export async function openRouteEdit(routeLabel: string) {
	await openDashboard();
	await switchTab("buttonTabRoutes");
	// Routes are grouped by line, each group folded ("[+] 1号线 Line 1 (2)"): open the group the route is in
	for (let i = 0; i < 10; i++) {
		const rows = (await list(DASHBOARD_LIST)).rows;
		if (rows.some(r => !r.header && (r.label === routeLabel || r.label.includes(routeLabel)))) {
			break;
		}
		const groups = rows.filter(r => r.header && r.label.startsWith("[+]"))
			.map(r => ({row: r, line: r.label.replace("[+] ", "").split(" ")[0]}))
			.filter(g => routeLabel.startsWith(g.line))
			.sort((a, b) => b.line.length - a.line.length);
		if (!groups.length) {
			break;
		}
		await click({x: groups[0].row.x + 10, y: groups[0].row.y + groups[0].row.height / 2});
		await waitFor("the group opened", async () => !(await list(DASHBOARD_LIST)).rows.some(r => r.label === groups[0].row.label));
	}
	await hoverRow(DASHBOARD_LIST, routeLabel);
	await click(`${DASHBOARD_LIST}.buttonEdit`);
	return waitScreen("EditRouteScreen");
}

/** … → "时刻表" → TimetableScreen */
export async function openTimetable(routeLabel: string) {
	await openRouteEdit(routeLabel);
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
