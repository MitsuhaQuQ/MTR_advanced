# ServerDriver.java

MTM_TestKit 的服务端驱动（调试专用，仅 Fabric）：服务器启动后开 `Http` 服务（side = `server`，端点文件写在服务器目录 `testkit/endpoint-server.json`），让外部测试脚本以控制台身份执行命令、在空世界里搭固定测试线网（`Fixture`/`Lines`）、读取铁路数据、采样列车位置、读取时刻表传感器脉冲。由 `TestKit.onInitialize` 注册：`SERVER_STARTED → start`、`SERVER_STOPPING → stop`、`END_SERVER_TICK → tickSensors`（`testkit/src/main/java/mtm/testkit/TestKit.java:22`–`:24`）。大部分路由经 `Http.onThread` 在服务器主线程执行。

## 类 `ServerDriver`（final，私有构造）

### 静态字段
- `http`：运行中的服务器。
- `watchedSensors`（`List<BlockPos>`）：测试线网放置并需要监视的时刻表传感器。
- `sensorPulses`（`List<long[]>`）：每次传感器被激活记录 `{传感器序号, 系统毫秒}`；每 tick 记录，避免每秒采样漏掉短脉冲。
- `lastPowered`（`int[]`）：上一 tick 每个传感器的 `POWERED` 值。

### `static synchronized void watchSensor(BlockPos pos)`
- 作用：加入监视列表并扩展 `lastPowered`。调用者：`testkit/src/main/java/mtm/testkit/serverdriver/Lines.java:346`。

### `static synchronized void tickSensors(MinecraftServer server)`
- 作用：每个服务器 tick，读主世界中每个被监视方块的 `BlockTrainPoweredSensorBase.POWERED`（没有该属性按 0）；值从非 2 变为 2 时记一次脉冲。
- 注意：只看主世界（overworld）。`POWERED == 2` 的含义见 `BlockTrainPoweredSensorBase`（此处只按"变为 2"计脉冲）。

### `private static synchronized JsonObject sensors()`
- 返回 `{"pulses":[{"sensor":序号,"millis":毫秒},…]}`（全部历史，不清空）。

### `static synchronized void start(MinecraftServer server)`
- 作用：先 `stop()` 旧服务，再注册路由并启动：
  - `/ping` → `ping`；
  - `/command`（`command`）→ `command`；
  - `/snapshot?dimension=` → `snapshot`；
  - `/fixture`（`name`，默认 `basic`）→ `name == "sidings"` 时 `Sidings.build(主世界, 请求体)`，`name == "lines"` 时 `Lines.build`，否则 `Fixture.build`（主世界）；
  - `/node`（`x,y,z`）→ `node`；
  - `/trains?dimension=` → `trains`；
  - `/clock` → `clock`；`/sensors` → `sensors`；`/log?since=` → `LogCapture.since`；
  - `/stop` → `server.halt(false)`（不等待地停服）。
- 副作用：开端口、写端点文件；失败只写错误日志。

### `static synchronized void stop()`
- 停止 HTTP 服务并置空。调用者：`testkit/src/main/java/mtm/testkit/TestKit.java:23`、本类 `start`。

### `private static ServerLevel level(MinecraftServer server, String dimension)`
- `dimension` 为空返回主世界；否则按维度 id 查找，找不到抛 `IllegalArgumentException`。

### `private static JsonObject ping(MinecraftServer)`
- 返回 `side="server"`、`tick`（服务器 tick 计数）、`players`（名字、坐标、维度）。

### `private static JsonObject command(MinecraftServer server, String command)`
- 作用：用自定义 `CommandSource`（收集输出文本，接受成功/失败消息，不通知管理员）构造权限等级 4、位于世界出生点、名为 `MTM_TestKit` 的命令源，执行命令（可带或不带 `/`）。
- 返回：`{"result": 命令返回值, "output": [输出行…]}`。

### `private static JsonObject node(ServerLevel level, BlockPos pos)`
- 作用：报告一个轨道节点：方块注册名、`BlockNode.getAngle` 角度（若是节点方块）、是否关键节点（`railwayData.keyNodes`）、以及从该节点出发的每条轨道（反射读 `RailwayData.rails` 私有字段）：终点坐标、轨道类型、两端朝向角度（按终点坐标排序）。
- 异常：反射失败抛 `IllegalStateException`。

### `private static JsonObject trains(ServerLevel level)`
- 作用：反射读每个 `Siding` 的私有 `trains` 集合，列出每辆 `TrainServer`：`id`、`siding`、`depot`（经 `dataCache.sidingIdToDepot`，无则 0）、车头坐标（`getTrackPosition(0,0)`，保留 2 位小数）、`speed`、`onRoute`、`doors`、`progress`（`getRailProgress`）、`route`（当前趟的线路 id，字符串）、`trip`（`getService().getTripMillis()`）。同时给出 `millis` 和 `tick`。测试按时间多次采样，检查列车出库、运行、折返、回库且不跳变。
- 异常：反射失败抛 `IllegalStateException`。

### `private static JsonObject clock()`
- 作用：以时刻表使用的时区和运营日给出当前时间：`millis`、`zone`（`transitsim.plan.OperatingDay.getTimeZone()`）、`time`（HH:mm:ss）、`dayStartHour`（运营日开始小时）。

### `private static JsonObject snapshot(ServerLevel level)`
- 作用：`DataJson.snapshot(车站, 站台, 停车轨, 线路, 车厂)` 加上 `dimension`。该维度没有 `RailwayData` 时抛 `IllegalStateException`。
