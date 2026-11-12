# Compat.java（modern）

方块/实体相关的跨版本静态工具（服务端与客户端共用），1.20 及以上版本实现（构建时由 `build.gradle:166-171` 复制为 `mtr.compat.Compat`）。签名与 legacy 版一致。主要调用者：`common/src/main/java/mtr/block/BlockNode.java:65`、`:232`、`common/src/main/java/mtr/advanced/SandboxRuns.java:116`。

## 接口 `Compat`（只有静态方法）

### `static Level getLevel(Entity entity)`
- 作用：`entity.level()`（1.20 改为方法）。调用者：`common/src/main/java/mtr/advanced/SandboxRuns.java:116`、`:225`。

### `static boolean isIce(BlockState state)`
- 作用：`state.is(BlockTags.ICE)`（1.20 没有 Material，改用方块标签）。调用者：`common/src/main/java/mtr/block/BlockNode.java:232`。

### `static boolean isSolid(BlockState state)`
- 作用：`state.isSolid()`。调用者：`common/src/main/java/mtr/block/BlockStationNameEntrance.java:46`。

### `static boolean isReplaceable(BlockState state)`
- 作用：`state.canBeReplaced()`。

### `static int getColor(DyeColor dyeColor)` / `static int getColor(Block block)`
- 作用：返回 `MapColor` 的 RGB（`getMapColor().col` / `defaultMapColor().col`）。

### `static BlockBehaviour.Properties noPistonPush(BlockBehaviour.Properties properties)`
- 作用：1.20 起推动反应是方块属性，这里设 `pushReaction(PushReaction.BLOCK)`，活塞推不动该方块。
- 返回：修改后的同一个 `properties`（可链式）。
- 调用者：`common/src/main/java/mtr/block/BlockNode.java:65`、`common/src/main/java/mtr/block/BlockSignalLightBase.java:22`、`common/src/main/java/mtr/block/BlockPSDTop.java:52`。
