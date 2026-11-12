// U44 车站配线图（中）, in the new editor: a route's stops panel ("编辑停站", opened by clicking the route in the tree),
// "站内配线…" at a station opens that station's track layout: trains run to the right, the previous and next stations
// are named left and right, a track clicked on the drawing is pinned like in the station list, ＋/− zoom, ‹ › go to the
// stations before and after.
// Covered here: all of the above at the middle station of "测试线回程" (丙站 → 乙站 → 甲站), and no page errors.
// Not covered (the fixture has none): passing loops, switches and crossovers, curves, other lines' platforms nearby,
// greyed platforms of other stations, the phone width. A screenshot is kept for review.
import {test, expect} from "../lib/test";
import {fixture} from "../lib/game";
import {openNextEditor, routeRow} from "../lib/editor";

test("U44 车站配线图（新版） @U44", async ({page}, testInfo) => {
	const {errors} = await openNextEditor(page, "lines");
	await (await routeRow(page, fixture.routeBack)).click();
	const stops = page.getByRole("list", {name: "车站和股道"});
	await expect(stops, "the stops panel opens").toBeVisible();
	await expect(stops.locator("li[data-stop]")).toHaveCount(3);

	await stops.locator('li[data-stop="1"]').getByRole("button", {name: "站内配线…"}).click();
	const layout = page.getByRole("region", {name: "乙站 配线图"});
	await expect(layout).toBeVisible();
	const svg = layout.getByRole("img", {name: "配线图"});
	await expect(svg, "the layout loads").toBeVisible({timeout: 20_000});

	// Trains run to the right: the station before on the left, the one after on the right
	await expect(layout.getByText("← 丙站")).toBeVisible();
	await expect(layout.getByText("甲站 →")).toBeVisible();
	const tracks = svg.locator("g[data-track]");
	expect(await tracks.count(), "the station's own track can be picked").toBeGreaterThan(0);

	await testInfo.attach("station diagram", {body: await layout.screenshot(), contentType: "image/png"});

	// ＋ zooms in (the view box gets smaller), − back out
	const width = async () => Number((await svg.getAttribute("viewBox"))!.split(" ")[2]);
	const before = await width();
	await layout.getByRole("button", {name: "放大"}).click();
	await expect.poll(width, {message: "zoomed in"}).toBeLessThan(before);
	const zoomed = await width();
	await layout.getByRole("button", {name: "缩小"}).click();
	await expect.poll(width, {message: "zoomed out"}).toBeGreaterThan(zoomed);

	// A track clicked on the drawing is pinned
	await tracks.first().dispatchEvent("click");
	await expect(svg.locator("g[data-track] title").first()).toContainText("已固定");

	// ‹ › go to the stations before and after
	await layout.getByTitle("下一站").click();
	await expect(page.getByRole("region", {name: "甲站 配线图"})).toBeVisible();
	await page.getByRole("region", {name: "甲站 配线图"}).getByTitle("上一站").click();
	await page.getByRole("region", {name: "乙站 配线图"}).getByTitle("上一站").click();
	await expect(page.getByRole("region", {name: "丙站 配线图"})).toBeVisible();

	expect(errors, "no errors in the page").toEqual([]);
});
