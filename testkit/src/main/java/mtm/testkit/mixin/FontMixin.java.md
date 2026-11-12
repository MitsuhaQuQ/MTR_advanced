# FontMixin.java（testkit）

MTM_TestKit 客户端 mixin，注入原版 `Font`（登记于 `testkit/src/main/resources/mtm_testkit.mixins.json`）。在 `Font` 所有绘制都会经过的两个 `drawInternal` 重载开头，把要画的文字交给 `ScreenText.record`，供测试读取界面上的文字。

## 类 `FontMixin`

### `private void mtmTestKit$recordString(String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode displayMode, int backgroundColor, int packedLight, boolean bidirectional, CallbackInfoReturnable<Integer> callback)`
- 注入点：`Font.drawInternal(String, float, float, int, boolean, Matrix4f, MultiBufferSource, DisplayMode, int, int, boolean)` 的 `HEAD`。
- 作用：`ScreenText.record(text, x, y, color, matrix)`。不改变原有绘制。
- 注意：带阴影的文字原版会画两次（阴影一次、正文一次）时，是否记两条取决于原版是否两次调用 `drawInternal`；未确定。

### `private void mtmTestKit$recordSequence(FormattedCharSequence text, float x, float y, int color, boolean dropShadow, Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode displayMode, int backgroundColor, int packedLight, CallbackInfoReturnable<Integer> callback)`
- 注入点：`Font.drawInternal(FormattedCharSequence, …)` 的 `HEAD`。
- 作用：把字符序列逐码点拼成字符串（丢弃样式）后调用 `ScreenText.record`。
