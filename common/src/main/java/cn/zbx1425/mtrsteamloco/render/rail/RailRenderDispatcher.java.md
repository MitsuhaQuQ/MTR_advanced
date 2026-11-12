# RailRenderDispatcher.java（ANTE）

轨道分块的总管：每帧收集 MTR 登记的轨道（`registerRail`），增删 `BakedRail` 和分块，按距离排序、视锥剔除，脏了的分块重建，入队绘制；还画节点和包围盒。

本分支的改动（0.73.4，存车线帧率）：
- `getModelKeyForRender`：平面轨道走分块时（`ClientConfig.useFlatRailChunks()`），火车轨道（`RailType.NONE` 除外）都用 `FLAT_RAIL_KEY`，其他运输方式返回 `""`（MTR 自己画）。
- `drawRails`：平面分块开关或"隐藏特殊轨道颜色"变了就 `clearRail()`，因为模型键和颜色是轨道加入时算的（`lastFlatState`）。
