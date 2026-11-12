# PlanAdapters.java

把游戏侧数据适配成 `transit-sim` 组件时刻表规则所需的接口（除 `transitsim.plan.PlanRoute` 已提供的之外）。目前只把站台的"时刻表边界"标记转成 `LongPredicate`，交给组件的 `transitsim.plan.TimetableBoundaries` 使用。被编辑器数据、编辑器动作和时刻表 GUI 包处理代码使用。

## final class PlanAdapters

### `private PlanAdapters()`
工具类，禁止实例化。

### `public static LongPredicate boundaryPlatforms(Map<Long, Platform> platforms)`
- 作用：返回一个谓词，判断给定站台 id 是否被标为时刻表边界。
- 参数：`platforms` 站台 id → `Platform` 的映射（通常是 `DataCache.platformIdMap`）。谓词捕获该映射引用，之后映射内容变化会反映到谓词结果。
- 返回：`platformId -> platform != null && platform.timetableBoundary`；未知 id 返回 `false`。
- 副作用：无。
- 主要调用者：`EditorActions.java:979`、`EditorData.java:492`、`PacketTrainDataGuiServer.java:385`。
