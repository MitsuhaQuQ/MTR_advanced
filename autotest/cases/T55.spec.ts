// T55 下一段的界面（中）: choosing the next route (through service), changing its service, choosing where to leave, and
// clearing all work; switching services and tables shows what belongs to each.
// Covered here: choosing "测试线回程" as the next segment of a 测试线 service (the selector screen and back), its service
// field, changing the service, clearing, a second service showing no link while the first keeps its own, a new table
// starting without links, and the link reaching the server when the screen closes.
// Not covered: "转出站"/"接入站" (shown only with timetable boundaries, which the fixture has none of).
import {test, expect} from "../lib/test";
import {byName, click, hoverRow, serverData, type, waitFor, waitScreen, widget} from "../lib/game";
import {addService, closeAll, openTimetable, selectService} from "../lib/screens";

const LINK = "TimetableScreen.buttonLinkRoute";
const LINK_SERVICE = "TimetableScreen.textFieldLinkService";
const CLEAR = "TimetableScreen.buttonClearLink";

async function chooseNextRoute(label: string) {
	await click(LINK);
	await waitScreen("DashboardListSelectorScreen");
	await hoverRow("DashboardListSelectorScreen.availableList", label);
	await click("DashboardListSelectorScreen.availableList.buttonAdd");
	await click("DashboardListSelectorScreen.buttonDone");
	await waitScreen("TimetableScreen");
}

test("T55 下一段的界面 @T55", async () => {
	await openTimetable("测试线 Test Line");
	await addService("06:00:00");
	await selectService("06:00:00");
	expect((await widget(LINK)).text).toContain("无");

	await chooseNextRoute("测试线回程");
	await waitFor("the next route on the button", async () => (await widget(LINK)).text.includes("测试线回程"));
	expect((await widget(LINK_SERVICE)).visible, "its service can be typed").toBe(true);
	expect((await widget(CLEAR)).visible).toBe(true);

	await type(LINK_SERVICE, "06:30:00", true);
	expect((await widget(LINK_SERVICE)).value).toBe("06:30:00");

	// Another service has no link; back on the first, its link is shown again
	await addService("06:20:00");
	await selectService("06:20:00");
	await waitFor("no link on the other service", async () => (await widget(LINK)).text.includes("无"));
	expect((await widget(LINK_SERVICE)).visible).toBe(false);
	await selectService("06:00:00");
	await waitFor("the first service's link", async () => (await widget(LINK)).text.includes("测试线回程"));
	expect((await widget(LINK_SERVICE)).value).toBe("06:30:00");

	// A new table starts without services or links
	await click("TimetableScreen.buttonAddTable");
	await waitFor("the second table", async () => (await widget("TimetableScreen.buttonTable")).text === "2/2");
	expect((await widget(LINK)).visible, "no service, no next segment").toBe(false);
	await click("TimetableScreen.buttonDeleteTable");
	await waitFor("back to one table", async () => (await widget("TimetableScreen.buttonTable")).text === "1/1");

	// Saved when the screen closes: the server has the link from 测试线 to 测试线回程
	await selectService("06:00:00");
	await waitFor("the first service's link", async () => (await widget(LINK)).text.includes("测试线回程"));
	await closeAll();
	const back = byName((await serverData()).routes, "测试线回程");
	const saved = await waitFor("the link on the server", async () => {
		const route = byName((await serverData()).routes, "测试线|");
		return JSON.stringify(route).includes(String(back.id)) ? route : undefined;
	});
	expect(saved).toBeTruthy();

	// Cleared, the button says none again
	await openTimetable("测试线 Test Line");
	await selectService("06:00:00");
	await click(CLEAR);
	await waitFor("the link cleared", async () => (await widget(LINK)).text.includes("无"));
	expect((await widget(LINK_SERVICE)).visible).toBe(false);
});
