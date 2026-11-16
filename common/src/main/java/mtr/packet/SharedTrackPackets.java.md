# SharedTrackPackets.java

服务端→客户端：分开排班的车厂共用的轨道（`mtr.advanced.SharedTrack`，0.85.0）。区域变化时发给所有玩家（`sendAll`，`RailwayData.updateSharedAreas`），玩家请求地图时发一次（`send`，`RailwayData.sendMapRails`）。客户端收下整体替换 `areas` 并递增 `version`，`EditorData` 输出为网页的 `sharedAreas`。

格式：区域数；每个区域 `key`、单位数（每个单位：车厂数+各 id、线路数+各线路键）、车站数+各 id、长度（double）、起点（long）。各数量有上限，坏包整个丢弃（`read` 返回 null）。测试 `SharedTrackPacketsTest`。
