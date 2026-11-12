// The web editor as the tests see it: the page the test client serves on 127.0.0.1 (EditorServer), opened in the Chrome
// installed on this machine. The client's driver hands out the address with its key, as the "advanced editor" button does.
// Tests are written against the new editor (next/), which is where the editor's work goes on (user, 2026-11-12).
import {Page, expect} from "@playwright/test";
import {client} from "./game";

export interface EditorPage {
	page: Page,
	/** Uncaught errors in the page, console errors and failed requests, since it was opened */
	errors: string[],
}

function watchErrors(page: Page): string[] {
	const errors: string[] = [];
	page.on("pageerror", error => errors.push(`pageerror: ${error.message}`));
	page.on("console", message => {
		// A failed request is reported below with its address
		if (message.type() === "error" && !message.text().startsWith("Failed to load resource")) {
			errors.push(`console: ${message.text()}`);
		}
	});
	page.on("response", response => {
		if (response.status() >= 400) {
			errors.push(`${response.status()}: ${response.request().method()} ${new URL(response.url()).pathname}`);
		}
	});
	return errors;
}

/** The old editor page (frozen; kept for the few things not moved yet) */
export async function openEditor(page: Page): Promise<EditorPage> {
	const errors = watchErrors(page);
	const {url} = await client.call("/editor-url");
	expect(url, "the client's editor server is running").toBeTruthy();
	await page.goto(url);
	// The tree is drawn once the first data has come from the game
	await expect(page.locator("[data-route]").first()).toBeVisible({timeout: 20_000});
	return {page, errors};
}

/** The new editor on one of its layers (physical, owners, lines, timetable, depots, dispatch, media) */
export async function openNextEditor(page: Page, layer = "lines"): Promise<EditorPage> {
	const errors = watchErrors(page);
	const {url} = await client.call("/editor-url");
	expect(url, "the client's editor server is running").toBeTruthy();
	// The old page takes the key from the address and keeps it for the new one, as "try the new editor" does
	await page.goto(url);
	await expect(page.locator("[data-route]").first()).toBeVisible({timeout: 20_000});
	await page.goto(`${new URL(url).origin}/next/#/${layer}`);
	await expect(page.getByText("已连接游戏")).toBeVisible({timeout: 20_000});
	return {page, errors};
}

/** Opens every closed line in the tree's operating lines ("运营线路"), so the routes under them show. */
export async function openLines(page: Page) {
	const lines = page.locator('[data-section="lines"][data-line]');
	for (let i = 0; i < await lines.count(); i++) {
		const line = lines.nth(i);
		const expanded = await line.getAttribute("aria-expanded");
		if (expanded === "false" || (expanded === null && (await line.textContent() || "").trim().startsWith("▸"))) {
			await line.click();
		}
	}
}

/** The route's row in the tree's operating lines, its line opened first if need be */
export async function routeRow(page: Page, routeId: unknown) {
	const route = page.locator(`[data-route="${String(routeId)}"][data-section="lines"]`);
	if (await route.count() === 0) {
		await openLines(page);
	}
	await expect(route.first()).toBeVisible();
	return route.first();
}

/** Right-clicks a route in the tree and picks a menu item. */
export async function routeMenu(page: Page, routeId: unknown, item: string) {
	await (await routeRow(page, routeId)).click({button: "right"});
	await page.getByText(item, {exact: true}).click();
}
