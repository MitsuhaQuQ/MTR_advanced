# core/sync.ts

页面与游戏之间所有不一致之处、以及游戏用给定内容没能做成的事（对应 `editor.js` 的 `syncProblems`）。折返车长时间占用站台的警告来自时刻层，由参数 `terminalWarnings` 传入（旧页面的 `terminalNow().warnings`）。被 `shell/Sync.tsx` 使用（同步面板和顶栏计数）。

## 类型
- `SyncProblem`：`level`（`err`/`warn`/`hint`）；`text`；`route?`（可跳转的路线 ID）；`actions?`（`{label, op?, adhoc?}`：`op` 为立即发送到 `api/apply` 的操作，不进草稿；`adhoc` 为临时处置的路网与列车）；`recall?`（`{depot, siding, slot}`，可直接收回的列车）。
- `TerminalWarning`：`type`（`HOLDS_PLATFORM` 或其他）、`from?.route`、`at`、`platform`、`until?`、`count?`。

## 函数
- `systemTitle(model, system)`（内部）：路网标题，同 `format.systemTitle`。
- `plansByAnchor(model, anchor)`（导出）：按锚点在 `model.contingencySystems` 中找路网（或其接入的 `others`），返回 `{anchor, plans, adhoc, system}` 或 null。调用者：本文件 `syncProblems`。
- `syncProblems(connection, model, draft, terminalWarnings = [])`（导出）：依次收集：
  1. 连接不是 `ok`：错误（不在世界里 / 链接失效 / 连不上）。无模型则到此为止。
  2. 草稿中冲突或出错的路线、运用、车厂设置、新种别。
  3. 应急预案：已启用的预案（动作"停用预案"→`contingency deactivate`）；列车已不再上报却仍有临时处置的（"解除"→`contingency release`）；多套预案并存的路网提示合并。
  4. 每个车厂：没选线路；路径生成失败/生成中/过期；绕远的空车路径；找不到路的运用数；留在线上过夜等处理的列车（带 `recall`）；报警的列车（未处理：可"启用预案"或"临时处置…"；已处理：按临时处置或预案说明，可解除/停用）；回送车厂指定失败；同站换站台没有路（`turnPairsOf` 中 `millis === null`）；没有对应车型存车线的运用（`unplaced`）；因存车线删除或换车型而移动的运用（`moves`）；运用里有已删除路线的班次；缺车（`shortfallText`）；没指定出入口、按方便方向自动出入库的线路（`entrancesUnset` 里的线路键，0.75.0 起级别 `hint`）；出入库逆着线路方向经过、没刷联锁色的站台（`entrancesUnset` 里 `against:` 开头的，0.75.0，警告）；提前了也没用的出库（实测余量里 `kind` 为 out、`seconds` 为负的，0.75.0，警告：到首站仍晚几秒，要改时刻表或出库路）；可重排提示（`replanText`，仅自动运用）；一个首站都出不去的存车线（`measuredOut` 为空数组且 `noWayOut` 非空，0.76.0，警告：收进来的车出不去，要在车厂里加调车线或查出入库规则）；各存车线没有出库/回库路的站台和尚未实测的出库；按运用图模式但没有出入库时刻。
  5. 跨多个车站范围、自动归属的站台（最多 20 条，`hint`）。
  6. 折返警告（最多 12 条）：`HOLDS_PLATFORM` 为警告，其他为错误。
  7. 有时刻表但没有各站时刻的路线（找不到路或尚未取到）。
  - 返回 `SyncProblem[]`。调用者：`shell/Sync.tsx:17`。
- 草稿线路的冲突（0.80.2）：被拒的写“和 X 的修改冲突”（`conflictBy`）；没被拒但 `changedSince` 为真时警告“游戏里在你的草稿之后改过（X 正在编辑它），现在写入会冲突”；有人正在游戏里编辑（线路 `editors`）时给一条提示。
- 交会处两股道在同一个联锁区（2026-11-14）：车厂路径的 `conflicts` 里 `kind` 为 `zone` 的单独出一条错误（列出最多 4 处坐标），说明两车会互相卡死、联锁色只刷道岔本身；其余冲突照旧并成"大量冲突进路"那一条。
