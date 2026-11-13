// U468 媒体热更新（高，0.79.0）: a picture or an audio file can be replaced in place by its resource id, with a file of
// another name: the id, folder (and the audio's sound id) stay, the new name shows, the old name is kept as the file's
// own, so references by it still find the file, and no other upload may take the old name.
// Covered here, through the editor's own endpoints on the test client (as the media pages send them): both kinds, the
// id kept, the old name listed, the old name refused to a new upload, replacing back to the old name, deleting. Not
// covered (looked at in game): a painting or announcer set with the old name showing or playing the new file.
import {test, expect} from "../lib/test";
import {readFileSync} from "fs";
import {client, sleep, waitFor} from "../lib/game";
import {GIF} from "../lib/media";

/** Another 1×1 GIF: the first colour of the palette changed */
const OTHER_GIF = (() => { const bytes = Buffer.from(GIF); bytes[13] = 0x12; bytes[14] = 0x34; bytes[15] = 0x56; return bytes; })();
const REPO = "P:/Minecraft-Transit-Railway-391ce5d1800fbc70b7f565571c692287828867fa";
const OGG_A = readFileSync(`${REPO}/resources/common/ante/assets/mtrsteamloco/sounds/d51/button_press.ogg`);
const OGG_B = readFileSync(`${REPO}/resources/common/ante/assets/mtrsteamloco/sounds/d51/button_release.ogg`);

type Item = {name: string, key?: string, folder?: string, resourceId?: string, oldNames?: string[], soundId?: string, hash: string};
type Library = {entries: Item[], busy?: boolean, message?: string};

async function endpoint(path: string) {
	const {url} = await client.call("/editor-url");
	const address = new URL(url);
	const key = address.hash.replace(/^#key=/, "");
	const call = async (method: "GET" | "POST", body?: unknown) => {
		const response = await fetch(`${address.origin}/api/${path}`, {method, headers: {"X-MTR-Editor-Key": key, "Content-Type": "application/json", ...(method === "POST" ? {"Origin": address.origin} : {})}, body: body === undefined ? undefined : JSON.stringify(body)});
		expect(response.ok, `${method} api/${path} answered ${response.status}`).toBeTruthy();
		return response.json();
	};
	const list = () => call("GET") as Promise<Library>;
	const until = (what: string, check: (library: Library) => Item | undefined | boolean, timeout = 30_000) =>
		waitFor(what, async () => { const library = await list(); return !library.busy && check(library) ? library : null; }, timeout);
	return {list, post: (body: unknown) => call("POST", body), until};
}

const named = (library: Library, name: string) => library.entries.find(entry => entry.name.endsWith(":" + name) || entry.name === name);

test("U468 图片按资源 ID 替换，文件名可变，旧名仍属于它 @U468", async () => {
	const {post, until} = await endpoint("images");
	try {
		expect((await post({name: "u468a.gif", data: GIF.toString("base64"), folder: "u468"})).ok).toBe(true);
		let library = await until("uploaded", next => named(next, "u468a.gif"));
		const before = named(library, "u468a.gif")!;
		expect(before.resourceId, "a resource id").toMatch(/^[a-z0-9]{8}$/);

		// Replaced by a picture of another name
		expect((await post({action: "replace", resourceId: before.resourceId, name: "u468b.gif", data: OTHER_GIF.toString("base64")})).ok).toBe(true);
		library = await until("replaced", next => named(next, "u468b.gif"));
		const after = named(library, "u468b.gif")!;
		expect(after.resourceId, "the id stays").toBe(before.resourceId);
		expect(after.folder, "the folder stays").toBe("u468");
		expect(after.hash, "the content is new").not.toBe(before.hash);
		expect(after.oldNames, "the old name is kept").toEqual([before.name]);
		expect(named(library, "u468a.gif"), "no file has the old name now").toBeUndefined();

		// The old name is still the file's: a new upload can't take it
		expect((await post({name: "u468a.gif", data: GIF.toString("base64")})).ok).toBe(true);
		library = await until("the refusal", next => Boolean(next.message && next.message.includes("用过")));
		expect(named(library, "u468a.gif")).toBeUndefined();

		// Back to its old name, which the name it had since then becomes
		expect((await post({action: "replace", resourceId: before.resourceId, name: "u468a.gif", data: GIF.toString("base64")})).ok).toBe(true);
		library = await until("back to the old name", next => named(next, "u468a.gif"));
		expect(named(library, "u468a.gif")!.resourceId).toBe(before.resourceId);
		expect(named(library, "u468a.gif")!.oldNames).toEqual([after.name]);
	} finally {
		const library = await until("the list", () => true);
		for (const name of ["u468a.gif", "u468b.gif"]) {
			const entry = named(library, name);
			if (entry) await post({action: "delete", name: entry.name});
		}
		await until("cleaned up", next => !named(next, "u468a.gif") && !named(next, "u468b.gif"));
	}
});

test("U468 音频按资源 ID 替换，文件名可变，声音 ID 不变 @U468", async () => {
	const {post, until} = await endpoint("audio");
	try {
		expect((await post({action: "upload", name: "u468a.ogg", soundId: "u468", folder: "u468", data: OGG_A.toString("base64")})).ok).toBe(true);
		let library = await until("uploaded", next => named(next, "u468a.ogg"));
		const before = named(library, "u468a.ogg")!;
		expect(before.resourceId).toMatch(/^[a-z0-9]{8}$/);

		expect((await post({action: "replace", resourceId: before.resourceId, name: "u468b.ogg", data: OGG_B.toString("base64")})).ok).toBe(true);
		library = await until("replaced", next => named(next, "u468b.ogg"));
		const after = named(library, "u468b.ogg")!;
		expect(after.resourceId, "the id stays").toBe(before.resourceId);
		expect(after.soundId, "the sound id stays").toBe("u468");
		expect(after.folder, "the folder stays").toBe("u468");
		expect(after.hash).not.toBe(before.hash);
		expect((after.oldNames || []).map(name => name.toLowerCase()), "the old name is kept").toEqual([(before as any).reference.toLowerCase()]);

		// The old name can't be taken by a new upload
		await post({action: "upload", name: "u468a.ogg", soundId: "", data: OGG_A.toString("base64")});
		// The server answers the page by itself; give it time to take the file if it were going to
		await sleep(4000);
		expect(named(await until("the list", () => true), "u468a.ogg"), "the old name stays free of other files").toBeUndefined();
	} finally {
		const library = await until("the list", () => true);
		for (const name of ["u468a.ogg", "u468b.ogg"]) {
			const entry = named(library, name);
			if (entry) await post({action: "delete", key: entry.key});
		}
		await until("cleaned up", next => !named(next, "u468a.ogg") && !named(next, "u468b.ogg"));
	}
});
