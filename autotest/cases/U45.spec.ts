// U45 线路配线图（中）: right-click a route in the tree, "线路配线图…": the whole line laid out left to right, station names
// in order, the route's path in the route's colour, the platforms it stops at named; ＋/− zoom, 1×/2×/3× side scale;
// clicking a station name opens "编辑停站" at that station.
// Covered here: all of the above on the fixture's straight line "测试线回程" (C → B → A), and no page errors.
// Not covered (the fixture has none): passing loops, crossovers, branches, depot tracks, other lines crossing, back and
// forth routes, stations marked red or yellow, a route with no path, the phone width. A screenshot is kept for review.
import {test, expect} from "../lib/test";
import {fixture} from "../lib/game";
import {openEditor, routeMenu} from "../lib/editor";

test("U45 线路配线图 @U45", async ({page}, testInfo) => {
	const {errors} = await openEditor(page);
	await routeMenu(page, fixture.routeBack, "线路配线图…");

	const dialog = page.locator("dialog[open]");
	await expect(dialog.locator("h2")).toContainText("测试线回程");
	const svg = dialog.locator("svg.route-diagram");
	await expect(svg, "the layout loads").toBeVisible({timeout: 20_000});
	await expect(dialog, "the route has a path").not.toContainText("寻不出");

	// Stations left to right in the order the route runs
	const stops = dialog.locator("[data-route-stop]");
	await expect(stops).toHaveCount(3);
	const names: {name: string, x: number}[] = [];
	for (let i = 0; i < 3; i++) {
		const text = stops.nth(i).locator("text");
		names.push({name: (await text.textContent()) || "", x: Number(await text.getAttribute("x"))});
	}
	names.sort((a, b) => a.x - b.x);
	expect(names.map(n => n.name)).toEqual(["丙站", "乙站", "甲站"]);

	// The path and the route's own platforms in the route's colour (0xFB8C00), each platform named
	const style = ((await svg.locator("line.route-path").getAttribute("style")) || "").toLowerCase().replace(/\s/g, "");
	expect(style).toMatch(/stroke:(#fb8c00|rgb\(251,140,0\))/);
	await expect(svg.locator("g.platform.ours")).toHaveCount(3);
	for (const label of await svg.locator("g.platform.ours text").allTextContents()) {
		expect(label.trim(), "each platform of the route is named").not.toBe("");
	}

	await testInfo.attach("route diagram", {body: await dialog.screenshot(), contentType: "image/png"});

	// ＋ widens the drawing, − narrows it; 3× makes it taller than 1×
	const width = async () => Number(await svg.getAttribute("width"));
	const height = async () => Number(await svg.getAttribute("height"));
	const start = await width();
	await dialog.locator('[data-route-zoom="in"]').click();
	expect(await width()).toBeGreaterThan(start);
	await dialog.locator('[data-route-zoom="out"]').click();
	await dialog.locator('[data-route-zoom="out"]').click();
	expect(await width()).toBeLessThan(start);
	await dialog.locator('[data-route-side="1"]').click();
	const flat = await height();
	await dialog.locator('[data-route-side="3"]').click();
	expect(await height()).toBeGreaterThan(flat);

	// A station name opens the stop editor
	await stops.first().locator("text").dispatchEvent("click");
	await expect(page.locator("dialog[open] h2")).not.toContainText("线路配线图");
	await expect(page.locator("dialog[open]")).toBeVisible();

	expect(errors, "no errors in the page").toEqual([]);
});
