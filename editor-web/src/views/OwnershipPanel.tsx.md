# views/OwnershipPanel.tsx

归属层在路网图旁边的面板：用"画笔"代替逐段轨道的右键菜单，用一张表代替逐条线路的对话框来设运营线路走哪些逻辑线路。
- 有逻辑线路时：每条逻辑线路是一支画笔（颜色、区段数）；选中后在图上点轨道，轨道所在整段放进该逻辑线路（旧 `setRailLogicalLine`）；橡皮把区段移出。下方是运营线路 × 逻辑线路的勾选表（旧 `lineTracks`；都不勾表示走其停靠站台所在的逻辑线路）。
- 没有逻辑线路时：画笔是各运营线路（区段只归这一条线）、"对所有线路开放"、"恢复自动归属"（旧 `setOwnerOverride`），并可一键"按现在的归属生成初稿"。

成片地画（0.83.13，拿着画笔或橡皮时）：点一段画整段并记作起点；Shift+点另一段画两段之间最短的路（`core/railPick.ts` 的 `railPath`），终点成为下一段的起点；按住左键拖画经过的轨道（`railsNearSegment`）；Shift+拖方框画框里和压框边的轨道（`railsInBox`）；双击沿轨道画到道岔或尽头（`railsAlong`）。每个动作一步撤销。拿着画笔时左键拖不平移，右键拖或空格+拖平移，滚轮缩放；Esc 先取消起点再放下画笔。右键单击菜单照常可用。所有修改经旧代码函数（`fn.setRailLogicalLine`、`fn.setOwnerOverride`、`fn.draftSettingsPart` 等，实现在 `legacy/retired.ts` + `core/settings.ts`）进草稿。由 `views/Layers.tsx` 在归属层显示。

## 类型
- `Brush`（导出）：`{ kind: "logical", id: string | null }`（null 为橡皮）、`{ kind: "owner", lines: string[] | null, label }`（`[]` 对所有线路开放，`null` 恢复自动），或 `{ kind: "select" }`（0.99.0 多选：手势不刷，只把区段加进选择，单点切换）。

## 多选（0.99.0）
- 模块级 `selection`（轨道序号集合，按整段）、`useSelection()`、`choose(indices, toggle)`（`toggle` 且全已选时移出）、`clearSelection()`；选择变化时写 `net.paintMarks.rails` 并重画，`NetworkSchematic` 逻辑图据此垫黄。
- 面板：有选择时显示“已选 N 段”分组（`aria-label` “选中的区段”，`data-chosen-sections`）：逻辑线路下拉 + “加入”、“新建逻辑线路并加入…”、“移出逻辑线路”、“清空选择”；加入或移出后清空选择。

## 模块级状态与导出函数
- `brushNow`、`brushListeners`（内部）：手中的画笔，在面板、实景地图、逻辑示意图之间共享。
- `setBrushNow(brush)`：设置画笔并通知订阅者。调用者：本文件。
- `useBrush()`：订阅当前画笔的 Hook（`useSyncExternalStore`）。调用者：`views/NetworkSchematic.tsx:28`、本文件。
- `paintMany(indices, brush)`（0.83.13）：一次刷很多轨道所在的区段：逻辑画笔 `fn.setRailsLogicalLine(indices, id)`，归属画笔 `fn.setRailsOwnerOverride(indices, lines)`（`legacy/retired.ts`，每段一次、一步撤销）。空数组不做事。调用者：本文件 `useBrushGestures`。
- `typing(target)`（内部）：按键是否落在输入框、文本框、下拉或可编辑元素上（那里空格不当平移键）。
- `useBrushGestures(brush, start, setStart)`（内部 Hook，0.83.13）：拿着画笔时在 `#net-canvas` 以捕获阶段监听 `mousedown`/`click`/`dblclick`/`contextmenu`，在 `window` 监听按键和拖动中的 `mousemove`/`mouseup`，只在归属图层生效：
  - 左键按下（未按空格）：拦下旧处理函数（不平移），移动超过 4 像素后成为拖动（Shift 时为框选）；拖动按两次移动之间的线段、命中距离 `NET_HIT_PX / scale` 收集轨道，框选每次按方框重算；`net.paintMarks` 标出将画的整段（`v.sectionOf`）和方框，`fn.drawNetwork()`；松开时 `paintMany`，并吞掉随后的那次 click。
  - 右键按下：设 `v.net.drag`，由旧 mousemove 平移；拖过的那次右键菜单被拦下。空格按住时左键交给旧处理函数平移，光标为 grab。
  - click（不是拖动结束、没有平移）：`fn.netRailAt` 找轨道；Shift 且有起点时 `railPath(net.data.rails, net.railPoints, 起点, 这段)` 后 `paintMany`（没有路时只画这段），否则 `paint`；这段成为新的起点。
  - dblclick：`paintMany(railsAlong(net.data.rails, 这段))`。
  - Esc：有起点先取消起点，否则放下画笔。卸载或换画笔时清掉 `net.paintMarks`、起点和光标。
- `paint(index, brush)`：用画笔刷一段轨道所在的区段：逻辑画笔 `fn.setRailLogicalLine(index, id)`，归属画笔 `fn.setOwnerOverride(index, lines)`。副作用：改草稿全网设置并重绘。调用者：`views/NetworkSchematic.tsx:191`、本文件。
- `removeLogicalLine(id)`：不再询问地删除逻辑线路：去掉属于它的区段（`railLines`），各运营线路对它的勾选（剩空则设 null，表示按站台），再在 `logicalLines` 中设为 null；均经 `fn.draftSettingsPart`。调用者：本文件（删除按钮）。
- `setLineTrack(lineKey, id, on)`：勾选/取消运营线路走某逻辑线路（合并游戏与草稿中的 `lineTracks`，空时设 null）。调用者：本文件 `LineTracks`。
- `sameBrush(a, b)`（内部）：两支画笔是否相同。

## 组件
### `OwnershipPanel({ className })`（导出）
- props：`className` 外层 `aside` 类名。
- 卸载时放下画笔。拿着画笔时由 `useBrushGestures` 处理图上的点、Shift+点、拖、框、双击（见上）；起点存在 `start`（ref）和 `hasStart`（提示里显示“起点已选”），`setStart` 同时写进 `net.paintMarks.start` 并重画。光标十字（空格或右键平移时 grab）。逻辑线路被删时放下对应画笔。
- 提示：拿着画笔时是六条操作说明（点、Shift+点、拖、Shift+拖、双击、平移与撤销与 Esc），否则一句选画笔的说明；英文在 `assets/mtr/editor/i18n.js`。
- `counts`：按 `v.net.sectionRails` 统计每条逻辑线路的区段数。
- 画笔列表（`brushRow`：色条、名称、选中高亮）：
  - 有逻辑线路：每条带草稿标记、联络线标记、区段数、"只看这条"（切换 `v.net.focusLogical` 并重绘）、编辑（`fn.logicalLineDialog(line)`）、两步确认删除（`removeLogicalLine`）；最后是橡皮。
  - 没有逻辑线路：当前运输方式下每条运营线路"只归 X"、"对所有线路开放"、"恢复自动归属"。
- 有编辑权限时："＋ 新建逻辑线路…"（`fn.logicalLineDialog(null, id => 选它为画笔)`）；没有逻辑线路时"按现在的归属生成初稿…"（`fn.logicalDraftDialog()`，归属未读到时禁用）。
- 有逻辑线路时显示 `LineTracks`（不含联络线）。
- 编辑按钮提示 0.82.5 起为「改名、改颜色」，逻辑线路面板不再设联络线。
- 调用者：`views/Layers.tsx:95`。

### `LineTracks({ operating, logicalLines, canEdit })`（内部）
- 表格：行是运营线路（草稿标记、未勾选时"按站台"），列是逻辑线路（竖排名称），格内勾选框调用 `setLineTrack`。没有逻辑线路时提示。
