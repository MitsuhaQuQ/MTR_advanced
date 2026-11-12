# views/TrackMapDrawing.tsx

把 `TrackMap`（`core/trackSchematic.ts` 或 `core/orthogonalTracks.ts` 的结果）画成 SVG 元素片段。主视图（`views/NetworkSchematic.tsx`）和回归预览用的是完全相同的轨道路径。新页面组件，不直接调用旧代码。

## 导出
### `TrackMapDrawing({ map, chosen, onChoose, trains, onTrain, zoom = 1 })`（组件）
- props：
  - `map: TrackMap`：要画的轨道图；
  - `chosen`：选中的车站 ID（`s:<ID>`）或 null，选中车站的站台底下画半透明高亮；
  - `onChoose(id)`：点车站名或站台时回调车站 ID；
  - `trains?: TrainMark[]`：要标出的列车（`views/NetworkSchematic.tsx` 的类型：`id`、`name`、`rail`、`late`、`selected`）；
  - `onTrain?(id)`：点列车时回调；
  - `zoom`：当前缩放倍数，用来让文字、圆点在缩放后保持屏幕大小（字号 13/zoom）。
- 绘制内容（返回 Fragment）：
  - 每段轨道一条折线：有路线颜色时用第一种颜色、线宽 2.5，否则灰色 1.8；`kind === 2` 画长虚线、`kind === 3` 画点线（标签见下）；
  - 站台（`kind === 1`）：在站台轨道一侧（背离同站其他站台的一侧）画白芯黑边的站台条，可点选车站，提示"N 号站台"；
  - 尽头小圆点（"尽头"）、道岔小圆点（"道岔"）；
  - `kind` 2/3 的轨道中点标注"存车线"/"留置位"；
  - 车站名（可点击、可键盘 Enter/空格选择，`|` 后的英文名不显示）；
  - 列车：所在轨道整段加粗描出（选中用选择色、晚点用 `--late` 色，否则墨色），上方写车名，可点击。
- 调用者：`views/NetworkSchematic.tsx:107`。
