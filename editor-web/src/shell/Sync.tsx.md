# shell/Sync.tsx

"同步状态"面板（对应旧页面 `syncDialog`）：页面和游戏哪里不一致、游戏哪里没做成。问题列表来自 `core/sync.ts` 的 `syncProblems`；收回列车和与应急预案有关的调度命令像旧页面一样立即发送（`store.sendNow` → `api/apply`），不经草稿。还列出各车厂"同站换站台"的实测用时（`core/depots.ts` 的 `turnPairsOf`）。由 `App.tsx` 打开，其问题数也显示在顶栏。

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
- props：`problem: SyncProblem`（`level`、`text`、`route?`、`recall?`、`actions?: {label, op?}[]`）。
- 状态 `sent`：按操作序号记录 `sending`/`sent`（收回用 -1）。
- `send(index, operations)`：`store.sendNow(operations)`；成功标记已发送，失败恢复可点。
- 有 `route` 时文字可点，先关面板再 `onOpenRoute`。
- 有编辑权限时：`recall` 显示两步确认的"直接收回"，发送 `[{type:"recall", ...p.recall}]`；每个 `action` 有 `op` 则为按钮发送 `[op]`，没有 `op`（临时措施）则是指向旧页面 `../#/timetable` 的链接。
- 调用者：本文件 `SyncPanel`。
