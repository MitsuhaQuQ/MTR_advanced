# GameRendererMixin.java（testkit）

MTM_TestKit 客户端 mixin，注入原版 `GameRenderer.render(float, long, boolean)`（每帧总渲染入口）的开头，调用 `ScreenText.nextFrame()` 标记一帧的开始，使 `ScreenText` 始终保留上一整帧画出的文字。登记于 `testkit/src/main/resources/mtm_testkit.mixins.json`。注意与 MTR 自己的 `fabric/src/main/java/mtr/mixin/GameRendererMixin.java`（注入 `renderLevel`）不是同一个类。

## 类 `GameRendererMixin`

### `private void mtmTestKit$nextFrame(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo callback)`
- 注入点：`render(FJZ)V` 的 `HEAD`。
- 作用：`ScreenText.nextFrame()`。
- 参数：`partialTicks` 帧插值；`nanoTime` 帧开始纳秒时间；`renderLevel` 本帧是否渲染世界（均未使用）。
