# FrequencyTimetable.java

按频率或按发车列表派车的车厂，转换为其路线时刻表的班次（R2），使它像其它车厂一样按运用运行。车厂给出真实一天中每小时的行车间隔（分钟）（`departuresOfHeadways`）。游戏原有的频率是按游戏小时（游戏一天 20 分钟，有 24 个游戏小时），频率 N 表示每 `200 / N` 秒发一列；旧车厂的频率转为同编号真实小时内的间隔分钟，取最接近的整分钟（`headwayOf`）。一列车原先依次跑车厂各路线，所以第一条之后的每条路线，其班次在前一条到达末站后一个折返时间。由游戏侧 `mtr.data.Depot`（`Depot.java:381`、`:983`、`:990`、`:1068`、`:1076`）使用。

## 类 `FrequencyTimetable`（final，工具类）

### 常量
- `GAME_HOUR_MILLIS = 50000`：一个游戏小时（1000 tick）。
- `FREQUENCY_SPACING_MILLIS = 200000`：频率为 1 时的发车间隔（游戏的算法）。
- `DAY_MILLIS`：一天毫秒数。
- `TURN_MILLIS = 60 秒`：列车从一条路线转到下一条的最短时间。
- `MAX_HEADWAY_MINUTES = 60`：可设置的最大间隔分钟。

### `private FrequencyTimetable()`
禁止实例化。

### `public static List<Integer> departuresOfHeadways(int[] headways, int max)`
- 作用：从午夜起每个有车的真实小时，从该小时开始每隔若干分钟发一列，直到该小时结束。
- 参数：`headways` 每个真实小时的间隔分钟（最多 24 个），0 为无车；`max` 最多发车数。
- 返回值：一天中的发车时刻（毫秒），有序。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:1068`（非真实时钟发车列表时）。

### `public static int headwayOf(int frequency)`
- 作用（R2）：游戏频率 → 同编号真实小时内的间隔分钟：`round(200000 / frequency / 60000)`，夹到 `[1, 60]`；频率 ≤0 为 0。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:990`。

### `public static List<Integer> departuresOf(int[] frequencies, int max)`
- 作用：按游戏方式在真实一天中发车：从 0 开始，取当前所在游戏小时的频率；为 0 时跳到下一个游戏小时，否则记录并前进 `200000 / frequency` 毫秒。
- 参数：`frequencies` 游戏 24 小时的频率；`max` 上限。
- 返回值：发车时刻列表；`frequencies` 为空或 null 时空列表。
- 主要调用者：main 代码中无调用，只有测试 `transit-sim/src/test/java/transitsim/planning/FrequencyTimetableTest.java:40`。

### `public static List<Integer> cleaned(Collection<Integer> departures, int max)`
- 作用：发车时刻取模一天、去重、排序，截到 `max` 个。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:1068`。

### `public static List<List<Integer>> chain(List<Integer> firstServices, List<long[][]> routeTimes)`
- 作用：第一条路线用 `firstServices`；之后每条路线的班次 = 前一条路线各班次 + 前一条路线末站到达时间 + `TURN_MILLIS`（取模一天）。遇到前一条路线时间未知（null 或空）时停止。
- 参数：`routeTimes` 每条路线从首站出发起各站的最快 {到达, 出发}，未知为 null。
- 返回值：每条路线的班次列表（可能少于路线数）。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:1076`。
