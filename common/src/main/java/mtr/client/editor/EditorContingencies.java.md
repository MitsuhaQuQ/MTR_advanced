# EditorContingencies.java

高级编辑器中的应急预案（D2）：每个轨道系统针对晚点或受阻列车的预案，以及每个车厂上报的列车和预案正作用于的列车。预案在草稿中编辑；启动预案、为单列车设定处置、结束二者都是调度员命令，立即发送。预案对象是组件 `transitsim.plan.Contingency` / `Contingencies`，客户端的系统表在 `ClientData.CONTINGENCY_SYSTEMS`（`ContingencySystems`），发送经 `ContingencyPackets`。客户端代码：`EditorData.java:354`（`addToDepot`）、`:424`（`systemsToJson`），`EditorActions.java:379`（`applyPlans`）、`:382`（`applyCommand`）。

## final class EditorContingencies

### 常量
- `TURNS = {"none", "nearest", "station"}`：折返方式（`Contingency.turn` 的下标：不折返、最近可折返处、指定车站）。
- `AFTERS = {"resume", "deadhead"}`：折返后的处置（`afterTurn`：恢复运营、回送）。

### `private EditorContingencies()`
工具类，禁止实例化。

### `public static JsonObject toJson(Contingency contingency)`
- 返回：`{id, name, routes[], lateMinutes, waitMinutes, deadlock, manual, turn, turnStation, afterTurn, skipStations[], takeover}`，id 为字符串。字段含义见组件 `Contingency`（大意：晚点阈值分钟、等待阈值分钟、是否对死锁生效、是否仅手动启动、折返方式、折返车站、折返后处置、跳停车站、是否由他车接替运用）。
- 调用者：本类 `addPlans`。

### `public static Contingency fromJson(JsonObject object)`
- 作用：读取预案；名称经 `Contingency.limitName` 限长；线路最多 `MAX_ROUTES`、跳停车站最多 `MAX_STATIONS`；`lateMinutes` 缺省 5、`waitMinutes` 缺省 10，限制在 `[0, MAX_MINUTES]`；`deadlock` 缺省 true、`manual` 缺省 false、`takeover` 缺省 false；未知的 `turn`/`afterTurn` 取 0。
- 调用者：本类 `applyPlans`、`applyCommand`。

### `public static void addToDepot(JsonObject depotObject, Depot depot)`
- 作用：把车厂上报的晚点/受阻列车及预案作用中的列车加到车厂 JSON 的 `alarms[]`。数据来自 `depot.clientAlarms`（服务器发来的字符串列表），条目 `"|"` 之后的为"已处理"（预案作用中）。每条以空格分隔：`train siding slot route reason lateSince waitingSince plan system [skipping turn takeover dispatch]`，后 4 项分别自 0.31.0（D3 跳停）、0.32.0（D4 提前折返）、0.33.0（D5 交接运用）、0.35.0（D6 调度命令）起发送。少于 9 项的忽略。`late`、`waiting` 用 `clientAlarmClockOffset` 换算成服务器时钟下的秒数。
- 调用者：`EditorData.java:354`。

### `public static JsonArray systemsToJson()`
- 作用：列出有线路的轨道系统（每个是一个相连的轨道网络，D2）：按线路首站台所在网络分组；锚点由 `anchorOf` 得出（0 则跳过）。
- 返回每个系统：`{anchor, routes[], stations[] ({id, name}), plans[] (含 active), adhoc{trainId: 设置}, others[] (轨道连入本网络、尚未合并的其它系统 {anchor, plans, adhoc})}`。
- 调用者：`EditorData.java:424`。

### `private static void addPlans(JsonObject object, Contingencies contingencies)`
- 作用：写 `plans[]`（每项 `toJson` + `active`）和 `adhoc`（按列车 id 的单车设置）。

### `private static Contingencies copyOf(long anchor)`
- 返回：客户端现有该系统预案与状态的副本（无则新建），用于修改后发送。

### `public static String applyPlans(JsonObject operation)`
- 作用：草稿中为一个系统编辑的预案整体替换其预案。`operation.system` 必须是存在的站台 id，`plans` 必须是数组；预案数不超过 `Contingencies.MAX_PLANS`，id ≤ 0 或重复的忽略。发 `ContingencyPackets.sendPlansC2S(anchor, contingencies)`。
- 返回：错误消息（`gui.mtr.editor_bad_change`）或 `null`。

### `public static String applyCommand(JsonObject operation)`
- 作用：调度员命令，`action`：
  - `activate`：启动等待手动启动的预案 `plan`（必须存在）；
  - `deactivate`：结束预案；
  - `adhoc`：为列车 `train` 设置处置 `setting`（转为 id=0、无线路、非手动），单车设置数不超过 `MAX_ADHOC`（已有该车时可替换）；
  - `release`：结束对该列车的处置。
  然后 `ContingencyPackets.sendStateC2S(anchor, contingencies)`。
- 返回：错误消息或 `null`。

### 私有辅助
- `parseId(JsonObject, String)`：字符串 id 转 long，失败或缺失为 0。
- `readIds(JsonObject, String, Set<Long>, int max)`：读字符串 id 数组，最多 max 个，跳过非数字。
- `getInt` / `getBoolean`：带默认值，类型错误时返回默认值。
- `clamp(int value, int max)`：限制到 `[0, max]`。
