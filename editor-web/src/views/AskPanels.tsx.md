# views/AskPanels.tsx

旧页面最后的小对话框改为侧面板（退役旧代码第 8 步）。`AskPanel` 是通用问答：一个问题、说明、可能一个输入值和一个回答按钮（平移后续班次、调整间隔、清空表、删除逻辑线路或种别、替换运用、重新排运用、摆车、生成逻辑线路初稿、车站等候分界、出库提前量，见 `legacy/dialogs.ts`）。其余面板做选择：车次出/回库指令的车厂、逻辑线路的名称和颜色、区段归属的线路、运营线路走的逻辑线路、新线路的名字。规则在 `src/core`（通过旧引擎函数 `fn.*` 调用）；面板的结果和以前一样进草稿。全部由 `views/LinePanels.tsx` 按 `PanelTarget.kind` 选择显示。

## 内部常量与组件
- `field`、`hint`：输入框和提示文字样式。
- `Buttons({ ok, onOk, onClose, error, danger })`：底部按钮：确认（`danger` 时危险样式）、取消、错误提示。
- `hex(color)`：RGB 整数 → `#rrggbb`。
- `Choices({ items, chosen, setChosen, empty })`：带色块的勾选列表（`items: {id, name, color}[]`），没有项时显示 `empty`。

## 导出组件
### `AskPanel({ target, onClose })`
- props：`target: AskTarget`（见 `legacy/engine.ts`）；`onClose`。
- 状态：输入值（初值 `target.input.value`）、错误。
- `answer()`：调用 `target.go(value)`；返回字符串则显示为错误、不关闭；否则关闭。输入框中 Enter 也回答。
- 没有 `go` 时只有一个"知道了"式按钮（文字为 `target.ok`）。
- 显示 `text` 各行、`list`（警示色列表）、可选输入（`label`、`unit`、`placeholder`，`numeric` 时小数键盘）、`hints`。
- 调用者：`views/LinePanels.tsx:30`。

### `ServiceOrderPanel({ serviceId, order, stop, onClose })`
- 车次从某站起由车厂出车（`order = "deploy"`），或到某站后转回送回车厂（`"return"`）。
- props：`serviceId`（`路线@车次`）、`order`、`stop`（站序）、`onClose`。
- 车厂下拉："自动选的车厂"（`"0"`）、跑当前线路组的车厂、其他车厂（运输方式不同的除外）。初值为现有指令的车厂。
- 确定：`fn.editTimetable(s.route, tt => fn.setServiceOrder(tt.tables[s.tableIndex], s.service, order, stop, depot))`（改草稿时刻表）。
- 调用者：`views/LinePanels.tsx:31`。

### `LogicalLinePanel({ lineId, then, onClose })`
- 新建（`lineId` 为 null）或修改逻辑线路：名称（必填，最多 64 字）、颜色（默认 `#3b82f6`）、是否联络线。
- 确定：ID 用原 ID 或 `X.newLogicalId()`，`fn.draftSettingsPart("logicalLines", …)` 写入草稿，然后 `then?.(id)`。
- 调用者：`views/LinePanels.tsx:32`。

### `OwnersPanel({ index, onClose })`
- 某轨道（实景地图轨道序号 `index`）所在区段只让勾选的运营线路走；可选线路为当前运输方式的线路组，初值为 `fn.railOwnersShown(index).owners`。
- 确定：至少勾一条（否则提示用右键"对所有线路开放"），`fn.setOwnerOverride(index, chosen)`。
- 调用者：`views/LinePanels.tsx:33`。

### `LineTracksPanel({ lineKey, onClose })`
- 运营线路走哪些逻辑线路（不含联络线）；初值 `X.lineTracksShown(...)[lineKey]`。
- 确定：`fn.draftSettingsPart("lineTracks", d => d[lineKey] = 选中 或 null)`。
- 调用者：`views/LinePanels.tsx:34`。

### `FinishPickingPanel({ onClose })`
- 在地图上点选完的新线路（`v.picking`）：填线路组名、种别名、颜色。
- 校验：线路组名必填且不能与已有线路组（`运输方式|名称小写`）重复（重复时提示改用"新建 ▾ → 种别…"）；种别名必填。
- 确定：向 `v.draft.created` 追加新建项（`key`、`source: ""`、`mode`、`lineName`、`lineKey`、名称"首站 → 末站"、`serviceType`、`color`、各站 `platformId`/`pass`（首末站不通过）/`waypoints`、空车次、`table: 0`、`depot: ""`），清 `v.picking`，`fn.saveDraft()`、`fn.renderAll()`。
- 调用者：`views/LinePanels.tsx:35`。
