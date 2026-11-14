// U484 替换音频后旧名字的音频方块照常响（高，0.82.3）: an audio file replaced by one of another name keeps its resource id,
// and the audio speakers naming it by the old name take the new name in place, so they keep playing it.
// Covered here: a speaker placed with the old name (its clip list too) names the new one after the replace, read from the
// block's own data on the server. Not covered (heard in game): the sound itself playing.
import {test, expect} from "../lib/test";
import {readFileSync} from "fs";
import {client, serverCommand, waitFor, FIXTURE_Y} from "../lib/game";

const REPO = "P:/Minecraft-Transit-Railway-391ce5d1800fbc70b7f565571c692287828867fa";
const OGG_A = readFileSync(`${REPO}/resources/common/ante/assets/mtrsteamloco/sounds/d51/button_press.ogg`);
const OGG_B = readFileSync(`${REPO}/resources/common/ante/assets/mtrsteamloco/sounds/d51/button_release.ogg`);
const [X, Z] = [12, 33];

type Item = {name: string, key?: string, resourceId?: string, reference?: string};
type Library = {entries: Item[], busy?: boolean};

async function audio() {
	const {url} = await client.call("/editor-url");
	const address = new URL(url);
	const key = address.hash.replace(/^#key=/, "");
	const call = async (method: "GET" | "POST", body?: unknown) => {
		const response = await fetch(`${address.origin}/api/audio`, {method, headers: {"X-MTR-Editor-Key": key, "Content-Type": "application/json", ...(method === "POST" ? {"Origin": address.origin} : {})}, body: body === undefined ? undefined : JSON.stringify(body)});
		expect(response.ok, `${method} api/audio answered ${response.status}`).toBeTruthy();
		return response.json();
	};
	const list = () => call("GET") as Promise<Library>;
	const until = (what: string, check: (library: Library) => unknown, timeout = 30_000) =>
		waitFor(what, async () => { const library = await list(); return !library.busy && check(library) ? library : null; }, timeout);
	return {post: (body: unknown) => call("POST", body), until};
}

const named = (library: Library, name: string) => library.entries.find(entry => entry.name.endsWith(":" + name) || entry.name === name);

async function speakerData(y: number) {
	const {output} = await serverCommand(`data get block ${X} ${y} ${Z}`);
	return output.join("\n");
}

test("U484 替换成别的名字后，音频方块里的旧名字就地换成新名字 @U484", async () => {
	const {post, until} = await audio();
	const y = FIXTURE_Y + 3;
	try {
		expect((await post({action: "upload", name: "u484a.ogg", soundId: "", data: OGG_A.toString("base64")})).ok).toBe(true);
		let library = await until("uploaded", next => named(next, "u484a.ogg")) as Library;
		const before = named(library, "u484a.ogg")!;
		const oldReference = before.reference!;
		expect(oldReference, "a reference to put in the block").toBeTruthy();

		// Placed with its own settings, then only the sound changed: settings missing a part are read as the defaults
		const placed = await serverCommand(`setblock ${X} ${y} ${Z} mtr:audio_speaker`);
		expect(placed.result, `placed: ${placed.output.join(" ")}`).toBe(1);
		const merged = await serverCommand(`data merge block ${X} ${y} ${Z} {sound:"${oldReference}",clips:[{sound:"${oldReference}",gap_tenths:0}]}`);
		expect(merged.result, `set: ${merged.output.join(" ")}`).toBe(1);
		expect((await speakerData(y)).toLowerCase(), "the block names the file by its old name").toContain(oldReference.toLowerCase());

		expect((await post({action: "replace", resourceId: before.resourceId, name: "u484b.ogg", data: OGG_B.toString("base64")})).ok).toBe(true);
		library = await until("replaced", next => named(next, "u484b.ogg")) as Library;
		const newReference = named(library, "u484b.ogg")!.reference!;
		expect(newReference.toLowerCase()).not.toBe(oldReference.toLowerCase());

		// The block takes the new name as it ticks
		const text = await waitFor("the block names the new file", async () => {
			const data = (await speakerData(y)).toLowerCase();
			return data.includes(newReference.toLowerCase()) ? data : null;
		}, 20_000);
		expect(text, "no clip keeps the old name").not.toContain(oldReference.toLowerCase());
	} finally {
		await serverCommand(`setblock ${X} ${y} ${Z} minecraft:air`);
		const library = await until("the list", () => true) as Library;
		for (const name of ["u484a.ogg", "u484b.ogg"]) {
			const entry = named(library, name);
			if (entry) await post({action: "delete", key: entry.key});
		}
	}
});
