# TrainClientRegistry.java

客户端车型登记表：内置车型的模型、贴图、颜色、声音（`register`），资源包车型由 `CustomResources` 加进来；按车型 ID 取 `TrainProperties`。

本分支的改动（0.73.4）：`getTrainProperties(String)` 找不到时才造空白属性（`TrainProperties.getBlankProperties()`，会新建渲染器和声音并拆路径）；以前用 `getOrDefault`，每次调用都先造一份，每节车厢每帧好几次。
