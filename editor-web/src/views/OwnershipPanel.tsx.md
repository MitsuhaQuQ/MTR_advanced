# views/OwnershipPanel.tsx

归属层在路网图旁边的面板：用"画笔"代替逐段轨道的右键菜单，用一张表代替逐条线路的对话框来设运营线路走哪些逻辑线路。
- 有逻辑线路时：每条逻辑线路是一支画笔（颜色、区段数）；选中后在图上点轨道，轨道所在整段放进该逻辑线路（旧 `setRailLogicalLine`）；橡皮把区段移出。下方是运营线路 × 逻辑线路的勾选表（旧 `lineTracks`；都不勾表示走其停靠站台所在的逻辑线路）。
- 没有逻辑线路时：画笔是各运营线路（区段只归这一条线）、"对所有线路开放"、"恢复自动归属"（旧 `setOwnerOverride`），并可一键"按现在的归属生成初稿"。

平移地图的点击不会画；Esc 或再点一次画笔放下。右键菜单照常可用。所有修改经旧代码函数（`fn.setRailLogicalLine`、`fn.setOwnerOverride`、`fn.draftSettingsPart` 等，实现在 `legacy/retired.ts` + `core/settings.ts`）进草稿。由 `views/Layers.tsx` 在归属层显示。

## 类型
- `Brush`（导出）：`{ kind: "logical", id: string | null }`（null 为橡皮）或 `{ kind: "owner", lines: string[] | null, label }`（`[]` 对所有线路开放，`null` 恢复自动）。

## 模块级状态与导出函数
- `brushNow`、`brushListeners`（内部）：手中的画笔，在面板、实景地图、逻辑示意图之间共享。
- `setBrushNow(brush)`：设置画笔并通知订阅者。调用者：本文件。
- `useBrush()`：订阅当前画笔的 Hook（`useSyncExternalStore`）。调用者：`views/NetworkSchematic.tsx:28`、本文件。
- `paint(index, brush)`：用画笔刷一段轨道所在的区段：逻辑画笔 `fn.setRailLogicalLine(index, id)`，归属画笔 `fn.setOwnerOverride(index, lines)`。副作用：改草稿全网设置并重绘。调用者：`views/NetworkSchematic.tsx:191`、本文件。
- `removeLogicalLine(id)`：不再询问地删除逻辑线路：去掉属于它的区段（`railLines`），各运营线路对它的勾选（剩空则设 null，表示按站台），再在 `logicalLines` 中设为 null；均经 `fn.draftSettingsPart`。调用者：本文件（删除按钮）。
- `setLineTrack(lineKey, id, on)`：勾选/取消运营线路走某逻辑线路（合并游戏与草稿中的 `lineTracks`，空时设 null）。调用者：本文件 `LineTracks`。
- `sameBrush(a, b)`（内部）：两支画笔是否相同。

## 组件
### `OwnershipPanel({ className })`（导出）
- props：`className` 外层 `aside` 类名。
- 卸载时放下画笔。拿着画笔时在 `#net-canvas`（旧实景地图画布）上以捕获阶段监听点击：没有拖动、处于归属图层时，用 `fn.netRailAt(x, y)` 找轨道并 `paint`，阻止旧处理函数；Esc 放下；光标改十字。逻辑线路被删时放下对应画笔。
- `counts`：按 `v.net.sectionRails` 统计每条逻辑线路的区段数。
- 画笔列表（`brushRow`：色条、名称、选中高亮）：
  - 有逻辑线路：每条带草稿标记、联络线标记、区段数、"只看这条"（切换 `v.net.focusLogical` 并重绘）、编辑（`fn.logicalLineDialog(line)`）、两步确认删除（`removeLogicalLine`）；最后是橡皮。
  - 没有逻辑线路：当前运输方式下每条运营线路"只归 X"、"对所有线路开放"、"恢复自动归属"。
- 有编辑权限时："＋ 新建逻辑线路…"（`fn.logicalLineDialog(null, id => 选它为画笔)`）；没有逻辑线路时"按现在的归属生成初稿…"（`fn.logicalDraftDialog()`，归属未读到时禁用）。
- 有逻辑线路时显示 `LineTracks`（不含联络线）。
- 调用者：`views/Layers.tsx:95`。

### `LineTracks({ operating, logicalLines, canEdit })`（内部）
- 表格：行是运营线路（草稿标记、未勾选时"按站台"），列是逻辑线路（竖排名称），格内勾选框调用 `setLineTrack`。没有逻辑线路时提示。
