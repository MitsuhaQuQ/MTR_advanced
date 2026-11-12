# CustomBogie.java（本分支新增）

客户端：资源包定义的转向架（仿 MTR 4 的 `bogie1`、`bogie2`）。`position` 是沿车身方向的位置（格），用车厢模型自身坐标。没有模型时在该位置画内置转向架。由 `CustomResources.readBogie` 创建（`common/src/main/java/mtr/client/CustomResources.java:76`、`:198`），保存在 `TrainProperties.bogie1/bogie2`，由 `JonModelTrainRenderer` 绘制。

## 类 `CustomBogie`

### 字段（final）
- `model`：转向架模型（`ModelTrainBase`，可为 null）。
- `textureId`：纹理路径（小写、去掉 `.png`；可为 null）。
- `position`：沿车身位置（格）。

### 构造函数 `CustomBogie(ModelTrainBase model, String textureId, float position)`
- 规范化纹理路径后保存。
