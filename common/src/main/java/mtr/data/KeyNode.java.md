# KeyNode.java

关键节点：用刷子在轨道节点上做的标记，使线路可以在仪表盘里把它选作途经点（waypoint）。作为 `SerializedDataBase` 存入铁路数据（读取于 `RailwayDataFileSaveModule.java:187`，保存时在 `:305`、`:346` 构造并经 `toBytes`（`:813-814`）序列化）。组件建图时，关键节点被 `GraphRecords.toMarks` 标为 `TrackSections.MARK_KEY`（`GraphRecords.java:588`）。

## class KeyNode extends SerializedDataBase

### 字段与存档键
- `public final BlockPos pos`：节点位置。
- `KEY_POS = "pos"`：msgpack 键，值为 `pos.asLong()`。

### `public KeyNode(BlockPos pos)`
- 作用：由位置新建。
- 主要调用者：`RailwayDataFileSaveModule.java:305`、`RailwayDataFileSaveModule.java:346`（保存时）。

### `public KeyNode(Map<String, Value> map)`
- 作用：从 msgpack 映射读取，`pos = BlockPos.of(map["pos"])`。
- 主要调用者：`RailwayDataFileSaveModule.java:187`（`KeyNode::new`，加载时）。

### `public void toMessagePack(MessagePacker messagePacker)`
- 作用：写存档：`"pos" → long`。
- 主要调用者：`RailwayDataFileSaveModule.java:814`（`toBytes`）。

### `public int messagePackLength()`
- 返回：1（键值对数量）。
- 主要调用者：`RailwayDataFileSaveModule.java:813`（`toBytes`）。

### `public void writePacket(FriendlyByteBuf packet)`
- 作用：网络包写入一个 `long`（`pos.asLong()`）。包字段顺序：`pos`。
- 主要调用者：未在仓库中找到直接调用（`SerializedDataBase` 抽象方法的实现），未确定。
