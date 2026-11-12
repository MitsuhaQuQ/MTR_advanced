// U45 线路配线图（中）, in the new editor: right-click a route type in the tree, "线路配线图…": the whole line laid out
// left to right, station names in order, the route's own tracks in the route's colour, the platforms named; ＋/− zoom;
// clicking a station name opens the stops panel at that station.
// Covered here: all of the above on the fixture's straight line "测试线回程" (丙站 → 乙站 → 甲站), and no page errors.
// Not covered (the fixture has none): passing loops, crossovers, branches, depot tracks, other lines crossing, back and
// forth routes, stations marked red or yellow, a route with no path, the phone width. A screenshot is kept for review.
import {test, expect} from "../lib/test";
import {fixture} from "../lib/game";
import {openNextEditor, routeMenu} from "../lib/editor";

test("U45 线路配线图（新版） @U45", async ({page}, testInfo) => {
	const {errors} = await openNextEditor(page, "lines");
	await routeMenu(page, fixture.routeBack, "线路配线图…");

	const dialog = page.getByRole("dialog", {name: /线路配线图：测试线回程/});
	await expect(dialog).toBeVisible();
	const svg = dialog.getByRole("img", {name: "线路配线图"});
	await expect(svg, "the layout loads").toBeVisible({timeout: 20_000});
	await expect(dialog, "the route has a path").not.toContainText("寻不出");

	// Station names left to right in the order the route runs, each with its stop number
	const heads = svg.locator("g.cursor-pointer");
	await expect(heads).toHaveCount(3);
	const names: {name: string, x: number}[] = [];
	for (let i = 0; i < 3; i++) {
		const text = heads.nth(i).locator("text").first();
		names.push({name: (await text.textContent()) || "", x: Number(await text.getAttribute("x"))});
	}
	names.sort((a, b) => a.x - b.x);
	expect(names.map(n => n.name)).toEqual(["丙站", "乙站", "甲站"]);
	await expect(heads.filter({hasText: "第 1 站"})).toHaveCount(1);

	// The route's own tracks drawn thick in its colour (0xFB8C00); its platforms named
	const ours = svg.locator('line[stroke-width="5"]');
	expect(await ours.count(), "the route's tracks are drawn in its colour").toBeGreaterThan(0);
	for (const stroke of await ours.evaluateAll(lines => lines.map(l => l.getAttribute("stroke")))) {
		expect((stroke || "").toLowerCase()).toMatch(/#fb8c00|rgb\(251,\s*140,\s*0\)/);
	}
	const labels = await svg.locator("text.num").allTextContents();
	expect(labels.length, "platforms are named").toBeGreaterThanOrEqual(3);
	expect(labels.every(l => l.trim() !== "" && l.trim() !== "?"), `every platform has a name: ${labels.join(", ")}`).toBe(true);

	await testInfo.attach("route diagram", {body: await dialog.screenshot(), contentType: "image/png"});

	// ＋ widens the drawing, − narrows it
	const width = async () => Number(await svg.getAttribute("width"));
	const start = await width();
	await dialog.getByRole("button", {name: "放大"}).click();
	await expect.poll(width).toBeGreaterThan(start);
	await dialog.getByRole("button", {name: "缩小"}).click();
	await dialog.getByRole("button", {name: "缩小"}).click();
	await expect.poll(width).toBeLessThan(start);

	// A station name opens the stops panel at that station
	await heads.first().dispatchEvent("click");
	await expect(dialog).toBeHidden();
	await expect(page.getByRole("list", {name: "车站和股道"}).or(page.getByRole("region", {name: /配线图$/}))).toBeVisible();

	expect(errors, "no errors in the page").toEqual([]);
});
