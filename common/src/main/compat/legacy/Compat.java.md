# Compat.java（legacy）

方块/实体相关的跨版本静态工具（服务端与客户端共用），1.20 以前版本实现，基于原版 `Material` 体系。构建时 `build.gradle:166-171` 按版本把 `compat/legacy` 或 `compat/modern` 复制成 `mtr.compat` 包；modern 版同名同签名。主要调用者：各方块构造函数（`common/src/main/java/mtr/block/BlockNode.java:65`、`common/src/main/java/mtr/block/BlockPSDTop.java:52`）、`common/src/main/java/mtr/advanced/SandboxRuns.java:116`。

## 接口 `Compat`（只有静态方法）

### `static Level getLevel(Entity entity)`
- 作用：取实体所在世界，legacy 读字段 `entity.level`。
- 调用者：`common/src/main/java/mtr/advanced/SandboxRuns.java:116`、`:225`。

### `static boolean isIce(BlockState state)`
- 作用：方块材质是否 `Material.ICE`。
- 调用者：`common/src/main/java/mtr/block/BlockNode.java:232`（判断节点下方是否水面/冰面）。

### `static boolean isSolid(BlockState state)`
- 作用：`getMaterial().isSolid()`。调用者：`common/src/main/java/mtr/block/BlockStationNameEntrance.java:46`。

### `static boolean isReplaceable(BlockState state)`
- 作用：`getMaterial().isReplaceable()`，方块是否可被直接替换（草、雪层等）。

### `static int getColor(DyeColor dyeColor)` / `static int getColor(Block block)`
- 作用：返回染料或方块默认地图颜色的 RGB 整数（`.col`）。

### `static BlockBehaviour.Properties noPistonPush(BlockBehaviour.Properties properties)`
- 作用：legacy 版原样返回（旧版本各方块自己覆盖 `getPistonPushReaction`）。
- 调用者：`common/src/main/java/mtr/block/BlockNode.java:65`、`common/src/main/java/mtr/block/BlockSignalLightBase.java:22` 等方块构造函数。
