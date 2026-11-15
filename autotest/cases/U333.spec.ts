// U333 车站范围编辑（高）: in the dashboard's station page, editing a station's area: the rectangle chosen with "范围 n/m"
// (a 3D one, set with the station area tool, shows its heights on the button and a yellow frame, and the map says it is
// locked: right-click only); a click on the map without dragging leaves the area as it was; a right-click on one of the
// station's rectangles takes it away (the main one: the next becomes the main), and the station's only rectangle stays,
// with a message; with a 3D rectangle chosen a left drag only pans; a 2D one is drawn again by dragging and is 2D then.
// Everything is kept after closing and opening again. The hover text over the map "站名 · 范围 n：3D（高度…）/ 2D（所有高度）".
// Basic fixture, station 丙站 (main area x 55–95, z −10–10, 2D). The test adds a small 3D rectangle (x 73–77, z −2–2, the
// heights of the two blocks clicked with the tool) in the middle of 丙站's area and takes it away again with the
// right-click, so 丙站 ends as it began (U44/U45, later in file order, need its platform inside it). A stone block put
// down for the tool's second corner is taken away again.
// Covered here (server and client data, the button's text, the map's message lines): the tool adding a 3D rectangle; its
// heights on the button and the lock message; a left click without a drag on the 3D and on the 2D rectangle changing
// nothing; the right-click taking the smaller rectangle under the mouse away ("已删除范围 2"); the right-click on the only
// one refused ("车站只剩这一块范围，不能删除"); all of it kept when the dashboard is opened again. Screenshots of the frame.
// Not covered (the test driver can't): the hover text (the map draws it at the real cursor, which /move doesn't set;
// ClientDriver would need to set the cursor too), dragging (no drag in the driver: left-drag panning a locked rectangle,
// redrawing a 2D one), deleting the main rectangle so that the next one becomes main (丙站 couldn't be put back to a 2D
// main area without a drag), leaving and joining the world again.
import {test, expect} from "../lib/test";
import {FIXTURE_Y, clientData, click, closeAll, fixture, giveOnly, hoverRow, openDashboard, screenshot, serverCommand, serverData, sleep, state, switchTab, teleport, useOn, waitFor, waitScreen, waitText, widget} from "../lib/game";

const LIST = "DashboardScreen.dashboardList";
const AREA_BUTTON = "DashboardScreen.buttonStationArea";
/** IGui.PANEL_WIDTH: the map takes the screen right of the dashboard's panel (DashboardScreen.init) */
const PANEL_WIDTH = 144;
const NAME = "丙站";
const SMALL = [73, -2, 77, 2];

const stationIn = (data: any) => data.stations.find((s: any) => String(s.id) === String(fixture.stationC));
/** What of a station's area the save keeps: the main corners, the other rectangles, their heights */
const areaOf = (station: any) => ({
	main: [station.x_min, station.z_min, station.x_max, station.z_max],
	extra: station.extra_areas || [],
	heights: station.area_heights || [],
});

/** The middle of the map, where "find" put the station's area */
async function mapMiddle() {
	const screen = await state();
	return {x: Math.round(PANEL_WIDTH + (screen.guiWidth - PANEL_WIDTH) / 2), y: Math.round(screen.guiHeight / 2)};
}

/** Dashboard → stations → 丙站: "find" (the map centred on its area), then edit its area */
async function editArea() {
	await openDashboard();
	// The dashboard opens on the tab it was left on; the tab shown is greyed out
	if ((await widget("DashboardScreen.buttonTabStations")).active) await switchTab("buttonTabStations");
	await hoverRow(LIST, NAME);
	await click(`${LIST}.buttonFind`);
	await hoverRow(LIST, NAME);
	await click(`${LIST}.buttonDrawArea`);
	await waitFor("the station's area being edited", async () => (await widget(AREA_BUTTON)).visible);
}

/** Waits a moment, then checks the server and the client still have the area as given */
async function unchanged(expected: ReturnType<typeof areaOf>, what: string) {
	await sleep(1000);
	expect(areaOf(stationIn(await serverData())), `${what}: the server's area`).toEqual(expected);
	expect(areaOf(stationIn(await clientData())), `${what}: the client's area`).toEqual(expected);
}

test("U333 车站范围编辑：3D 锁定、点一下不变、右键删块、只剩一块不能删 @U333", async ({}, testInfo) => {
	test.setTimeout(3 * 60_000);
	const y = FIXTURE_Y;
	const original = areaOf(stationIn(await serverData()));
	expect(original.main, "丙站 as the fixture built it").toEqual([55, -10, 95, 10]);
	expect(original.extra, "no other rectangles yet").toEqual([]);
	const [x1, z1, x2, z2] = SMALL;
	const top = y + 2;

	try {
		// The station area tool: the grass at one corner, a stone block higher up at the other; the box is given to 丙站 as a
		// new rectangle, with the heights between the two blocks
		await serverCommand(`setblock ${x2} ${top} ${z2} minecraft:stone`);
		// Near enough for both clicks: the server takes a click on a block only within 6 blocks of the eyes (from z1 - 4.5
		// the stone at z2 was 9 blocks away, and the second click was dropped)
		await teleport((x1 + x2) / 2 + 0.5, y, z1 - 0.5);
		await giveOnly("mtr:station_area_tool");
		await useOn(x1, y - 1, z1, "up");
		await useOn(x2, top, z2, "up");
		await waitScreen("StationAreaToolScreen");
		const stationButton = await waitFor("丙站 offered", async () => (await state()).widgets.find(w => w.id.startsWith("StationAreaToolScreen.buttons[") && w.text.includes(NAME) && w.visible));
		await click(stationButton.id);
		await waitFor("the rectangles of 丙站 offered", async () => (await state()).widgets.some(w => w.text === "新增一块范围" && w.visible));
		await click({text: "新增一块范围"});
		await waitScreen(null);
		const withSmall = await waitFor("the new rectangle on the server", async () => {
			const area = areaOf(stationIn(await serverData()));
			return area.extra.length === 4 ? area : undefined;
		});
		expect(withSmall.main, "the main area unchanged").toEqual(original.main);
		expect(withSmall.extra, "the box clicked").toEqual(SMALL);
		expect(withSmall.heights, "with the heights between the two blocks (3D)").toEqual([...SMALL, y - 1, top]);

		await editArea();
		const middle = await mapMiddle();
		expect((await widget(AREA_BUTTON)).text, "two rectangles, the main one chosen").toBe("范围 1/2");

		// The 3D rectangle: its heights on the button, locked
		await click(AREA_BUTTON);
		await waitFor("the 3D rectangle chosen", async () => (await widget(AREA_BUTTON)).text === `范围 2/2 Y${y - 1}-${top}`);
		await waitText(new RegExp(`这块是 3D 范围（高度 ${y - 1} 到 ${top}），这里只能右键删除`));
		await testInfo.attach("3D rectangle chosen", {path: await screenshot("U333-3d-chosen")});
		// A left click on the map (no drag) changes nothing
		await click({x: middle.x, y: middle.y});
		await unchanged(withSmall, "a left click with the 3D rectangle chosen");

		// The 2D main area: a click without dragging leaves it as it was
		await click(AREA_BUTTON);
		await waitFor("the main area chosen again", async () => (await widget(AREA_BUTTON)).text === "范围 1/2");
		await waitFor("no lock message for the 2D area", async () => !(await state()).texts.some(t => t.text.includes("这块是 3D 范围")));
		await click({x: middle.x, y: middle.y});
		await unchanged(withSmall, "a left click without dragging on the 2D area");
		await testInfo.attach("2D area chosen after a click", {path: await screenshot("U333-2d-after-click")});

		// Right-click in the middle: the smaller rectangle there (the 3D one) goes, with its heights; the main stays
		await click({x: middle.x, y: middle.y}, {button: 1});
		await waitText(/已删除范围 2/);
		const back = await waitFor("the rectangle gone on the server", async () => {
			const area = areaOf(stationIn(await serverData()));
			return area.extra.length === 0 ? area : undefined;
		});
		expect(back, "丙站 as it began").toEqual(original);
		expect((await widget(AREA_BUTTON)).text).toBe("范围 1/1");

		// Right-click on the only rectangle: refused
		await click({x: middle.x, y: middle.y}, {button: 1});
		await waitText(/车站只剩这一块范围，不能删除/);
		await unchanged(original, "the only rectangle right-clicked");

		// Kept after closing and opening again
		await click("DashboardScreen.buttonDoneEditingStation");
		await closeAll();
		await editArea();
		expect((await widget(AREA_BUTTON)).text, "one rectangle after opening again").toBe("范围 1/1");
		await unchanged(original, "opened again");
		await closeAll();
	} finally {
		await closeAll();
		await serverCommand(`setblock ${x2} ${top} ${z2} minecraft:air`);
		// A failure half way may leave the small rectangle: right-click it away (the smaller one under the middle)
		if (areaOf(stationIn(await serverData())).extra.length) {
			await editArea();
			const middle = await mapMiddle();
			for (let i = 0; i < 3 && areaOf(stationIn(await serverData())).extra.length; i++) {
				await click({x: middle.x, y: middle.y}, {button: 1});
				await sleep(1000);
			}
			await closeAll();
		}
	}
});
