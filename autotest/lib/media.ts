// The media library's picture endpoints on the test client (EditorServer api/images), as the media page calls them, for
// putting pictures and folders in place and reading what the server sent back (0.74.0 folders).
import {expect} from "@playwright/test";
import {client, waitFor} from "./game";

export type Picture = {name: string, hash: string, folder?: string, uploader?: string, uploaderName: string};
export type PictureLibrary = {entries: Picture[], folders?: {owner: string, ownerName: string, path: string}[], self?: string, canUpload: boolean, canManage?: boolean, busy: boolean, message: string};

/** A 1×1 GIF */
export const GIF = Buffer.from("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==", "base64");

export async function pictures() {
	const {url} = await client.call("/editor-url");
	const address = new URL(url);
	const key = address.hash.replace(/^#key=/, "");
	// Writes have to come from the page itself (EditorServer checks Origin), as the browser sends it
	const call = async (method: "GET" | "POST", body?: unknown) => {
		const response = await fetch(`${address.origin}/api/images`, {method, headers: {"X-MTR-Editor-Key": key, "Content-Type": "application/json", ...(method === "POST" ? {"Origin": address.origin} : {})}, body: body === undefined ? undefined : JSON.stringify(body)});
		expect(response.ok, `${method} api/images answered ${response.status}`).toBeTruthy();
		return response.json();
	};
	const list = () => call("GET") as Promise<PictureLibrary>;
	const post = (body: unknown) => call("POST", body);
	/** Waits until the list the server sent back passes the check */
	const until = (what: string, check: (library: PictureLibrary) => boolean, timeout = 15_000) =>
		waitFor(what, async () => { const library = await list(); return !library.busy && check(library) ? library : null; }, timeout);
	/** Uploads a picture (one at a time, as the game takes them) */
	const upload = async (name: string, folder?: string) => {
		expect((await post({name, data: GIF.toString("base64"), ...(folder === undefined ? {} : {folder})})).ok).toBe(true);
		return until(`${name} uploaded`, library => library.entries.some(entry => entry.name.endsWith(":" + name)), 30_000);
	};
	const find = (library: PictureLibrary, name: string) => library.entries.find(entry => entry.name.endsWith(":" + name));
	const folders = (library: PictureLibrary, owner: string) => (library.folders || []).filter(folder => folder.owner === owner).map(folder => folder.path).sort();
	return {address, key, list, post, until, upload, find, folders};
}
