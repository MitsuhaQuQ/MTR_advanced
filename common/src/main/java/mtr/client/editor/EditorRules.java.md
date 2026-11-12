# EditorRules.java

车厂出入库规则与命名回送路径（"运用补完"第 2 步）在高级编辑器中的 JSON 表示：id 一律为字符串（避免 JS 丢失 long 精度），时间为毫秒。数据本体是组件的 `transitsim.plan.DepotRules`。`EditorData` 用 `toJson` 发给网页，`EditorActions`（以及 `EditorContingencies`）用 `fromJson` 读网页提交的规则。

## final class EditorRules

### `private EditorRules()`
工具类，禁止实例化。

### `public static JsonObject toJson(DepotRules rules)`
- 作用：序列化规则。
- 返回 JSON：
  - `rules[]`：每条 `{id, lines[] (线路键), weekdays (位掩码), outEntrance, backEntrance, extraLead (ms), backSiding, outPath, backPath, waitThreshold (ms)}`，id 类字段为字符串。
  - `paths[]`：每条 `{id, name, kind ("out"/"back"/"shunt"), from, to, waypoints[] (字符串)}`。
- 主要调用者：`EditorData.java:376`。

### `public static DepotRules fromJson(JsonElement element)`
- 作用：尽量读取网页发来的规则和路径；不是对象时返回 `null`。
- 校验与取值：规则数不超过 `DepotRules.MAX_RULES`、路径数不超过 `MAX_PATHS`、途经点不超过 `MAX_WAYPOINTS`（多余忽略）；`weekdays & DepotDuties.ALL_WEEKDAYS`，缺省 0；`extraLead` 限制在 `[0, 3600000]`，缺省 0；`waitThreshold` 限制在 `[-1, NetworkSettings.MAX_WAIT_THRESHOLD_MILLIS]`，缺省 −1（含义推测为"用网络默认"，未确定）；`kind` 未知按 `OUT`。
- 异常：途经点字符串不是整数时 `Long.parseLong` 会抛 `NumberFormatException`（未捕获）。
- 主要调用者：`EditorActions.java:158`、`EditorActions.java:401`、`EditorContingencies.java:199`。

### `private static long id(JsonObject object, String key)`
- 作用：读字符串形式的 id；缺失、null 或非整数返回 0。
