# RegionDataPackets.java

把服务器算出的 `RegionData`（组件 `transit-sim` 的寻路分区结果与站台图）编码进网络包发给玩家，以及在客户端解码。服务端 `PathRegionSync.send` 调 `write`；客户端 `PacketTrainDataGuiClient` 调 `read`（`:273`、`:299`、`:311`）。

## final class RegionDataPackets

### 常量
- `MAX_ENTRIES = 1 << 20`：读包时任何计数的上限，超出抛异常。

### `private RegionDataPackets()`
工具类，禁止实例化。

### `public static void write(RegionData regionData, FriendlyByteBuf packet)` / `public static RegionData read(FriendlyByteBuf packet)`
- 包字段顺序：
  1. 边界（`PathBoundaries.write` 格式：节点、段）；
  2. `int regionCount`；
  3. `uncutNodes`（`int n, long×n`）；
  4. `uncutSegments`（`int n, UUID×n`）；
  5. `missingNodes`（long 列表）；
  6. `missingSegments`（UUID 列表）；
  7. `platformRegions`（区域表，见下）；
  8. `sidingRegions`；
  9. 站台图（见 `writePlatformGraph`）。
- 字段含义（未切开/缺失的节点与段等）由组件 `RegionData` 定义，此处未展开。
- 异常：`read` 在计数非法或站台图目标越界时抛 `IllegalArgumentException`。

### `private static void writePlatformGraph(FriendlyByteBuf packet, PlatformGraph platformGraph)` / `readPlatformGraph`
- 格式：`int count`；每个站台：`long platformId, long network`；然后两个方向状态（`state = i*2 + direction`）各自 `boolean usable, int edgeCount`，再 `edgeCount` 个 `(int target, float cost)`。
- 读取时目标必须在 `[0, count*2)`，否则抛异常。代价以 float 传输（精度损失）。

### 私有辅助
- `writeLongs` / `readLongs`、`writeUuids` / `readUuids`：`int n` + n 个值。
- `writeRegions` / `readRegions`：`int n`，每项 `long id, int m, int×m`（该站台/停车线所在的区域编号）。
- `limit(int count)`：`0 ≤ count ≤ MAX_ENTRIES`，否则抛 `IllegalArgumentException("Too many region entries")`。
