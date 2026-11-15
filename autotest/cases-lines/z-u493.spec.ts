// U493 归属层的逻辑图像地铁图: the new page's ownership layer draws its logical map like a metro map (0.83.7,
// editor-web/src/views/NetworkSchematic.tsx LogicalCorridor, layout in editor-web/src/core/abstractNet.ts): every piece
// of a corridor level, upright or at 45°, bent where the stations don't line up, double track as parallel lines.
// Read only (nothing painted), so it belongs in the observe stage (playwright.config.ts) beside run.spec.ts.
// Covered here, over the six lines' fixture: the map is drawn with stations and corridors, every segment of every
// corridor polyline is at a multiple of 45°, and the page reports no errors. Station labels that overlap and corridor
// segments that cross are counted for the report only; the screenshot is for a person to judge the look.
import {test, expect} from "../lib/test";
import {lines} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

/** How far off a multiple of 45° a segment may be (degrees) */
const TOLERANCE = 1;

type Pt = [number, number];
interface Segment {corridor: number, from: Pt, to: Pt}

function crosses(s: Segment, t: Segment): boolean {
	const cross = (o: Pt, a: Pt, b: Pt) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
	const d1 = cross(t.from, t.to, s.from), d2 = cross(t.from, t.to, s.to), d3 = cross(s.from, s.to, t.from), d4 = cross(s.from, s.to, t.to);
	return d1 * d2 < -1e-6 && d3 * d4 < -1e-6;
}

test("U493 归属层的逻辑图：区间只走水平、竖直和 45° @lines @U493", async ({page}, testInfo) => {
	expect(lines.length, "the six lines' fixture (MTM_FIXTURE_NAME=lines)").toBeGreaterThan(0);
	const {errors} = await openNextEditor(page, "owners");
	const logical = page.getByRole("group", {name: "视图"}).getByRole("button", {name: "逻辑图"});
	await expect(logical).toBeVisible({timeout: 20_000});
	if (await logical.getAttribute("aria-pressed") !== "true") await logical.click();
	const svg = page.locator('svg[aria-label="全网逻辑图"]');
	await expect(svg).toBeVisible({timeout: 20_000});
	await expect(svg.locator("polyline").first()).toBeAttached({timeout: 20_000});
	await page.getByRole("button", {name: "全图"}).click();
	await page.waitForTimeout(500);

	// Each corridor is a <g> of polylines (one per track, plus a transparent one to paint on while a brush is chosen)
	const corridors: Pt[][][] = await svg.locator(":scope > g").evaluateAll(groups => groups
		.filter(g => g.querySelector(":scope > polyline"))
		.map(g => [...g.querySelectorAll(":scope > polyline")]
			.filter(p => p.getAttribute("stroke") !== "transparent")
			.map(p => (p.getAttribute("points") || "").trim().split(/\s+/).map(xy => xy.split(",").map(Number) as [number, number]))));
	const stations = await svg.locator('g[role="button"]').count();
	expect(stations, "stations on the map").toBeGreaterThan(0);
	expect(corridors.length, "corridors on the map").toBeGreaterThan(0);

	const segments: Segment[] = [];
	const off: string[] = [];
	let tracks = 0, multi = 0;
	corridors.forEach((polylines, corridor) => {
		if (polylines.length > 1) multi++;
		for (const points of polylines) {
			tracks++;
			for (let i = 1; i < points.length; i++) {
				const [x0, y0] = points[i - 1], [x1, y1] = points[i];
				if (Math.hypot(x1 - x0, y1 - y0) < 0.5) continue;
				const degrees = Math.atan2(y1 - y0, x1 - x0) * 180 / Math.PI;
				const miss = Math.abs(degrees - Math.round(degrees / 45) * 45);
				if (miss > TOLERANCE) off.push(`corridor ${corridor}: (${x0.toFixed(1)},${y0.toFixed(1)}) → (${x1.toFixed(1)},${y1.toFixed(1)}) at ${degrees.toFixed(1)}°`);
				// One track per corridor is enough to look for crossings
				if (points === polylines[0]) segments.push({corridor, from: [x0, y0], to: [x1, y1]});
			}
		}
	});

	// For the report: segments of different corridors crossing, and station labels overlapping
	let crossings = 0;
	for (let i = 0; i < segments.length; i++) {
		for (let j = i + 1; j < segments.length; j++) {
			if (segments[i].corridor !== segments[j].corridor && crosses(segments[i], segments[j])) crossings++;
		}
	}
	const labels = await svg.locator('g[role="button"] > text').evaluateAll(texts => texts.map(t => {
		const r = t.getBoundingClientRect();
		return {name: t.textContent || "", left: r.left, top: r.top, right: r.right, bottom: r.bottom};
	}));
	const overlaps: string[] = [];
	for (let i = 0; i < labels.length; i++) {
		for (let j = i + 1; j < labels.length; j++) {
			const a = labels[i], b = labels[j];
			if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) overlaps.push(`${a.name} / ${b.name}`);
		}
	}

	const shot = testInfo.outputPath("u493-ownership-map.png");
	await page.screenshot({path: shot});
	await testInfo.attach("ownership logical map", {path: shot, contentType: "image/png"});
	await testInfo.attach("map summary", {body: [
		`${stations} stations, ${corridors.length} corridors (${multi} drawn with more than one track), ${tracks} track polylines, ${segments.length} segments`,
		`segments off 45°: ${off.length}`, ...off,
		`crossings between corridors: ${crossings}`,
		`overlapping station labels: ${overlaps.length}`, ...overlaps,
	].join("\n")});

	expect(off, "every corridor segment level, upright or at 45°").toEqual([]);
	expect(errors).toEqual([]);
});
