// U445 旧存档升级（高，0.74.0；资源 ID 0.79.0）: a world saved before folders (and before resource ids) opens with every
// uploaded picture and audio file still there, each at its uploader's top, each given a resource id of its own; folders
// can then be made and files moved into them.
// Run on a copy of a player's world with media from before 0.74 (MTM_FIXTURE_NAME=world, e.g. the Q: world copy
// C:/gtmp/ui/worlds/q-20261112 on server-q with mtm_worldgen), with MTM_U445_PICTURES and MTM_U445_AUDIO the number of
// files its databases hold. Not covered: leaving and coming back (each run starts from a fresh copy), and paintings,
// speakers and announcers showing and playing them (looked at in game).
import {test, expect} from "../lib/test";
import {client, waitFor} from "../lib/game";

type Item = {name: string, key?: string, folder?: string, resourceId?: string, uploader?: string};
type Library = {entries: Item[], self?: string, folders?: {owner: string, path: string}[], busy?: boolean, message?: string};

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
	return {list, post: (body: unknown) => call("POST", body), until: (what: string, check: (library: Library) => boolean, timeout = 30_000) => waitFor(what, async () => { const library = await list(); return !library.busy && check(library) ? library : null; }, timeout)};
}

for (const [kind, path, expected] of [["图片", "images", Number(process.env.MTM_U445_PICTURES || 0)], ["音频", "audio", Number(process.env.MTM_U445_AUDIO || 0)]] as const) {
	test(`U445 旧存档升级：${kind}都在、在顶层、各有资源 ID @U445`, async () => {
		test.skip(!expected, "the world's file count is not given");
		const {until} = await endpoint(path);
		const library = await until(`the ${path} list`, next => next.entries.length >= expected, 60_000);
		expect(library.entries.length, "every file is still there").toBe(expected);
		expect(library.entries.filter(entry => entry.folder).map(entry => entry.name), "all at their uploader's top").toEqual([]);
		const ids = library.entries.map(entry => entry.resourceId || "");
		expect(ids.filter(id => !/^[a-z0-9]{8}$/.test(id)), "each has a resource id").toEqual([]);
		expect(new Set(ids).size, "no two the same").toBe(ids.length);
	});
}

test("U445 旧存档升级：可以新建文件夹并移入旧文件 @U445", async () => {
	test.skip(!Number(process.env.MTM_U445_PICTURES || 0), "the world's file count is not given");
	const {until, post} = await endpoint("images");
	const library = await until("the picture list", next => next.entries.length > 0 && Boolean(next.self), 60_000);
	const self = library.self!;
	// Organised as a manager (the test player is an operator), in the folders of the first picture's uploader
	const picture = library.entries[0];
	const owner = picture.uploader || self;
	expect((await post({action: "folder", op: "create", owner, path: "", name: "U445"})).ok).toBe(true);
	await until("the folder", next => (next.folders || []).some(folder => folder.owner === owner && folder.path === "U445"));
	expect((await post({action: "folder", op: "move", owner, path: "", folder: "U445", keys: [picture.name]})).ok).toBe(true);
	const moved = await until("the picture moved", next => next.entries.some(entry => entry.name === picture.name && entry.folder === "U445"));
	expect(moved.entries.find(entry => entry.name === picture.name)!.resourceId, "it keeps its id").toBe(picture.resourceId);
});
