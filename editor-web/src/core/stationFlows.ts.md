# stationFlows.ts

车站股道运用（0.80.0，用户 2026-11-14：排查运用的冲突流）：一个车站周围的轨道，每条线的车在每段轨道上往哪个方向走。纯计算，`StationFlows.tsx` 画。

## `stationFlows(net, stationId, radius = 160)`
- 输入：网络数据（`EditorNetwork` 的 `nodes`、`rails`、`stations`、`routes`、`dutyUses`）、车站 id（不带 `s:`）、范围（格，轨道任一点在车站中心这么远以内就取）。
- 载客运行：每条线路相邻两个节点之间的轨道和方向（`forward` = 从轨道较小的节点走向较大的节点）。
- 运用走行：`dutyUses` 里同一条线（`lineKey`）的线路没有按同一方向走过的轨道（折返、换站台、出入库），`move: true`；没有对应线路的用灰色。
- 每段轨道两个方向都有车走时 `both`（红底），折返线本来就是这样，其余的值得看一眼。
- 返回 `{rails[{rail, points, kind, both}], flows[{rail, forward, color, lineKey, route, move}], lines[{lineKey, name, color, services, moves}], box}`，车站不存在时 null。
- 测试：`test/stationFlows.test.tsx`。
