// U440 媒体文件夹（高，0.74.0）: uploaded pictures go into the folder chosen on upload, folders can be made, kept empty,
// renamed and deleted (what is in them moves up), and pictures move between folders; every change goes through the
// server and comes back in the list the editor reads.
// Covered here, through the editor's own endpoints on the test client (as the media page sends them): upload into a
// folder, an empty folder kept, moving, renaming, deleting a folder without losing the picture, a bad name refused.
// Not covered (looked at in game): the painting's image picker and the sound tree showing the folders; dragging in a page.
import {test, expect} from "../lib/test";
import {client, waitFor} from "../lib/game";

// A 1×1 GIF
const GIF = Buffer.from("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==", "base64").toString("base64");

type Library = {entries: {name: string, folder?: string, uploader?: string}[], folders?: {owner: string, path: string}[], self?: string, busy: boolean, message: string};

async function editor() {
	const {url} = await client.call("/editor-url");
	const address = new URL(url);
	const key = address.hash.replace(/^#key=/, "");
	// Writes have to come from the page itself (EditorServer checks Origin), as the browser sends it
	const call = async (method: "GET" | "POST", body?: unknown) => {
		const response = await fetch(`${address.origin}/api/images`, {method, headers: {"X-MTR-Editor-Key": key, "Content-Type": "application/json", ...(method === "POST" ? {"Origin": address.origin} : {})}, body: body === undefined ? undefined : JSON.stringify(body)});
		expect(response.ok, `${method} api/images answered ${response.status}`).toBeTruthy();
		return response.json();
	};
	return {list: () => call("GET") as Promise<Library>, post: (body: unknown) => call("POST", body)};
}

test("U440 媒体文件夹：上传进文件夹、新建、移动、改名、删除 @U440", async () => {
	const {list, post} = await editor();
	const self = (await waitFor("the picture list", async () => { const library = await list(); return library.self ? library : null; }, 20_000)).self!;
	const picture = (library: Library) => library.entries.find(entry => entry.name.endsWith(":u440.gif"));
	const folders = (library: Library) => (library.folders || []).filter(folder => folder.owner === self).map(folder => folder.path).sort();

	// Upload into a folder two levels down; both levels are kept
	expect((await post({name: "u440.gif", data: GIF, folder: "车站/甲站"})).ok).toBe(true);
	let library = await waitFor("the picture in its folder", async () => { const next = await list(); return !next.busy && picture(next) ? next : null; }, 30_000);
	expect(picture(library)!.folder).toBe("车站/甲站");
	expect(folders(library)).toEqual(expect.arrayContaining(["车站", "车站/甲站"]));

	// A new, empty folder is kept
	await post({action: "folder", op: "create", owner: self, path: "车站", name: "乙站"});
	library = await waitFor("the empty folder", async () => { const next = await list(); return folders(next).includes("车站/乙站") ? next : null; });

	// Moving the picture to it
	await post({action: "folder", op: "move", owner: self, path: "", folder: "车站/乙站", keys: [picture(library)!.name]});
	library = await waitFor("the picture moved", async () => { const next = await list(); return picture(next)?.folder === "车站/乙站" ? next : null; });

	// Renaming the parent takes everything along
	await post({action: "folder", op: "rename", owner: self, path: "车站", name: "Stations"});
	library = await waitFor("the folder renamed", async () => { const next = await list(); return picture(next)?.folder === "Stations/乙站" ? next : null; });
	expect(folders(library).some(path => path.startsWith("车站")), "nothing left under the old name").toBe(false);

	// A name with a slash is refused by the server too
	await post({action: "folder", op: "create", owner: self, path: "", name: "a/b"});
	await waitFor("the refusal", async () => { const next = await list(); return next.message.includes("斜杠") ? next : null; });
	expect(folders(await list())).not.toContain("a/b");

	// Deleting the folder the picture is in moves it up a level; the picture stays
	await post({action: "folder", op: "delete", owner: self, path: "Stations/乙站"});
	library = await waitFor("the folder deleted", async () => { const next = await list(); return picture(next)?.folder === "Stations" ? next : null; });
	expect(folders(library)).not.toContain("Stations/乙站");

	// Cleaning up: the folder, then the picture
	await post({action: "folder", op: "delete", owner: self, path: "Stations"});
	await waitFor("the picture at the top", async () => { const next = await list(); return picture(next)?.folder === "" ? next : null; });
	await post({action: "delete", name: picture(await list())!.name});
	await waitFor("the picture deleted", async () => { const next = await list(); return picture(next) ? null : next; });
});
