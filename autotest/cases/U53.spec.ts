// U53 游戏内删除音频（高）: `/mtr deleteaudio ` with Tab completes only the names the player may delete; a wrong name or
// one without a colon tells the right form.
// Covered here: the messages for a name without a colon and for a name that does not exist, and that completion offers
// nothing when there is no audio. Not covered yet (needs audio uploaded through the web editor): completing own and
// others' names, deleting with and without permission, names with spaces and Chinese, the console, the lists updating.
import {test, expect} from "../lib/test";
import {chat, chatSince, suggest, waitFor} from "../lib/game";

async function messageAfter(since: number, command: string): Promise<string> {
	await chat(command);
	const found = await waitFor(`a reply to ${command}`, async () => (await chatSince(since)).messages.find(m => !m.overlay));
	return found.text;
}

test("U53 删除音频：名字写法提示和补全 @U53", async ({marks}) => {
	expect(await suggest("/mtr deleteaudio "), "nothing to complete when there is no audio").toEqual([]);

	expect(await messageAfter(marks.chat, "/mtr deleteaudio 下一站"), "a name without a colon").toContain("命名空间");
	const second = (await chatSince(marks.chat)).last;
	expect(await messageAfter(second, "/mtr deleteaudio steve:不存在.ogg"), "a name that does not exist").toContain("没有这个音频");
});
