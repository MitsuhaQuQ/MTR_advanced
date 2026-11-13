# ModelMapper.java

MTR 车型的方块部件包装：建 `ModelPart`（`addCuboid`、`setRotationAngle` 等），`render` 设位置和绕 Y 转角后画。

本分支的改动（0.73.5）：`BakedTrainModels.capturing` 时 `render` 改用 ANTE 的 `CapturingVertexConsumer.dumpModelPartQuads` 逐个方块写顶点，因为 Sodium 把 `ModelPart.render` 换成了只接受游戏自己缓冲的写法。
