# FrameStats.java（testkit）

帧时间测量（存车线帧率，2026-11-13）。`GameRendererMixin` 在每帧开头调用 `nextFrame()`；`ClientDriver` 的 `/frames` 路由在请求线程上调用 `measure(ms)`。

## 类 `FrameStats`（final，私有构造）

### `static void nextFrame()`
- 作用：记录本帧开始的纳秒时间；正在记录时把与上一帧开始的间隔追加到数组。

### `static JsonObject measure(long millis) throws InterruptedException`
- 作用：清空并开始记录，睡 `millis` 毫秒，停止后汇总。
- 返回：`frames`（帧数）、`fps`（帧数 ÷ 总时间）、`low1`（第 99 百分位帧时间换成的帧率）、`p50ms`、`p90ms`、`p99ms`、`maxms`。没有帧时只有 `frames`。
- 调用者：`ClientDriver` 的 `/frames`。不能在渲染线程调用（会卡住帧）。
