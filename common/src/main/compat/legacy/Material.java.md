# Material.java（legacy）

方块材质的跨版本包装（1.20 以前；构建时由 `build.gradle:166-171` 按版本复制为 `mtr.compat.Material`）。1.20 删掉了原版 `Material`，MTR 方块统一写 `Material.properties(Material.METAL, MaterialColor.X)`；legacy 版内部包着原版 `net.minecraft.world.level.material.Material`。调用者：`common/src/main/java/mtr/block/BlockNode.java:65`、`common/src/main/java/mtr/block/BlockGlassFence.java:30`、`common/src/main/java/mtr/block/BlockLiftTrack.java:20`。

## 类 `Material`（final）

### 常量
- `METAL`、`STONE`：包着原版同名材质。

### 字段 `private final net.minecraft...Material data`
- 被包的原版材质。

### 私有构造函数 `Material(net.minecraft...Material data)`
- 只用于上面两个常量。

### `static BlockBehaviour.Properties properties(Material material)`
- 作用：`BlockBehaviour.Properties.of(material.data)`，用材质默认颜色。

### `static BlockBehaviour.Properties properties(Material material, MaterialColor color)`
- 作用：`Properties.of(material.data, color.data)`，指定地图颜色。
- 返回：新的方块属性对象，供方块构造函数链式设置。
