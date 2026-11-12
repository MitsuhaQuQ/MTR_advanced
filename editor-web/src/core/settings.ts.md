# core/settings.ts

旧 `editor.js` 的全网设置部分（退役旧代码第 6 步）：草稿中的全网设置及其各部分（逻辑线路、区段的逻辑线路、运营线路走的轨道、手工设定的区段归属、等候分界），站台的过站限速、停车位置、所属车站，车厂的出入库规则和车厂条目（配属、线路、车型），以及寻路边界。轨道所在区段以其各段轨道的键（"a,b"）传入，由调用方从显示的路网算出；函数只修改传入的草稿。主要经 `legacy/retired.ts` 的 `settingsRules`（以 `X` 导入）注入旧代码，`legacy/dialogs.ts`、`views/AskPanels.tsx`、`views/OwnershipPanel.tsx` 等也直接导入。

## 类型与常量
- `LogicalLine`：`{ id, name, color（RGB 整数）, connecting?（联络线） }`。
- `PLATFORM_PARTS`：站台三项设置及游戏中的值：`platformSpeeds`（`passSpeed`，缺省 80 km/h）、`platformStops`（`stopPosition`，缺省 0）、`platformStations`（`stationSet`，缺省 `"0"` 自动）。`PlatformPart` 为其键。
- `PASS_SPEEDS = [80, 100, 120, 160, 200, 300]`：过站限速可选值（km/h）。调用者：`legacy/retired.ts:229`。
- `STOP_POSITIONS = [0, -1, 2, 5, 10, 20]`：停车位置可选值（0 出站端、-1 按车长居中、正数离出站端格数）。调用者：`legacy/retired.ts:230`。

## 函数
- `draftSettings(draft)`：草稿全网设置或 `{}`。`savedSettings(data)`：游戏全网设置或 `{}`。调用者：`legacy/retired.ts:201`、`legacy/retired.ts:202`。
- `compareRailKeys(a, b)`：按服务器顺序比较轨道键 "a,b"（两个长整数节点位置，用 BigInt）。调用者：本文件、`legacy/retired.ts:203`。
- `logicalLinesShown(data, draft)`：游戏逻辑线路叠加草稿增删改后的 `Map<id, LogicalLine>`。调用者：本文件、`legacy/retired.ts:204`。
- `usesLogicalLines(data, draft)`：是否有逻辑线路。调用者：`legacy/retired.ts:205`。
- `railLinesShown(data, draft)`：各轨道键 → 逻辑线路 ID（草稿覆盖游戏，null 表示取消）。调用者：本文件、`legacy/retired.ts:206`。
- `sectionLogicalLine(data, draft, sectionKeys)`：区段的逻辑线路：区段内设了有效逻辑线路的轨道中键最小者（同服务器），没有为 null。调用者：`legacy/retired.ts:207`（`railLogicalLine`）。
- `sectionLogicalDrafted(draft, sectionKeys)`：区段是否有轨道在草稿里改了逻辑线路。调用者：`legacy/retired.ts:208`。
- `setSettingsPart(data, draft, part, change)`：修改草稿全网设置的一部分：以草稿该部分的副本调用 `change(drafted, saved)`（`logicalLines` 的 `saved` 转为 `id → {name, color, connecting}`）；去掉与游戏相同的项（`null` 且游戏中没有的也去掉）；该部分为空则删除，整个设置为空则 `networkSettings = null`。直接修改 `draft`。调用者：本文件、`legacy/retired.ts:210`。
- `setSectionLogicalLine(data, draft, key, sectionKeys, id)`：把区段放进逻辑线路（`id` 为 null 则移出）：设定保存在所选轨道 `key` 上，并从区段其他轨道上去掉（游戏中有的设为 null）。调用者：`legacy/retired.ts:215`。
- `newLogicalId()`：新逻辑线路 ID（`Date.now()*1000 + 随机 0–999` 的字符串）。调用者：`legacy/retired.ts:218`。
- `setLogicalLine(data, draft, id, value)`：新建或修改逻辑线路。`src` 中未见直接调用（未确定）。
- `deleteLogicalLine(data, draft, id)`：删除逻辑线路，连同属于它的区段设定和各运营线路对它的选择。调用者：`legacy/dialogs.ts:104`。
- `lineTracksShown(data, draft)`：运营线路键 → 所走逻辑线路 ID 列表（草稿覆盖游戏；没有表示按站台所在的逻辑线路）。调用者：`views/AskPanels.tsx:165`。
- `setLineTracks(data, draft, lineKey, ids)`：设置运营线路走的逻辑线路。`src` 中未见直接调用（未确定）。
- `draftOwnerOverrides(draft)` / `savedOwnerOverrides(data)`：草稿 / 游戏中手工设定的区段归属：轨道键 → 归属者列表（草稿中 null 表示恢复自动）。调用者：`legacy/retired.ts:219`、`legacy/retired.ts:220`。
- `sectionOwnersShown(data, draft, sectionKeys, entry)`：区段显示的归属：草稿没改时用服务器算的 `entry`（`owners`、`manual`）；草稿改了时，区段各轨道（草稿的按草稿、其余按游戏）手设归属的并集；都没有手设则表示草稿把它恢复为自动。返回 `{ owners, manual, drafted, auto }`。调用者：`legacy/retired.ts:221`（`railOwnersShown`）。
- `setSectionOwners(data, draft, key, sectionKeys, lines)`：在草稿中手工设定区段归属：`lines` 为线路列表、空列表（对所有线路开放）或 null（恢复自动）；保存在所选轨道上，从区段其他轨道去掉；只是撤回未应用改动的项去掉；更新 `networkSettings.ownerOverrides`。调用者：`legacy/retired.ts:224`。
- `waitThresholdMillis(data, draft)`：全网等候分界（毫秒）：草稿优先，其次游戏，缺省 300000。调用者：`legacy/retired.ts:228`。
- `setStationWait(draft, stationId, millis)`：设某车站自己的等候分界（毫秒），null 表示用全网的。直接修改 `draft.networkSettings.stationWaitThresholds`。调用者：`legacy/dialogs.ts:169`。
- `platformSetting(draft, part, p)`：站台某项设置：草稿中有则用草稿，否则游戏值。调用者：`legacy/retired.ts:197`、`legacy/retired.ts:235`。
- `setPlatformSetting(draft, part, p, value)`：在草稿设置站台某项；等于游戏值则删去。调用者：`legacy/retired.ts:198`、`legacy/retired.ts:243`。
- `rulesOf(draft, depot)`：车厂出入库规则与命名回送路径（`{rules, paths}`）的副本：草稿优先，否则游戏的，缺省空。调用者：`legacy/retired.ts:246`。
- `sameVehicle(drafted, game)`：草稿的车型设置是否等于游戏的（`acceleration`、`deceleration`、`manual`、`maxSpeed`、`maxManualSpeed`、`manualToAutomaticTime`，数值误差 1e-6；游戏设置需 `set` 为真才算有）。调用者：本文件 `storeDepotEntry`、`legacy/retired.ts:247`。
- `depotEntryOf(draft, depot)`：车厂条目，用于修改后再 `storeDepotEntry`（车厂面板与车型面板改同一条目的不同部分）：草稿中有则深拷贝并补齐 `vehicleSettings`、`serviceTypes`、`poolFleets`（缺的车池用游戏值）；否则由游戏车厂生成 `{ base: revision, routes, cycleRoutes, sidings（id → {fleet, routes}）, poolFleets, vehicleSettings, serviceTypes: {}, clearBuffers: false, addedBuffers: null }`。调用者：`legacy/retired.ts:248`；`views/MoveLinePanel.tsx:59` 等经 `fn.depotEntryOf`。
- `storeDepotEntry(draft, depot, entry, sameAdded?)`：把车厂条目放进草稿；与游戏完全相同（线路、长时间停留、各存车线配属与线路、各车池配属与车型设置、无种别限制、不清余量、追加余量相同——`sameAdded` 未给时要求 `addedBuffers` 为空）则从草稿删去。调用者：`legacy/retired.ts:250`。
- `setBoundary(draft, key, boundary, wasBoundary)`：在草稿设置或取消轨道 `key` 的寻路边界；与原来相同则删去。调用者：`legacy/retired.ts:256`。
