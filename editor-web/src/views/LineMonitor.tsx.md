# views/LineMonitor.tsx

调度层的“线路”视图（0.99.0）：一次一条**逻辑线路**的调度员画面，显示归属层分给这条逻辑线路的物理轨道（上下行、折返线、出入段线都在内），不是某条运营线路的路线。图用全网示意图的同一套画法：旧代码读的 `v.net.data`（`api/network`）经 `core/trackSchematic.ts` 的 `trackSchematic` 和 `core/orthogonalTracks.ts` 的 `orthogonalTracks` 变成横竖 45° 的轨道图，再只留 `fn.railLogicalLine(序号)` 为所选逻辑线路的轨道（含草稿），缩放到放得下。每 2 秒（页面可见时）读 `api/console`，同时写回 `v.consoleState.data`，让右侧调度卡片一起更新。

## 导出
### `LineMonitor({ onTrain })`
- 逻辑线路下拉（`fn.logicalLinesShown()`，选择记在 localStorage `mtr-next-line-monitor`），图例：空闲蓝、占用红、进路锁定绿。没有逻辑线路、路网未读到、所选逻辑线路没有轨道时各有提示。
- 轨道折线 `data-rail`（两端节点键）、`data-state`（`free`/`taken`/`locked`）：在 `taken` 里为占用；否则在 `locked`（每条锁定进路的道岔节点到两侧节点的两段）里为锁定；站台轨道下垫灰底，存车线、留置位画虚线；道岔点、车站名。
- 车次框 `data-train`：按 `train.head` 找所在轨道，`along`（从位置较小的节点量起）换成沿折线的比例插值，箭头按 `towardsHigher` 和折线方向旋转；名字 `fn.consoleTrainName`，晚点 ≥ 60 秒加“+分”，≥ 300 秒红框，选中的黄框；点击调用 `onTrain(id)`。

## 内部
- `along(points, part)`：折线上某比例处的点和方向。
- 常量：`POLL_MS`（2000）、颜色 `FREE`/`TAKEN`/`LOCKED`。

调用者：`views/Layers.tsx` 的 `DispatchWork`。
