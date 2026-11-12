// The web editor as the tests see it: the page the test client serves on 127.0.0.1 (EditorServer), opened in the Chrome
// installed on this machine. The client's driver hands out the address with its key, as the "advanced editor" button does.
import {Page, expect} from "@playwright/test";
import {client} from "./game";

export interface EditorPage {
	page: Page,
	/** Uncaught errors in the page and console errors, since it was opened */
	errors: string[],
}

export async function openEditor(page: Page): Promise<EditorPage> {
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
	const {url} = await client.call("/editor-url");
	expect(url, "the client's editor server is running").toBeTruthy();
	await page.goto(url);
	// The tree is drawn once the first data has come from the game
	await expect(page.locator("[data-route]").first()).toBeVisible({timeout: 20_000});
	return {page, errors};
}

/** Right-clicks a route in the tree ("运营线路", its line opened first if need be) and picks a menu item. */
export async function routeMenu(page: Page, routeId: unknown, item: string) {
	const route = page.locator(`[data-route="${String(routeId)}"]`);
	if (await route.count() === 0) {
		const lines = page.locator('[data-section="lines"][data-line]');
		for (let i = 0; i < await lines.count(); i++) {
			if ((await lines.nth(i).textContent() || "").trim().startsWith("▸")) {
				await lines.nth(i).click();
			}
		}
	}
	await route.first().click({button: "right"});
	await page.getByText(item, {exact: true}).click();
}
