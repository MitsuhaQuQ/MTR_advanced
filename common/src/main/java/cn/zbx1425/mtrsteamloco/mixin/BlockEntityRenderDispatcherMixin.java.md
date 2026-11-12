# BlockEntityRenderDispatcherMixin.java（ANTE mixin）

客户端 mixin，注入原版 `BlockEntityRenderDispatcher.render`（每个方块实体的渲染入口）。在开启列车渲染优化、`LevelRendererMixin` 已给出主视角视锥（`WorldRenderVisibility.hasFrustum()`）时，跳过确定在视锥外的原版装饰类方块实体（旗帜、告示牌、悬挂告示牌、头颅）的渲染，减少大场景的开销。只在其渲染器确实是原版对应渲染器类时才跳过（别的模组替换了渲染器就不动）。`MixinLintTest` 把它列为允许的"头部取消"注入（`common/src/test/java/cn/zbx1425/mtrsteamloco/MixinLintTest.java:39`）。

## 类 `BlockEntityRenderDispatcherMixin`（abstract）

### 影子成员
- `level`：当前渲染的世界。
- `getRenderer(E entity)`：取方块实体的渲染器。

### `private void cullVanillaDecoration(BlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource buffers, CallbackInfo ci)`
- 注入点：`render` 的 `HEAD`，可取消。
- 作用：没有视锥或实体不在当前世界时不处理；按类型找对应原版渲染器类（`BANNER → BannerRenderer`，`SIGN → SignRenderer`，1.20+ `HANGING_SIGN → HangingSignRenderer`，`SKULL → SkullBlockRenderer`），其他类型不处理；以方块中心、半径 1.5（覆盖墙上旗帜、龙头等伸出部分）判断是否可见，不可见且渲染器类精确等于原版类时 `ci.cancel()`。
- 参数：`tickDelta` partial tick；其余为原版渲染参数。
