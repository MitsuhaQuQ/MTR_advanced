# Sidings.java（testkit）

测帧率用的存车场夹具（用户 2026-11-13：10–20 条相距 4 格的存车线都停着车时帧率大跌）。由 `ServerDriver` 的 `/fixture {"name":"sidings", …}` 调用。

## 类 `Sidings`（final，私有构造）

### `static JsonObject build(ServerLevel level, JsonObject body)`
- 作用：按 `body.yards`（没有时把 `body` 当成一个车场）逐个建车场，最后 `dataCache.sync()`。世界里已有存车线时拒绝。
- 返回：`y` 和每个车场的结果。

### `private static JsonObject yard(ServerLevel level, RailwayData railwayData, JsonObject body)`
- 字段：`count`（默认 20）、`length`（150）、`spacing`（4）、`z`（第一条的 z，0）、`trains`（车型 ID，轮流给各存车线，默认 `sp1900`；资源包车型要带 `mtr_custom_train_` 前缀）、`baseType`（`train_24_2`）。
- 作用：从 x = 0 往东铺 `count` 条存车线（`Fixture.connectRail`，与轨道连接器同样的步骤），再建一个没有线路的车厂罩住它们，各存车线 `setTrainIdAndBaseType`；车厂没有线路，每条存车线停着它造的那一列车不动。
- `trains: ["none"]`：不建车厂（没有车），在存车线南边加一条 40×40 的弯道（铁轨），用来单看轨道；弯道建不成时结果里 `curve` 是原因。
- 返回：`z`、`count`、`length`、`spacing`、`trains`、`depot`、`sidings`。
