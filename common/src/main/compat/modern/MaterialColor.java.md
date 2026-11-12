# MaterialColor.java（modern）

地图颜色的跨版本包装（1.20 及以上；构建时由 `build.gradle:166-171` 复制为 `mtr.compat.MaterialColor`），内部包原版 `net.minecraft.world.level.material.MapColor`。常量列表与 legacy 版相同。调用者：`common/src/main/compat/modern/Material.java:7`、`:20`，各方块构造函数（`common/src/main/java/mtr/block/BlockArrivalProjectorBase.java:36`）。

## 类 `MaterialColor`（final）

### 常量
`COLOR_GRAY, COLOR_BLACK, COLOR_YELLOW, QUARTZ, TERRACOTTA_YELLOW, TERRACOTTA_LIGHT_BLUE, STONE, SNOW, WOOD, METAL, ICE, EMERALD, DIAMOND, COLOR_RED, COLOR_PURPLE, COLOR_ORANGE, TERRACOTTA_ORANGE, SAND, COLOR_LIGHT_GRAY, COLOR_BROWN, COLOR_BLUE`，各包原版 `MapColor` 同名项。

### 字段
- `public final MapColor data`：原版颜色（`Material.properties` 里用于 `mapColor`）。
- `public final int col`：RGB 值。

### 私有构造函数 `MaterialColor(MapColor data)`
