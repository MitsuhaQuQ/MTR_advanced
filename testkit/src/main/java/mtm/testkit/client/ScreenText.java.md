# ScreenText.java

MTM_TestKit 客户端：记录"上一整帧"里字体画出的所有文字，以及位置（屏幕自身变换之后的 GUI 坐标）和颜色。这样界面自己画的、不属于控件的文字（状态行、标签、冲突摘要等）也能被测试读到。由 `FontMixin` 在 `Font.drawInternal` 处调用 `record`，`GameRendererMixin` 在每帧开始时调用 `nextFrame`，`ClientDriver` 的 `/state` 读取 `lastFrame`（`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:188`）。只有渲染线程写入和交换。

## 类 `ScreenText`（final，私有构造）

### 常量与静态字段
- `KEEP = 600`：每帧最多记录条数，超出的忽略。
- `drawing`：本帧正在记录的列表（仅渲染线程访问）。
- `lastFrame`（volatile）：上一整帧的列表，供 HTTP 线程读取。

### `static void record(String text, float x, float y, int color, Matrix4f matrix)`
- 作用：文字为空或本帧已满则忽略；否则用 `matrix` 变换 `(x, y)` 得到实际坐标，记录 `{"text","x","y","color"}`，坐标保留 1 位小数，颜色格式 `#rrggbb`（丢弃 alpha）。
- 调用者：`testkit/src/main/java/mtm/testkit/mixin/FontMixin.java:21`、`:31`。
- 注意：世界中的文字（例如 PIDS、站牌）只要经过 `Font.drawInternal` 也会被记录，坐标为其矩阵变换后的值。

### `static void nextFrame()`
- 作用：帧开始时把 `drawing` 变为 `lastFrame`，再新建空的 `drawing`。调用者：`testkit/src/main/java/mtm/testkit/mixin/GameRendererMixin.java:18`。

### `static JsonArray lastFrame()`
- 返回上一帧条目的 JSON 数组（共享同一批 JsonObject）。
