# TurnBackLengths.java（0.79.4 新增）

找出路径里「在比列车短的折返线上掉头」的地方（2026-11-14：六线图 6 号线 75 格的车在 50 格的尽头折返线上掉头，车身压着道岔，两条正线都被挡住）。

- `find(path, isTurnBack, trainLength)`：凡是下一段轨道是这段的反向（`isOppositeOf`）、且这段是折返线的地方，把往回连续的折返线长度加起来；比列车短（差半格以内算取整误差）就记一条 `Short(rail, length)`。站台或正线上的掉头、只是穿过折返线的不算。哪些轨道是折返线由宿主给（游戏侧是 `RailType.TURN_BACK`）。
- 调用者：游戏 `Depot.tellShortTurnBacks`（找完车厂路径后，变了才在日志说一次）。

测试：`TurnBackLengthsTest`（3 项）。
