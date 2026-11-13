// U462 调度台按钮恢复（中，0.77.0）: in the web dispatch console, holding a train, letting it go and holding it again works
// without loading the page again: each button is greyed out as sent only until the train's state changes. Runs after
// run.spec.ts, while the six lines' trains are out.
// Covered here: one train on a duty, hold, release, hold, release. Not covered: the other commands, and a command the
// server refuses (the card then says 发送失败).
import {test, expect} from "../lib/test";
import {openNextEditor} from "../lib/editor";

test("U462 调度台：扣停、放行、再扣停不用刷新 @lines @U462", async ({page}) => {
	const {errors} = await openNextEditor(page, "dispatch");
	// A train on a duty: its card has the commands
	const anyCard = page.locator("li[data-console-card]").filter({has: page.getByRole("button", {name: "扣停"})}).first();
	await expect(anyCard).toBeVisible({timeout: 30_000});
	const id = await anyCard.getAttribute("data-console-card");
	const card = page.locator(`li[data-console-card="${id}"]`);
	const button = (name: string) => card.getByRole("button", {name, exact: true});

	for (let round = 0; round < 2; round++) {
		await button("扣停").click();
		// Sent, then the console reads the train held and offers to let it go
		await expect(button("放行"), `round ${round + 1}: held`).toBeEnabled({timeout: 15_000});
		await button("放行").click();
		// Let go, holding it again is offered at once, not left greyed out as sent (until 0.77.0 it was)
		await expect(button("扣停"), `round ${round + 1}: let go`).toBeEnabled({timeout: 15_000});
	}
	await expect(card.getByRole("button", {name: "已发送"})).toHaveCount(0);
	expect(errors).toEqual([]);
});
