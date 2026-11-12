# shell/Sync.tsx

"同步状态"面板（对应旧页面 `syncDialog`）：页面和游戏哪里不一致、游戏哪里没做成。问题列表来自 `core/sync.ts` 的 `syncProblems`；收回列车、与应急预案有关的调度命令和对一列车的临时处置立即发送（`store.sendNow` → `api/apply`），不经草稿。还列出各车厂"同站换站台"的实测用时（`core/depots.ts` 的 `turnPairsOf`）。由 `App.tsx` 打开，其问题数也显示在顶栏。

## 内部
- `formatDuration(millis)`：满 1 分钟显示"X 分 Y 秒"，否则"Y 秒"。
- `LEVEL`、`DOT`：问题级别 `err`/`warn`/`hint` → 左边框、圆点的样式类。

## 导出
### `useSyncProblems()`（Hook）
- 返回 `syncProblems(store.connection, store.model, store.draft)`（`SyncProblem[]`）。调用者：`App.tsx:87`（顶栏计数）、本文件 `SyncPanel`。

### `SyncPanel({ open, onClose, onOpenRoute })`（组件）
- props：同 `ReviewPanel`。
- 计算 `turns`：对每个车厂 `turnPairsOf(model, depot)` 中有实测毫秒数的对，生成"车厂X：A → B 实际要 …"文本（`stopLabel`），最多显示 40 条。
- 显示：标题说明（已同步时间、每 1.5 秒检查；或没有连上）、问题列表（每条一个 `Problem`）或"页面和游戏一致"、换站台用时列表。
- 调用者：`App.tsx:75`。

## 内部组件
### `Problem({ problem, onClose, onOpenRoute })`
- props：`problem: SyncProblem`（`level`、`text`、`route?`、`recall?`、`actions?: {label, op?, adhoc?}[]`）。
- 状态 `adhoc`：展开了临时处置表单的那个操作序号，或 null。
- 状态 `sent`：按操作序号记录 `sending`/`sent`（收回用 -1）。
- `send(index, operations)`：`store.sendNow(operations)`；成功标记已发送，失败恢复可点。
- 有 `route` 时文字可点，先关面板再 `onOpenRoute`。
- 有编辑权限时：`recall` 显示两步确认的"直接收回"，发送 `[{type:"recall", ...p.recall}]`；每个 `action` 有 `op` 则为按钮发送 `[op]`；有 `adhoc`（临时处置…）则按钮展开/收起下面的 `AdhocForm`，发送 `[{type:"contingency", system, action:"adhoc", train, setting}]`（第 9 步前是指向旧页面的链接）。
- 调用者：本文件 `SyncPanel`。

### `AdhocForm({ target, sending, onSend, onCancel })`（2026-11-13，退役第 9 步从旧页面 `adhocDialog` 搬来）
- props：`target: {system, train}`（报警所在路网的锚点和列车）；`sending` 正在发送时禁用"发送"；`onSend(setting)`；`onCancel()`。
- 路网和车站：`plansByAnchor(store.model, target.system)`（`core/sync.ts`）的 `system.stations`；初值取该锚点 `adhoc[train]` 已有的设置，没有时为"最近的能折返的站、接线运营、不跳站、不接替"。
- 表单：临时折返（不折返 / 最近的能折返的站 / 指定车站）、折返站（选了指定车站时）、折返以后（接线运营 / 回送回库，折返时）、跳过的站（复选）、由备车接替余下的运用。
- `go()`：指定车站却没选站 →"选了指定车站折返，但没选车站"；什么都没选 →"什么处置都没选"（都显示在表单里，不发送）；否则 `onSend`，`afterTurn` 只取 `deadhead` / `resume`。
- 调用者：本文件 `Problem`。测试：`test/adhoc.test.tsx`。
