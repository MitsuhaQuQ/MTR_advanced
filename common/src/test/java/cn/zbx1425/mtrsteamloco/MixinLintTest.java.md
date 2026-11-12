# MixinLintTest.java

检查 ANTE 的 mixin 不悄悄整段替换 MTR 自己的代码：整段取代（`@Overwrite`、在开头取消的注入等）必须登记在 `ALLOWED` 里并写明为什么；新出现的或登记了却没了的都会失败。

0.73.4 登记了 `RenderTrainsMixin.onRenderRailStandard`：ANTE 用分块缓冲画的轨道（3D 轨道，或平面轨道做成的模型）不再由 MTR 逐段重画；其余轨道和拿着轨道工具时仍由 MTR 画。
