# views/DepotPanel.tsx

车厂设置侧面板（旧代码的 `depotDialog` 改接到这里，旧实现保留为 `oldDepotDialog`）和车厂出入库规则面板（旧 `depotRulesDialog`）。由 `views/LinePanels.tsx` 按 `PanelTarget.kind`（`depot` / `rules`）显示。通过旧代码函数读写草稿（`fn.storeDepotEntry`、`fn.saveDraft`、`fn.renderAll` 等，规则在 `core/settings.ts`），`core/depots.ts` 的 `sameLine` 用于匹配规则线路。

## 内部常量与类型
- `field`：输入框样式。`H({ children })`：小节标题组件。
- `Siding`：面板里存车线的编辑值 `{ fleet: 文本（空为不限）, routes }`。
- `Buffer`：实测余量的编辑值 `{ adopt, seconds: 文本 }`。
- `Rule`：出入库规则：`id`、`lines`（线路键）、`weekdays`（位掩码，0 为每天）、`outEntrance`/`backEntrance`（出/入口车站 ID，"0" 自动）、`extraLead`（比出库路线用时再早发，毫秒）、`backSiding`、`outPath`/`backPath`（命名回送路径 ID，"0" 默认）、`waitThreshold`（毫秒，-1 用车站或全网的）。
- `WayPath`：命名回送路径：`id`、`name`、`kind`（`out`/`back`）、`from`、`to`、`waypoints`。

## 导出组件
### `DepotPanel({ depotId, onClose })`
- 车厂设置：负责的线路（按线路组列出种别开关）、是否允许长时间停在线上（`cycleRoutes`；按时刻表的车厂与否文案不同）、每个车辆池的配属车数（空为存车线相加，摘要 `fn.vehicleSummary(pool, 车型设置, 种别)`）、存车线 × 所选路线的勾选表及每条存车线最多停几列（不勾 = 全部线路）、实测余量（每项"采用"及秒数，1–1200）与"清空实测余量"、全网的换端最短时间（0–600 秒）与等候分界（0–3600 秒）。路线勾选变化后表格立即跟随（旧对话框需保存后重开）。
- 初值：草稿中的车厂条目，否则游戏车厂；换端时间与等候分界来自 `fn.minReverseMillis()`、`fn.waitThresholdMillis()`。
- `save()`：校验数字；生成存车线条目（空为 -1，0–999；只保留仍勾选的路线）、车辆池配属（空为 null）和车型设置（沿用条目或游戏的）；全网设置只改这两个值（与已应用值相同则不写），保留草稿全网设置的其他部分（旧对话框会把逻辑线路等丢掉）；比较采用的余量与已应用的是否相同（`sameAdded`）；`fn.storeDepotEntry(depot, entry, sameAdded)`（进草稿 `depots`，与游戏相同则删除）。
- 副作用：改 `v.draft.networkSettings`、草稿 `depots`。
- 调用者：`views/LinePanels.tsx:42`。

### `DepotRules({ depotId, editing, onClose })`
- 车厂的出入库规则与命名回送路径：每条规则一张卡片（线路和星期用按钮切换），每条路径一张卡片。规则按"线路和星期都指定 > 只指定线路 > 只指定星期 > 都不指定"选用。
- props：`depotId`；`editing` 正在编辑的值（从地图选线回来时由旧代码传回，否则 null 用 `fn.rulesOf(depot)`）；`onClose`。
- 值在原对象上就地编辑（与旧对话框相同），挂载时写入 `v.rulesEditing = { depot, value }`，使在地图上选好的路回到这里。
- `lines = fn.depotLines(depot)`；`unset` 为没指定出入口、按方便方向自动出入库的线路（`depot.shortfall.entrancesUnset`），0.75.0 起顶部是灰色说明“已按方便的方向自动出入库（可在出入库规则里指定）”，不再是警告。
- “实测余量”说明（0.75.0）：出库按到首站晚了多少算、自动校正（每 3 班取中间值提前那么多，马上生效；提前后晚点没少一半就退回并标出），折返和回库只作建议。实测秒数为负的出库（服务端 `Depot.withIneffective`）显示警告色“提前出库没用：到首站仍晚 N 秒……”；勾“采用”时默认秒数不取负值。
- 每条规则：删除；线路按钮（用 `sameLine` 匹配，改名前勾的线路仍算勾上；车厂已没有的线路以删除线显示，点一下去掉）；星期按钮；出库/回库方式说明（`wayText`：命名路径的段数和名称，或出入口车站名，或"自动"）与"在图上点轨道…"（`pick`：记 `v.rulesEditing`，关闭面板，`fn.startWayPick(depot, {rule, out})`）；出/回库路径下拉；回哪条存车线；早发秒数；等候分界秒数。
- 回送路径卡片：名字、种类、途经点数、"在图上画…"（`pick({path, out})`）、删除（同时把引用它的规则改回默认）。
- 底部：放进草稿（与游戏相同则删除 `draft.depotRules[depot]`，否则写入 `{rules: value}`；清 `v.rulesEditing`；`fn.saveDraft()`、`fn.renderAll()`）、新增规则、新增出库路径、新增回库路径。关闭时清 `v.rulesEditing`。
- 调用者：`views/LinePanels.tsx:41`。
