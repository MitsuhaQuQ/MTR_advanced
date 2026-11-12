# views/LinePanels.tsx

所有侧面板的总入口，以及线路层自己的几个面板（旧代码的 `speedLimitDialog`、`throughDialog`、`contingencyDialog`、`tableDialog` 改接到这里；规则沿用旧的，旧函数保留为 `old<Name>`）：
- 线路限速：每条路线一行，输入时检查 0–300 km/h 且不高于沿途最快轨道限速；
- 直通运行：从一条路线的末站或时刻边界站接到另一路线的首站或时刻边界站，时间范围内每班接上对方能赶上的第一班，列出所有配对，可逐对取消；
- 路网故障预案：每个预案一张卡片，就地增删；
- 路线时刻表：新建一张，或修改一张表的名称和星期，显示改完后的一周。
每个面板都把改动放进草稿（直通按 `setLink` 的方式），与以前相同。由 `App.tsx` 常驻挂载。

## 导出
### `LinePanels()`（组件）
- 挂载时把 `panelHost.current` 设为 `setTarget`（`legacy/engine.ts`），卸载时清空。
- 按 `PanelTarget.kind` 渲染：`ask` → `AskPanel`；`serviceOrder` → `ServiceOrderPanel`；`logicalLine` → `LogicalLinePanel`；`owners` → `OwnersPanel`；`lineTracks` → `LineTracksPanel`；`finishPicking` → `FinishPickingPanel`（以上在 `views/AskPanels.tsx`）；`speed` → `SpeedLimits`；`vehicle` → `VehiclePanel`；`groups` → `DutyGroupsPanel`；`release` → `ReleasePlanPanel`（`views/DepotExtras.tsx`）；`moveLine` → `MoveLinePanel`；`rules` → `DepotRules`；`depot` → `DepotPanel`（`views/DepotPanel.tsx`）；`table` → `TableDays`；`through` → `Through`；其余（`contingency`）→ `Contingency`。每个面板用 `key` 保证目标变化时重建；关闭时清目标。
- 调用者：`App.tsx:78`（`<LinePanels />`）。

## 内部常量与组件
- `field`：输入框样式。`Footer({ children, error })`：按钮加错误提示。

### `SpeedLimits({ routeIds, onClose })`
- 初值 `fn.speedLimitOf(r)`（0 表示不限，显示为空）。
- `problemOf(r)`：不在 0–300 或高于 `r.fastestRail` 时的提示。
- 保存：逐条 `fn.setSpeedLimit(r, 值)`，`fn.saveDraft()`、`fn.renderAll()`。草稿中已有限速的路线显示标记。

### `Through({ routeId, onClose })`
- 选择：从（路线 A）、接出（末站或 A 的时刻边界站，`fn.isBoundary(r, i)`）、到（路线 B，默认首站在 A 末站同站者）、接入（首站或 B 的时刻边界站）、首站发车时间范围（默认 05:00–26:00，`parseClock`）、衔接至少（分钟，默认 3）、最多再等（分钟，默认 12）、是否替换原有直通。
- `found`（`useMemo`）：沿用旧规则：A 在时间范围内（且不替换时尚无直通）的每一班，在接出站的到达时刻 `out` 之后 `[out + 衔接, out + 衔接 + 最多再等]` 内找 B 尚未被接的最早一班（按接入站出发时刻），每班 B 只接一次。返回 `{ result: {s, out, to, into}[], exitIndex }`。
- 列表显示每对的班次、到达、接的班次、出发、等候分钟，可取消勾选（`left`）。
- 新建：对每个选中的对 `fn.setLink(s, 接出站, 目标车次, 接入站)`（改草稿时刻表）。

### `type Plan`
- 故障预案：`id`、`name`、`routes`（空为路网全部线路）、`lateMinutes`、`waitMinutes`（0 为不看）、`deadlock`、`manual`（只报警等调度员启用）、`turn`（`none`/`nearest`/`station`）、`turnStation`、`afterTurn`（`resume`/`deadhead`）、`skipStations`、`takeover`（备车接替）、`active?`。

### `Contingency({ anchor, onClose })`
- 路网（`data.contingencySystems` 中锚点为 `anchor` 的）的预案：初值为草稿 `draft.contingencies[anchor]`，否则游戏中的。
- 有其他接通进来的路网预案（`system.others`）时提示，可勾选"合成一套"。
- `change(i, patch)`、`toggle(list, id, on)`、`add()`（新编号为最大编号 + 1，默认晚点 5、等进路 10、锁死、手动）。
- 保存：规整名称（≤64 字）和分钟数（0–1440）；指定车站折返但未选车站、或没有任何条件时报错；合并时把其他套预案重新编号并入，并在草稿中清空那些套（合计不超过 64 个）；与游戏相同（忽略 `active`）则删除草稿条目，否则写入 `draft.contingencies[anchor]`；`fn.saveDraft()`、`fn.renderAll()`。

### `TableDays({ routeId, index, onClose })`
- 新建时刻表（`index < 0`）或修改第 `index` 张表的名称与星期（旧 `tableDialog`）。一天只用一张表：勾选的星期从原来的表移到这张。
- 七个星期按钮显示当前由哪张表运行（`ownerOf`），下方表格显示改完后每张表的星期。新建时可复制某张表的班次（连同例外、直通、回库、停运、指令），可勾选为线路组的全部路线一起建。
- 保存：新建且任一路线已有 `v.MAX_TABLES` 张表时报错；对每条路线 `fn.editTimetable(route, edited => …)`：从其他表移除这些星期；新建时先 `liftCommonPasses(edited)`（0.65.27：所有班次都通过的站改为路线通过站），再追加新表（名称、星期、余量取复制源或第一张表、各站设定取复制源或第一张表）；否则改名称和星期。有勾选星期时 `fn.setWeekday(第一个勾选的星期)` 切过去。
