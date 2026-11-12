// U96 删线路时提示残留引用（中）: deleting a route a depot uses lists the depot in the chat, and the route leaves the
// depot's route list by itself.
// Covered here: the depot reference (the fixture's West Depot runs "测试线"). Not covered yet: through-running links
// and duties that use the route (the fixture has neither), and the log line.
// This test deletes "测试线", so it runs last among the tests that need it (file order U9x after U2x).
import {test, expect} from "../lib/test";
import {byName, chatSince, click, clientData, fixture, hoverRow, list, openDashboard, serverData, switchTab, waitFor, waitScreen, widget} from "../lib/game";

const LIST = "DashboardScreen.dashboardList";

test("U96 删线路时提示残留引用 @U96", async ({marks}) => {
	const before = await serverData();
	expect(byName(before.depots, "西车厂").route_ids.map(String), "West Depot runs the route before").toContain(String(fixture.route));

	await openDashboard();
	await switchTab("buttonTabRoutes");
	await hoverRow(LIST, "测试线 Test Line");
	expect((await widget(`${LIST}.buttonDelete`)).visible, "the delete button is on the route's row").toBe(true);
	await click(`${LIST}.buttonDelete`);
	await waitScreen("DeleteConfirmationScreen");
	await click("DeleteConfirmationScreen.buttonYes");
	await waitScreen("DashboardScreen");

	// The chat names the depot the route was taken out of
	const messages = await waitFor("the chat message about the depots", async () => {
		const {messages} = await chatSince(marks.chat);
		return messages.find(m => m.text.includes("西车厂")) ? messages : undefined;
	});
	expect(messages.map(m => m.text).join("\n")).toContain("测试线");

	// The server, and the client's copy, no longer have the route, and the depot no longer lists it
	await waitFor("the route gone on the server", async () => !(await serverData()).routes.some((r: any) => String(r.id) === String(fixture.route)));
	const after = await serverData();
	expect(byName(after.depots, "西车厂").route_ids.map(String)).not.toContain(String(fixture.route));
	await waitFor("the route gone on the client", async () => !(await clientData()).routes.some((r: any) => String(r.id) === String(fixture.route)));
	expect((await list(LIST)).rows.map(r => r.label)).toEqual(["测试线回程 Test Line Back"]);
});
