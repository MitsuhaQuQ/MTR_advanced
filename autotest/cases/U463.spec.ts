// U463 路网示意图缩放不跳回（中，0.77.0）: zoomed in with the wheel, the network drawing keeps its zoom while the page reads
// the network and the dispatch data again; only another view or 全图 fits it again.
// Covered here: the dispatch layer's drawing (the console reads every two seconds) and the physical layer's, the real and
// the schematic views. Not covered: dragging.
import {test, expect} from "../lib/test";
import {sleep} from "../lib/game";
import {openNextEditor} from "../lib/editor";
import type {Page} from "@playwright/test";

async function transform(page: Page, label: string): Promise<string | null> {
	return page.locator(`svg[aria-label="${label}"] > g`).first().getAttribute("transform");
}

/** The scale of a drawing's transform, translate(x y) scale(k) */
function scale(transform: string | null): number {
	const match = /scale\(([-0-9.e]+)\)/.exec(transform || "");
	return match ? Number(match[1]) : NaN;
}

async function zoomStays(page: Page, view: string, label: string) {
	await page.getByRole("group", {name: "视图"}).getByRole("button", {name: view}).click();
	const drawing = page.locator(`svg[aria-label="${label}"]`);
	await expect(drawing).toBeVisible({timeout: 20_000});
	await expect(drawing.locator("> g").first()).toBeVisible({timeout: 20_000});
	const fitted = await transform(page, label);
	const box = (await drawing.boundingBox())!;
	await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
	for (let i = 0; i < 3; i++) {
		await page.mouse.wheel(0, -200);
		await sleep(150);
	}
	const zoomed = await transform(page, label);
	expect(zoomed, `${view}: the wheel zooms in`).not.toBe(fitted);
	// Long enough for the network and the console to be read again several times
	await sleep(15_000);
	expect(await transform(page, label), `${view}: the zoom stays`).toBe(zoomed);
	// 全图 fits it again: zoomed back out (the drawing's box may have changed size since it was first fitted, as the
	// dispatch layer's console comes up beside it, so not to the very same place)
	await page.getByRole("button", {name: "全图"}).click();
	await expect.poll(async () => scale(await transform(page, label))).toBeLessThan(scale(zoomed));
}

test("U463 示意图缩放不跳回：调度层 @U463", async ({page}) => {
	const {errors} = await openNextEditor(page, "dispatch");
	await zoomStays(page, "实际图", "全网实际配线图");
	expect(errors).toEqual([]);
});

test("U463 示意图缩放不跳回：物理层示意 @U463", async ({page}) => {
	const {errors} = await openNextEditor(page, "physical");
	await zoomStays(page, "示意图", "全网示意配线图");
	expect(errors).toEqual([]);
});
