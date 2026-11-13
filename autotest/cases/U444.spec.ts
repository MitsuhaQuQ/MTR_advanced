// U444 媒体文件夹权限（中，0.74.0）: a manager (op) may organise anyone's folders; without that, only their own. The test
// player makes a folder for another uploader while an operator, loses op, and the server then refuses to touch that
// folder but still lets the player organise their own. Op is given back at the end.
import {test, expect} from "../lib/test";
import {player, serverCommand} from "../lib/game";
import {pictures} from "../lib/media";

const OTHER = "00000000-0000-0000-0000-000000000444";

test("U444 文件夹权限：管理员可整理所有人的，普通玩家只能整理自己的 @U444", async () => {
	const {post, until, folders} = await pictures();
	const name = await player();
	const self = (await until("the list", library => Boolean(library.self))).self!;
	try {
		expect((await until("a manager", library => library.canManage === true)).canManage).toBe(true);
		await post({action: "folder", op: "create", owner: OTHER, path: "", name: "别人的"});
		await until("another uploader's folder", library => folders(library, OTHER).includes("别人的"));

		await serverCommand(`deop ${name}`);
		await until("no longer a manager", library => library.canManage === false, 20_000);
		await post({action: "folder", op: "rename", owner: OTHER, path: "别人的", name: "改了"});
		await until("the refusal", library => library.message.includes("只能整理自己的文件"));
		expect(folders(await until("the list", () => true), OTHER)).toEqual(["别人的"]);

		// Their own folders still
		await post({action: "folder", op: "create", owner: self, path: "", name: "自己的"});
		await until("their own folder", library => folders(library, self).includes("自己的"));
		await post({action: "folder", op: "delete", owner: self, path: "自己的"});
		await until("their own folder deleted", library => !folders(library, self).includes("自己的"));
	} finally {
		await serverCommand(`op ${name}`);
		await until("a manager again", library => library.canManage === true, 20_000);
		await post({action: "folder", op: "delete", owner: OTHER, path: "别人的"});
		await until("cleaned up", library => !folders(library, OTHER).includes("别人的"));
	}
});
