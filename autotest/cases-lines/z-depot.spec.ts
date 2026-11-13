// U446 没配规则自动选方向、U447 逆向过站台提示联锁区（高，0.75.0）: on the six lines, line 2's depot reaches its line at
// more than one station with no rule, and lines 1 and 4 (front turn-back, depot behind) can only leave by running one
// of their platforms against the line, not painted as an interlocking zone. The server plans them all the same and says
// so in its log; the web editor's sync panel tells the first as a hint and warns of the second.
// Covered here: the server's log lines and the two texts in the sync panel. Not covered: painting the zone and the
// warning going away, a rule then being followed (U446's last part).
import {test, expect} from "../lib/test";
import {serverLog} from "../lib/lines";
import {openNextEditor} from "../lib/editor";

test("U446 U447 车厂出入库：自动选方向的说明和逆向过站台的警告 @lines @U446 @U447", async ({page}) => {
	const log = serverLog();
	expect(log, "the server took the most convenient way where no rule says").toMatch(/reaches the line at more than one station.*most convenient/);
	expect(log, "the server used a way against the line and said so").toMatch(/runs the line's platforms \[[^\]]+\] against the line, which aren't in an interlocking zone; it is used/);

	const {errors} = await openNextEditor(page, "depots");
	await page.getByRole("button", {name: /同步状态/}).click();
	const panel = page.getByRole("dialog", {name: "同步状态"});
	await expect(panel.getByText(/已按方便的方向自动出入库/).first(), "U446: the hint").toBeVisible({timeout: 20_000});
	await expect(panel.getByText(/出入库要逆着线路方向经过/).first(), "U447: the warning").toBeVisible({timeout: 20_000});
	expect(errors).toEqual([]);
});
