// U473 车站股道运用图（高，0.80.0）and U486 放大和拖动（中，0.82.3）: clicking a station on the physical layer's schematic
// opens its track use: the tracks around it, each line's trains as arrow lines flowing their way, the legend to show one
// line alone. The wheel zooms in about the pointer (the drawing's view box narrows, lines keep their size on screen),
// dragging moves it, a double click or 复位 shows it whole.
// Covered here: the drawing opens with flows and a legend, one line alone, zooming, dragging, resetting, Esc closing.
// Not covered (looked at): whether the flows read right at a given station.
import {test, expect} from "../lib/test";
import {openNextEditor} from "../lib/editor";

const viewBox = async (svg: import("@playwright/test").Locator) => (await svg.getAttribute("viewBox"))!.split(" ").map(Number);

test("车站股道运用图：流向、图例、放大、拖动、复位 @lines @U473 @U486", async ({page}) => {
	const {errors} = await openNextEditor(page, "physical");
	const schematic = page.getByRole("group", {name: "视图"}).getByRole("button", {name: "示意图"});
	if (await schematic.getAttribute("aria-pressed") !== "true") await schematic.click();
	// A station several lines' trains run through: the third of line 2 (its express trains pass, the others stop)
	// (TestKit's Lines.java names them "<layout>号线<n>站")
	const name = "2号线三站";
	// The labels overlap at the whole network's scale: the click goes to the station itself
	await page.getByRole("button", {name, exact: true}).first().dispatchEvent("click");
	const panel = page.getByRole("dialog", {name: `${name} 股道运用`});
	await expect(panel).toBeVisible({timeout: 15_000});
	const svg = panel.getByRole("img", {name: `${name} 股道运用图`});
	await expect(svg.locator('[data-flow="service"]').first(), "the trains' flows are drawn").toBeAttached();

	// The legend shows one line alone, then all again
	const legend = panel.locator("button[aria-pressed]").filter({hasNotText: "×"});
	const all = await svg.locator("[data-flow]").count();
	if (await legend.count() > 1) {
		await legend.first().click();
		await expect(legend.first()).toHaveAttribute("aria-pressed", "true");
		expect(await svg.locator("[data-flow]").count(), "one line alone shows fewer flows").toBeLessThan(all);
		await legend.first().click();
		await expect(svg.locator("[data-flow]")).toHaveCount(all);
	}

	// Zooming in about the middle: the view box narrows, the zoom shown grows
	await expect(panel.getByText("1×", {exact: true})).toBeVisible();
	const [, , wholeW, wholeH] = await viewBox(svg);
	const box = (await svg.boundingBox())!;
	await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
	for (let i = 0; i < 6; i++) await page.mouse.wheel(0, -400);
	await expect(panel.getByText("1×", {exact: true}), "zoomed in").toHaveCount(0);
	const [x0, y0, w, h] = await viewBox(svg);
	expect(w, "the view box narrows").toBeLessThan(wholeW / 2);
	expect(h).toBeLessThan(wholeH / 2);
	// Lines keep their size on screen: a rail's stroke narrows as the view does
	const stroke = Number(await svg.locator("polyline").first().getAttribute("stroke-width"));
	expect(stroke * wholeW / w, "the stroke scaled back to the whole drawing").toBeGreaterThan(stroke * 2);

	// Dragging moves the view
	await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
	await page.mouse.down();
	await page.mouse.move(box.x + box.width / 2 + 120, box.y + box.height / 2 + 60, {steps: 6});
	await page.mouse.up();
	const [x1, y1] = await viewBox(svg);
	expect(Math.abs(x1 - x0) + Math.abs(y1 - y0), "the view moved").toBeGreaterThan(0);

	// 复位, then a double click after zooming again, show it whole
	await panel.getByRole("button", {name: "复位"}).click();
	await expect(panel.getByText("1×", {exact: true})).toBeVisible();
	expect((await viewBox(svg))[2]).toBeCloseTo(wholeW, 3);
	await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
	await page.mouse.wheel(0, -800);
	await expect(panel.getByText("1×", {exact: true})).toHaveCount(0);
	await svg.dblclick({position: {x: box.width / 2, y: box.height / 2}});
	await expect(panel.getByText("1×", {exact: true})).toBeVisible();

	await page.keyboard.press("Escape");
	await expect(panel).toHaveCount(0);
	expect(errors).toEqual([]);
});
