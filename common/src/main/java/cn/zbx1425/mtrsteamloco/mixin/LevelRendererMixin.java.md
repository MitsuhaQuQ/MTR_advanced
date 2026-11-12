# LevelRendererMixin.java（ANTE mixin）

客户端 mixin，注入原版 `LevelRenderer`（优先级 100，较早应用）。负责：每帧开始/结束时清空、在渲染实体前设置用于剔除的视锥（`WorldRenderVisibility`，供列车、装饰物等优化渲染和 `BlockEntityRenderDispatcherMixin` 使用）；帧末交换装饰物渲染缓冲并重置帧统计；以及在区块段被标脏时通知 ANTE 的轨道渲染调度器更新光照。Forge 侧另有模板 `forge/src/main/LevelRendererMixinTemplate.java`（未确定其用途/是否生效）。

## 类 `LevelRendererMixin`

### 影子字段 `renderBuffers`
- 原版渲染缓冲（当前代码只在被注释掉的代码中使用）。

### `private void resetRenderFrustum(CallbackInfo ci)`
- 注入点：`renderLevel` 的 `HEAD` 和 `RETURN`。
- 作用：`WorldRenderVisibility.setFrustum(null)`，保证视锥只在本帧实体渲染阶段有效。

### `private void setRenderFrustum(PoseStack matrices, float partialTick, long finishTime, boolean renderBlockOutline, Camera camera, GameRenderer renderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo ci)`
- 注入点：`renderLevel` 中第一次出现常量字符串 `"entities"`（profiler 切换到实体阶段）处；`require = 0`，找不到注入点不报错。
- 作用：满足全部条件时 `WorldRenderVisibility.setFrustum(accessor.getCullingFrustum())`：MTR 配置开启列车渲染优化（`Config.optimizeTrainRendering()`）且 ANTE `ClientConfig.enableOptimization`；本对象是客户端主 `levelRenderer`；相机为主相机；没有冻结视锥（`getCapturedFrustum() == null`）；不是 Vivecraft、Replay Mod；不在光影阴影阶段且未使用光影包。
- 参数：`projection` 的类型按 `MC_VERSION >= 11903` 为 JOML 或旧 `com.mojang.math.Matrix4f`（Manifold 预处理）。

### `private void afterBlockEntities(...)`
- 注入点：`renderLevel` 中常量 `"destroyProgress"` 处（方块实体之后）。
- 作用：当前为空（原先提交 ANTE 绘制调度的代码已被注释掉）。

### `private void renderLevelLast(...)`
- 注入点：`renderLevel` 的 `TAIL`。
- 作用：`BlockEntityEyeCandyRenderer.exchange()`（交换装饰物渲染的双缓冲）；`MainClient.drawContext.resetFrameProfiler()`。

### `private void setSectionDirtyWithNeighbors(int sectionX, int sectionY, int sectionZ, CallbackInfo ci)`
- 注入点：`setSectionDirtyWithNeighbors` 的 `HEAD`。
- 作用：对 x、z 各 ±1 的 9 个段柱、y ∈ [sectionY-1, sectionY+1] 调 `MainClient.railRenderDispatcher.registerLightUpdate`。注释说明：Sodium 会 `@Overwrite` 这些方法，所以三个入口都要注入；可能带来多余更新（TODO）。

### `private void setSectionDirty(int sectionX, int sectionY, int sectionZ, boolean reRenderOnMainThread, CallbackInfo ci)` / `setSectionDirty(int, int, int, CallbackInfo)`
- 注入点：两个 `setSectionDirty` 重载的 `HEAD`。
- 作用：对该段调 `registerLightUpdate(x, y, y, z)`。
