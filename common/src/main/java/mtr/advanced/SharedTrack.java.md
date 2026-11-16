# SharedTrack.java

分开排班的车厂共用的轨道（0.84.16 起服务端日志，0.85.0 起经 `SharedTrackPackets` 发给网页检查清单）。客户端只有存车线自身的轨道，没有车次路径，所以只在服务端算。

## `find(depots, sidings, platforms, dataCache, rails)`
- 单位：`LineDuties.unitDepotIdsOf`（含 `NetworkSettings.diagramJoins` 并入关系）；连续运动的车厂不计。
- 用 `transitsim.network.SharedAreas` 找区域，再给每个区域补：各单位在其上跑的线路（存车线路径经过该区域的车厂的线路，`LineDuties.linesOf`）、站台所在车站、长度（`rails` 中轨道长度之和）、起点、`key`（`SharedAreas.Area.describe`）。
- 调用者：`RailwayData.updateSharedAreas`。

## 类 Area / Unit
- `Area`：`units`、`stationIds`、`length`（格）、`start`、`key`。
- `Unit`：`depotIds`、`lines`（线路键）。
