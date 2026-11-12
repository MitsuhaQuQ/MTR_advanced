// U318 换车型后停着的车画对（高）: in the siding screen, changing the train type several times in a row (without a
// restart) on a siding with any number of trains and on one with a limit takes effect at once.
// Covered here (data): each change reaches the server and the client's copy and shows on the siding screen's button,
// on the fixture's West (no limit) and East (at most one) sidings, opened as a player would from the dashboard.
// Not covered yet: the drawing of a parked train (needs trains in the sidings, so a depot that runs; then screenshots
// for AI judgement), sitting in a train while it changes, after an autosave.
import {test, expect} from "../lib/test";
import {byName, click, clientData, closeAll, fixture, hoverRow, list, openDashboard, serverData, switchTab, type, waitFor, waitScreen, widget} from "../lib/game";

const LIST = "DashboardScreen.dashboardList";
const TRAINS = "SidingScreen.availableTrainsList";

/** Dashboard → depots → the depot's area → its siding → the siding screen */
async function openSiding(depot: string) {
	await openDashboard();
	await switchTab("buttonTabDepots");
	await hoverRow(LIST, depot);
	await click(`${LIST}.buttonDrawArea`);
	await waitFor("the depot's sidings in the list", async () => (await list(LIST)).rows.every(r => r.type === "Siding") && (await list(LIST)).rows.length > 0);
	const siding = (await list(LIST)).rows[0];
	await hoverRow(LIST, siding.label);
	await click(`${LIST}.buttonEdit`);
	return waitScreen("SidingScreen");
}

/** Picks the first train whose name contains text, through the screen's search box and list. */
async function chooseTrain(text: string): Promise<string> {
	await click("SidingScreen.buttonSelectTrain");
	await type(`${TRAINS}.textFieldSearch`, text, true);
	// The list is filtered on the next frame: wait until every row it shows matches, or a stale row would be clicked
	const row = await waitFor(`the list filtered to ${text}`, async () => {
		const rows = (await list(TRAINS)).rows;
		return rows.length > 0 && rows.every(r => r.label.includes(text)) ? rows[0] : undefined;
	});
	await hoverRow(TRAINS, row.label);
	await click(`${TRAINS}.buttonAdd`);
	await waitFor("the train list to close", async () => (await widget("SidingScreen.buttonSelectTrain")).visible);
	return row.label;
}

const sidingOf = (data: any, id: unknown) => data.sidings.find((s: any) => String(s.id) === String(id));

for (const [depot, sidingKey, limited] of [["西车厂", "sidingWest", false], ["东车厂", "sidingEast", true]] as const) {
	test(`U318 换车型：${depot}${limited ? "（限车数）" : "（不限车数）"} @U318`, async () => {
		const id = fixture[sidingKey];
		expect(sidingOf(await serverData(), id).unlimited_trains, "the fixture's limit").toBe(!limited);

		await openSiding(depot);
		let previous = sidingOf(await serverData(), id).train_custom_id;
		// Short trains, as the fixture's sidings are 30 blocks long; each change follows the last without a restart
		for (const name of ["迷你", "小型", "迷你"]) {
			const chosen = await chooseTrain(name);
			expect((await widget("SidingScreen.buttonSelectTrain")).text, "the button shows the train chosen").toBe(chosen);
			const server = await waitFor("the server to take the new train", async () => {
				const current = sidingOf(await serverData(), id);
				return current.train_custom_id !== previous ? current : undefined;
			});
			const client = sidingOf(await clientData(), id);
			expect(client.train_custom_id, "the client's copy has the same train").toBe(server.train_custom_id);
			expect(client.train_type, "and the same base type").toBe(server.train_type);
			previous = server.train_custom_id;
		}
		await closeAll();
		expect(byName((await serverData()).depots, depot).name).toContain(depot);
	});
}
