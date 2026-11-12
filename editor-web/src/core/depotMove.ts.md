# core/depotMove.ts

在车厂之间移动工作（0.69.2，Q 世界 2026-11-12）：把另一车厂的一组运用放进本车厂、用本车厂的某车型跑（不论原来用哪种车）；把一条线路的路线连同运用从一个车厂移到另一个车厂，原车厂该线路的运用删去。线路换车型的办法是移到有那种车的车厂；两车厂互换线路即两次移动。所有结果只进草稿：车厂的路线与存车线按车厂面板保存的格式，运用按运用编辑保存的格式。这里的函数**直接修改**传入对象、不读其他东西，面板和测试都这样调用。被 `views/DepotExtras.tsx`（运用组导入）、`views/MoveLinePanel.tsx`（线路换车厂）使用。

## 类型
- `Slot`（内部）：一个运用位（存车线上一列车）的各"日"条目数组，元素含 `days`（星期位掩码）、`stay`、`next`、`duty`、`start`、`nextDuty`、`returnSiding`、`fromOvernight`、`segments`（`{route, …}`）。
- `Duties`：`{ mode, sidings: 存车线 ID → Slot[], auto? }`。
- `DepotEntry`：车厂面板保存的车厂条目：`routes`、`sidings`（存车线 ID → `{fleet, routes}`，`routes` 为空表示跑全部）及其他字段。

## 函数
- `sidingsOf(depot, vehicle)`（导出）：车厂中车型为 `vehicle` 的存车线（未设车型为 `""`），按车厂顺序。`src` 外部未见直接调用，本文件内多处使用。
- `vehiclesOf(depot)`（导出）：车厂存车线的车型，去重、按出现顺序。调用者：`views/DepotExtras.tsx:100`、`views/MoveLinePanel.tsx:40`、`views/MoveLinePanel.tsx:52`。
- `dutyGroupsOf(depot, duties)`（导出）：按运用图模式时，统计"车型|星期掩码" → 运用日条目数；非运用图模式返回空 Map。调用者：`views/DepotExtras.tsx:103`。
- `putDays(duties, targets, perSource)`（内部）：把多组"日"条目放进目标存车线：第 k 组放进第 k 条目标（多出的都放最后一条）；每条放进第一个在其星期上空闲的运用位，没有则新建位；保留该位已有的运用号，新位用 `newDutyId()`；新条目 `next:-1`、`start` 沿用该位起点或 `"prefer"`、`nextDuty:"0"`、`returnSiding:"0"`，`segments` 深拷贝。返回放入条数。
- `applyDutyGroup(duties, depot, source, sourceDuties, vehicle, mask, targetVehicle = vehicle)`（导出）：把来源车厂 `source` 中车型 `vehicle`、星期恰为 `mask` 的运用放进本车厂车型 `targetVehicle` 的存车线：先从目标存车线删掉这些星期的条目（空条目删去），再 `putDays`；`duties.mode` 设为 `"diagram"`。返回放入数，本车厂没有该车型存车线时 -1。调用者：`views/DepotExtras.tsx:114`。
- `dropEmptySlots(duties)`（内部）：删去没有任何"日"条目的运用位，并修正同一存车线中其他条目的 `next` 链接（指向被删位的改为 -1，之后的减 1）。
- `hasDutiesOf(duties, routeIds)`（导出）：运用中是否有这些路线的班次。调用者：`views/MoveLinePanel.tsx:44`、`views/MoveLinePanel.tsx:62`。
- `takeRoutes(duties, depot, routeIds)`（导出）：从车厂运用中取出这些路线的班次（移出方）：每个"日"条目保留其他班次，取空则删条目，再 `dropEmptySlots`。被取出的条目只含这些路线的班次（若部分取出，`stay` 设为 false）。返回 `Map<车型, 每条该车型存车线的条目数组>`。调用者：`views/MoveLinePanel.tsx:64`、`views/MoveLinePanel.tsx:65`。
- `putTaken(duties, depot, taken, targetVehicle)`（导出）：把 `takeRoutes` 的结果放进目标车厂车型 `targetVehicle` 的存车线（保留其他运用），`mode` 设为 `"diagram"`。返回放入数或 -1（无该车型）。调用者：`views/MoveLinePanel.tsx:68`。
- `entryWithout(entry, routeIds)`（导出）：从车厂条目及其各存车线中去掉这些路线；只跑这些路线的存车线随之变成跑剩下的全部路线（与面板对"不勾选"的存法一致）。返回该条目。调用者：`views/MoveLinePanel.tsx:59`。
- `entryWith(entry, depot, routeIds, vehicle)`（导出）：把路线加进车厂条目，并加进该车型中"只跑部分路线"的存车线（跑全部的不用加）。返回该条目。调用者：`views/MoveLinePanel.tsx:60`。
