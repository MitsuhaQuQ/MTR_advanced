// U441 网页文件夹树和拖动 / U442 上传时选文件夹（高，0.74.0）: in the editor's media page, in Chrome: the folder tree
// shows the player's folders, a folder shows its pictures, ticked pictures dragged onto a folder move into it and back out
// onto the uploader, an upload asks for its folder (the open one by default), and deleting a folder moves what it holds up.
// Every step is checked in the list the server sends back.
import {test, expect} from "../lib/test";
import {openNextEditor} from "../lib/editor";
import {pictures, GIF} from "../lib/media";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";

test("U441 U442 网页文件夹：拖动移动、上传选文件夹、删除上移 @U441 @U442", async ({page}) => {
	const {upload, post, until, find, folders} = await pictures();
	await upload("u441a.gif");
	const start = await upload("u441b.gif");
	const self = start.self!;
	await post({action: "folder", op: "create", owner: self, path: "", name: "页面"});
	await until("the folder", library => folders(library, self).includes("页面"));

	const {errors} = await openNextEditor(page, "media");
	await page.getByRole("button", {name: "图片", exact: true}).click();
	const folder = (folderPath: string) => page.locator(`[data-folder="${self}:${folderPath}"]`);
	const row = (name: string) => page.locator(`[data-entry$=":${name}"]`);

	// The player's top shows both pictures; ticking both and dragging one moves both into 页面
	await folder("").click();
	await expect(row("u441a.gif")).toBeVisible();
	await expect(row("u441b.gif")).toBeVisible();
	await row("u441a.gif").getByRole("checkbox").check();
	await row("u441b.gif").getByRole("checkbox").check();
	await row("u441a.gif").dragTo(folder("页面"));
	await until("both moved", library => find(library, "u441a.gif")?.folder === "页面" && find(library, "u441b.gif")?.folder === "页面");
	await expect(row("u441a.gif")).toHaveCount(0);

	// In 页面, dragging one back onto the player's top moves it out
	await folder("页面").click();
	await expect(row("u441a.gif")).toBeVisible();
	await row("u441b.gif").dragTo(folder(""));
	await until("one moved out", library => find(library, "u441b.gif")?.folder === "");

	// An upload asks for its folder: the open one, 页面, unless another is chosen
	const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "u441-")), "u441c.gif");
	fs.writeFileSync(file, GIF);
	await page.locator("[data-image-library] input[type=file]:not([data-replace-input])").setInputFiles(file);
	const target = page.locator("[data-upload-target]");
	await expect(target.getByRole("combobox")).toHaveValue("页面");
	await target.getByRole("button", {name: "上传", exact: true}).click();
	await until("uploaded into 页面", library => find(library, "u441c.gif")?.folder === "页面", 30_000);

	// Deleting 页面 asks first, then moves its pictures up to the player's top
	await folder("页面").click();
	await page.getByRole("button", {name: "删除文件夹"}).click();
	await page.getByRole("button", {name: "确定"}).click();
	const after = await until("the folder deleted", library => !folders(library, self).includes("页面"));
	expect(find(after, "u441a.gif")!.folder).toBe("");
	expect(find(after, "u441c.gif")!.folder).toBe("");

	// Cleaning up
	for (const name of ["u441a.gif", "u441b.gif", "u441c.gif"]) {
		await post({action: "delete", name: find(await until("the list", () => true), name)!.name});
	}
	await until("cleaned up", library => !library.entries.some(entry => /:u441[abc]\.gif$/.test(entry.name)));
	expect(errors, "no errors in the page").toEqual([]);
});
