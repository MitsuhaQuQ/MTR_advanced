# WorldRenderVisibility.java（本分支新增）

客户端：保存当前帧主视角的剔除视锥，供列车和装饰物等判断是否在屏幕内（实验性的列车/装饰剔除，`c53099f8`）。视锥由 ANTE 的 `LevelRendererMixin` 在渲染实体前设置、帧首尾清空（`cn/zbx1425/mtrsteamloco/mixin/LevelRendererMixin.java:35`、`:50`），只在开启优化且不是 Vivecraft/Replay/光影阴影阶段时才有；没有视锥时一律视为可见。使用者：`common/src/main/java/mtr/data/TrainClient.java:141`、`:142`，`cn/zbx1425/mtrsteamloco/mixin/BlockEntityRenderDispatcherMixin.java:33`、`:55`。

## 类 `WorldRenderVisibility`（final，私有构造）

### 静态字段 `frustum`
- 当前视锥，可能为 null。

### `static void setFrustum(Frustum currentFrustum)`
- 设置/清空。

### `static boolean hasFrustum()`
- 是否有视锥（即是否启用剔除）。

### `static boolean isVisible(double x, double y, double z, double radius)`
- 以点为中心、各向外扩 `radius` 的盒子是否可见。

### `static boolean isVisible(double x, double y, double z, double endX, double endY, double endZ, double padding)`
- 两点包围盒外扩 `padding` 后是否与视锥相交；没有视锥或 `padding` 非有限时返回 true。
