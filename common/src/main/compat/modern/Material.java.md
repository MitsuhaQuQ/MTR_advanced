# Material.java（modern）

方块材质的跨版本包装（1.20 及以上；构建时由 `build.gradle:166-171` 复制为 `mtr.compat.Material`）。1.20 已无原版 `Material`，本类只记一个默认地图颜色，`properties` 只设置 `mapColor`（不再带材质附带的硬度、可燃等属性，由调用方链式补上）。调用者：`common/src/main/java/mtr/block/BlockNode.java:65`、`common/src/main/java/mtr/block/BlockGlassFence.java:30`。

## 类 `Material`（final）

### 常量
- `METAL`（默认色 `MaterialColor.METAL`）、`STONE`（默认色 `MaterialColor.STONE`）。

### 字段 `private final MaterialColor defaultColor`

### 私有构造函数 `Material(MaterialColor defaultColor)`

### `static BlockBehaviour.Properties properties(Material material)`
- 作用：用该材质默认颜色转调下一个重载。

### `static BlockBehaviour.Properties properties(Material material, MaterialColor color)`
- 作用：`BlockBehaviour.Properties.of().mapColor(color.data)`；此处不再使用 `material` 参数。
- 返回：新方块属性对象。
