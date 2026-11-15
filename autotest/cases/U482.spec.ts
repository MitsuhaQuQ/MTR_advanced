// U482 媒体库替换音频和上传同一限制（中，0.82.1）: replacing an audio file in the media library takes a WAV as uploading does:
// turned into Ogg in the page first, then held to the server's size limit; an Ogg over the limit is refused in the page
// and not sent. The file keeps its resource id and sound id.
// Covered here: a WAV over the limit that is small as Ogg replaces the file, keeping its ids; an Ogg over the limit is
// refused with its note and the file stays. Not covered: a WAV still over the limit once converted (web test).
import {test, expect} from "../lib/test";
import {readFileSync, writeFileSync, mkdtempSync} from "fs";
import * as os from "os";
import * as path from "path";
import {client, waitFor} from "../lib/game";
import {openNextEditor} from "../lib/editor";

const REPO = "P:/Minecraft-Transit-Railway-391ce5d1800fbc70b7f565571c692287828867fa";
const OGG = readFileSync(`${REPO}/resources/common/ante/assets/mtrsteamloco/sounds/d51/button_press.ogg`);

type Item = {name: string, key: string, resourceId?: string, soundId?: string, hash?: string, size?: number};
type Library = {entries: Item[], busy?: boolean, maxFileBytes: number};

/** A mono 16-bit WAV of a tone, seconds long */
function wav(seconds: number) {
	const rate = 44100, samples = rate * seconds;
	const buffer = Buffer.alloc(44 + samples * 2);
	buffer.write("RIFF", 0); buffer.writeUInt32LE(36 + samples * 2, 4); buffer.write("WAVE", 8);
	buffer.write("fmt ", 12); buffer.writeUInt32LE(16, 16); buffer.writeUInt16LE(1, 20); buffer.writeUInt16LE(1, 22);
	buffer.writeUInt32LE(rate, 24); buffer.writeUInt32LE(rate * 2, 28); buffer.writeUInt16LE(2, 32); buffer.writeUInt16LE(16, 34);
	buffer.write("data", 36); buffer.writeUInt32LE(samples * 2, 40);
	for (let i = 0; i < samples; i++) buffer.writeInt16LE(Math.round(8000 * Math.sin(2 * Math.PI * 440 * i / rate)), 44 + i * 2);
	return buffer;
}

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
	const until = (what: string, check: (library: Library) => unknown, timeout = 60_000) =>
		waitFor(what, async () => { const library = await list(); return !library.busy && check(library) ? library : null; }, timeout) as Promise<Library>;
	return {post: (body: unknown) => call("POST", body), list, until};
}

const named = (library: Library, name: string) => library.entries.find(entry => entry.name.endsWith(":" + name) || entry.name === name);

test("U482 替换音频：大 WAV 先转换再替换，超限 Ogg 被拒 @U482", async ({page}) => {
	const {post, list, until} = await audio();
	const dir = mkdtempSync(path.join(os.tmpdir(), "u482-"));
	try {
		expect((await post({action: "upload", name: "u482a.ogg", soundId: "u482", data: OGG.toString("base64")})).ok).toBe(true);
		const before = named(await until("uploaded", next => named(next, "u482a.ogg")), "u482a.ogg")!;
		const limit = (await list()).maxFileBytes;
		expect(limit, "the server tells its limit").toBeGreaterThan(0);
		const big = path.join(dir, "u482b.wav");
		writeFileSync(big, wav(Math.ceil(limit / 88200) + 3));

		const {errors} = await openNextEditor(page, "media");
		await page.getByRole("button", {name: "音频", exact: true}).click();
		const row = (name: string) => page.locator("tr[data-entry]", {hasText: name});
		await row("u482a").click();
		await page.locator("input[data-replace-input]").setInputFiles(big);
		// Converted in the page, then sent: the same file under the new name, its ids kept
		const after = named(await until("replaced by the converted WAV", next => named(next, "u482b.ogg")), "u482b.ogg")!;
		expect(after.resourceId, "the resource id stays").toBe(before.resourceId);
		expect(after.soundId, "the sound id stays").toBe("u482");
		if (after.size !== undefined) expect(after.size, "sent as Ogg, under the limit").toBeLessThanOrEqual(limit);

		// An Ogg over the limit is refused in the page
		const tooBig = path.join(dir, "u482c.ogg");
		writeFileSync(tooBig, Buffer.concat([OGG, Buffer.alloc(limit + 1024)]));
		await row("u482b").click();
		await page.locator("input[data-replace-input]").setInputFiles(tooBig);
		await expect(page.getByText(/文件不能超过/).first(), "the note says the limit").toBeVisible({timeout: 15_000});
		await page.waitForTimeout(3000);
		const still = await list();
		expect(named(still, "u482c.ogg"), "not sent").toBeUndefined();
		expect(named(still, "u482b.ogg")!.hash, "the file stays as it was").toBe(after.hash);
		expect(errors).toEqual([]);
	} finally {
		const library = await until("the list", () => true);
		for (const name of ["u482a.ogg", "u482b.ogg", "u482c.ogg"]) {
			const entry = named(library, name);
			if (entry) await post({action: "delete", key: entry.key});
		}
	}
});
