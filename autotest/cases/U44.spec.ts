// U44 车站配线图（中）: in "编辑停站", clicking a station's name opens that station's track layout: trains run to the
// right, the previous and next stations are named left and right, a track clicked on the drawing is pinned like in the
// connection chart, ＋/− zoom.
// Covered here: all of the above at the middle station of "测试线回程" (丙站 → 乙站 → 甲站), and no page errors.
// Not covered (the fixture has none): passing loops, switches and crossovers, curves, other lines' platforms nearby,
// greyed platforms of other stations, the phone width. A screenshot is kept for review.
import {test, expect} from "../lib/test";
import {fixture} from "../lib/game";
import {openEditor, routeMenu} from "../lib/editor";

test("U44 车站配线图 @U44", async ({page}, testInfo) => {
	const {errors} = await openEditor(page);
	await routeMenu(page, fixture.routeBack, "编辑停站…");
	const dialog = page.locator("dialog[open]");
	await expect(dialog.locator("h2")).toContainText("测试线回程");

	// The middle station's column head opens its layout
	await dialog.locator('[data-station-col="1"]').dispatchEvent("click");
	const layout = dialog.locator(".layout");
	await expect(layout.locator(".diagram-head")).toContainText("乙站");
	const svg = layout.locator("svg.layout-svg");
	await expect(svg, "the layout loads").toBeVisible({timeout: 20_000});

	// Trains run to the right: the station before is on the left, the one after on the right
	const ends = layout.locator(".layout-ends span");
	await expect(ends.nth(0)).toContainText("丙站");
	await expect(ends.nth(1)).toContainText("甲站");
	expect(await svg.locator("polyline.rail").count(), "rails are drawn").toBeGreaterThan(0);
	const tracks = svg.locator("g[data-track]");
	expect(await tracks.count(), "the station's own track can be picked").toBeGreaterThan(0);

	await testInfo.attach("station diagram", {body: await layout.screenshot(), contentType: "image/png"});

	// ＋ zooms in (the view box gets smaller), − back out
	const viewBox = async () => (await svg.getAttribute("viewBox"))!.split(" ").map(Number);
	const before = await viewBox();
	await layout.locator('[data-zoom="in"]').click();
	await expect.poll(async () => (await viewBox())[2], {message: "zoomed in"}).toBeLessThan(before[2]);
	const zoomed = (await viewBox())[2];
	await layout.locator('[data-zoom="out"]').click();
	await expect.poll(async () => (await viewBox())[2], {message: "zoomed out"}).toBeGreaterThan(zoomed);

	// A track clicked on the drawing is pinned
	await tracks.first().dispatchEvent("click");
	await expect(svg.locator("g[data-track] title").first()).toContainText("已固定");

	expect(errors, "no errors in the page").toEqual([]);
});
