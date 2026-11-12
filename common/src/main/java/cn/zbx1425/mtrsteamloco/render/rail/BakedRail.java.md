# BakedRail.java（ANTE）

一条轨道按模型重复间隔切成的若干段，每段一个姿态矩阵，按所在分块归类（`coveredChunks`），并记模型键和颜色。

本分支的改动（0.73.4）：模型是 `RailModelRegistry.FLAT_RAIL_KEY` 时，颜色按 MTR 的规则：只有站台、存车线等特殊轨道（`hasSavedRail`）且没开"隐藏特殊轨道颜色"时用类型颜色，其余白色。
