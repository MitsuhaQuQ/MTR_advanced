# api/types.ts

编辑器端点返回数据的 TypeScript 形状，按服务器 `EditorData` / `EditorServer` 的写法（规格见 `MTR-网页编辑器规格.md` 第 7 节）。只给新页面目前读到的字段写了类型，其余字段原样透传。只有类型，没有运行时代码。草稿类型在 `core/draft.ts`，名称和颜色在 `core/model.ts`。

## 类型
- `Stop`：路线的一个停站。`station` 车站 ID；`name` 车站名；`platformId` 站台 ID；`platform` 站台名；`pass` 是否通过不停；`passingTrack?` 是否走通过线（未确定精确含义，按字段名）；`boundary?` 是否为分区边界站；`dwellOverride?` 停站时间覆盖（单位未在本文件写明，未确定）。
- `Table`：一个时刻表。`name`；`weekdays` 按位表示运行的星期，周一为最低位；`marginPercent` 余量百分比；`services` 各车次在首站的发车时刻，单位毫秒，自运营日起点算起。
- `Route`：一条路线。`id`、`name`、`line`（线路名）、`lineKey`（"运输方式|线路名"，同一线路组的路线共用）、`serviceType`、`color`、`lineColor`（RGB 整数）、`circular`、`timetable`、`editorManaged`、`connectingLine`、`revision`（游戏里的版本，用于冲突检测）、`parts?`（各部分版本，键名未确定）、`editors?`、`headways?`（0.82.0：比前一班跟得太近的班次，`{from, to, service, before, gap, needed}`）、`stops`、`hasTimes`、`pathFailure?`（寻路失败说明）、`tables`。
- `Siding`：股道。`id`、`name`、`maxTrains`、`fleet?`、`routes?`（路线 ID）、`vehicle?`。
- `Pool`：车辆池。`key`、`vehicle`、`fleet`（可空）、`sum`、`sidings`（股道 ID）。
- `Depot`：车厂。`id`、`name`、`routes`、`sidings`、`pools?`、`notConfigured?`、`oldLogic?`（使用旧逻辑）、`path?`（运用寻路状态 `state`：`generating`/`failed`/`stale`/`found`，说明 `text`，失败运用数 `failedDuties`）、`shortfall?`（未覆盖车次数 `uncovered`，`replan?`）。
- `EditorData`：`api/data` 的整体。`dayStartMillis` 运营日起点（毫秒时间戳）；`offsetMillis`；`canEdit` 是否有编辑权限；`simulation` 是否允许网页模拟；`language` 游戏语言；`regionCount`；`networkSettings`（`minReverseMillis` 最短折返时间、`earlyThresholdMillis` 早点阈值，毫秒）；`routes`；`depots`。

## 使用者
- `api/client.ts:1`、`core/draft.ts:8`、`core/model.ts:5`、`state/store.tsx:10`。
