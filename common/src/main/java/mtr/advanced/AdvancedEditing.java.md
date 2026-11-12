# AdvancedEditing.java

本文件是一个只含静态方法的接口，放"高级编辑器"（浏览器编辑器）对游戏自带界面的约束规则。按类注释的说法，`mtr.advanced` 包存放本分支在 MTR 之上加的逻辑，原版类只保留存档字段并调用这里。目前只有一条规则：由高级编辑器管理的线路（`Route.editorManaged`）在游戏内界面里只读。没有对应的 `transit-sim` 组件代码。

## interface AdvancedEditing

### `static boolean isLockedInGame(Route route)`
- 作用：判断一条线路在游戏自带界面（仪表盘、时刻表界面）里是否应锁定为只读——其时刻表与停站由高级编辑器维护。
- 参数：`route` 线路，可为 `null`。
- 返回：`route != null && route.editorManaged`。
- 副作用：无。
- 主要调用者：`DashboardScreen.java:156`、`DashboardScreen.java:490`、`TimetableScreen.java:604`。
