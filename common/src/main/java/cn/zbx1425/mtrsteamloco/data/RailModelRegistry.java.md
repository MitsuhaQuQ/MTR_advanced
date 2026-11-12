# RailModelRegistry.java（ANTE）

轨道模型登记表：资源重载时读资源包 `mtrsteamloco/rails/*.json` 的轨道模型（`loadFromJson`），登记 `""`（MTR 自己的画法）、`"null"`（隐藏）和节点模型，建玩家可选的模型树（`TREE`，来自 `PATH_MAP`）。

本分支的改动（0.73.4，存车线帧率）：
- `FLAT_RAIL_KEY = "mtm_flat_rail"`：MTR 平面轨道做成的模型，只放进 `ELEMENTS`（分块按它建缓冲），不进 `PATH_MAP`，所以不出现在可选列表里。
- `flatRailModel()`：4 格一段（`FLAT_RAIL_REPEAT`），宽 2 格，贴图 `textures/block/rail.png`，横向整张、纵向每格取一条（v 0.1875–0.3125，每格挪 1/4，再加 MTR 的轨道纹理偏移设置），正反两面；高度偏移 `0.0625 + SMALL_OFFSET`，和 `RenderTrains.renderRailStandard` 一样。
