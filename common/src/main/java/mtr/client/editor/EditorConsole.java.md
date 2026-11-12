# EditorConsole.java

网页编辑器的调度台（D6）：显示本维度按运用运行的列车（每列车头所在的信号闭塞）和被占用的闭塞，数据来自服务器最近发来的快照；并立即发送调度员的命令。客户端侧，在游戏线程上运行。快照与命令收发在 `DispatchConsole`（`sendRequestC2S`、`sendCommandC2S`、`clientSnapshot`），命令码在组件 `TrainService`。`EditorServer.java:554` 调 `build`，`EditorActions.java:386` 调 `applyCommand`。

## final class EditorConsole

### `private EditorConsole()`
工具类，禁止实例化。

### `public static JsonObject build()`
- 作用：请求服务器发一个新快照（`DispatchConsole.sendRequestC2S()`），并用上一个快照生成 JSON。
- 返回字段：
  - `available`：是否已有快照；没有时只返回这一项。
  - `age`：快照年龄（秒），按 `clockOffset` 修正客户端与服务器时钟差。
  - `trains[]`：每列车 `id, siding, sidingName, depot, depotName, route, routeName, slot, head ([msb, lsb] 字符串，车头所在轨道 UUID), block (车头所在闭塞 id，可无), next (下一站车站名), nextStation (下一站车站 id), lastStation, lastName, stopState, late (晚点秒数), waiting (等候秒数), skipping (flags&1), outOfService (flags&2), noDuty (flags&4), turn, takeover, dispatch`。`stopState/turn/takeover/dispatch` 的取值由 `DispatchConsole.Snapshot` 定义，此处未展开。
  - `blocks[]`：被占用的闭塞与列车车头所在的闭塞，每个 `id, color, occupied, rails[] ([msb, lsb])`。每条轨道归属于按颜色序排在最前的闭塞（与游戏显示一致）。
- 副作用：发送一个请求包。
- 主要调用者：`EditorServer.java:554`。

### `public static String applyCommand(JsonObject operation)`
- 作用：把一条调度命令立即发给服务器。
- 参数 `operation` 字段：`train`（列车 id 字符串）；`command`：`hold`（扣车）、`release`（放行）、`extend`（延长停站，`seconds` 秒，限制在 `[0, TrainService.MAX_EXTRA_DWELL_MILLIS/1000]`）、`turn`（在某站折返，`station` 为车站 id，转为该站全部站台 id）、`clearOut`（清客）、`return`（回库）、`cancel`（取消）。
- 返回：错误消息（`gui.mtr.editor_bad_change` 的翻译）或 `null`。
- 副作用：`DispatchConsole.sendCommandC2S(trainId, code, value(ms), platformIds)`。
- 主要调用者：`EditorActions.java:386`。
