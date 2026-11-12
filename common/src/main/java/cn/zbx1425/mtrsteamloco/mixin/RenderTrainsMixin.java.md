# RenderTrainsMixin.java（ANTE）

注入 MTR 的 `RenderTrains`：在 MTR 画列车和轨道时接上 ANTE 的绘制（`DrawScheduler` 的提交、轨道分块 `RailRenderDispatcher.drawRails`、节点、装饰方块），以及轨道的取舍。

本分支的改动（0.73.4，存车线帧率）：
- 画轨道分块的条件改为 `getRailRenderLevel() >= 2 || ClientConfig.useFlatRailChunks()`；节点模型只在 3D 轨道时画。
- `onRenderRailStandard`：3D 轨道时照旧登记并取消 MTR 的画法；平面轨道走分块时也登记，不拿轨道工具（`renderColors` 为假）时取消 MTR 的画法，拿着时让 MTR 照旧画彩色轨道（分块那时不画，见 `MeshBuildingRailChunk.enqueue`）。`MixinLintTest` 的 `ALLOWED` 里登记了这个整段取代。
