# MaterialColor.java（legacy）

地图颜色的跨版本包装（1.20 以前；构建时由 `build.gradle:166-171` 按版本复制为 `mtr.compat.MaterialColor`）。1.20 把原版 `MaterialColor` 改名为 `MapColor`；MTR 代码统一用 `mtr.compat.MaterialColor`。legacy 版内部包原版 `net.minecraft.world.level.material.MaterialColor`。调用者：`common/src/main/compat/legacy/Material.java:20`、各方块构造函数（如 `common/src/main/java/mtr/block/BlockArrivalProjectorBase.java:36`）。

## 类 `MaterialColor`（final）

### 常量
`COLOR_GRAY, COLOR_BLACK, COLOR_YELLOW, QUARTZ, TERRACOTTA_YELLOW, TERRACOTTA_LIGHT_BLUE, STONE, SNOW, WOOD, METAL, ICE, EMERALD, DIAMOND, COLOR_RED, COLOR_PURPLE, COLOR_ORANGE, TERRACOTTA_ORANGE, SAND, COLOR_LIGHT_GRAY, COLOR_BROWN, COLOR_BLUE`，各包原版同名颜色。

### 字段
- `public final ...MaterialColor data`：原版颜色对象（传给 `Properties.of`）。
- `public final int col`：RGB 值（构造时拷贝 `data.col`）。

### 私有构造函数 `MaterialColor(...MaterialColor data)`
- 记下 `data` 和 `col`。
