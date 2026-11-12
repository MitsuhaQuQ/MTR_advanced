# core/abstractNet.ts

全网示意配线：把 `api/network` 给出的全部轨道（逐段首尾相连）折叠成车站和车站之间的走廊，按地铁线路图的方式布局。步骤：
1. 车站范围：每个车站的站台轨道（归最近的车站），以及沿站台轴线方向一个框内的所有节点。框内沿轴线的轨道是车站的股道，按并排位置每个位置一条"道"（lane）：站台（带编号）或正线。
2. 其余轨道是锚点之间的链（锚点：车站范围、道岔区、尽头）；相近的道岔节点合成一个道岔区，其成员按顺序作为"端口"保留。
3. 同两个地点之间的链合成一条走廊；每条轨道记住它两端连到的具体车站股道或道岔端口，使渡线、不等股数的合流和侧线保持真实连接关系。
4. 布局：每条走廊希望方向取整到 45°、长度随真实长度的平方根增长；过近的地点互相推开。结果单位为像素。
5. 路线的路径给经过的轨道上色。

被 `views/NetworkSchematic.tsx` 使用；`core/trackSchematic.ts` 引用其类型。

## 类型
- `NetData`：`api/network` 的数据：`nodes`（`[x, z, 键]`）、`rails`（`[节点a, 节点b, 种类, …]`，种类 1 为站台）、`stations?`（`{id, name, x, z}`）、`routes?`（`{id, color, lineKey, name, nodes: 节点序号[]}`）。
- `TopoPlatform`：拓扑中的站台：`id`、`name`、`station`、`line?`（`[x1, z1, x2, z2]`）、`passingTrack?`。
- `Lane`：车站的一道：`offset`（横向偏移，世界单位，从最小者起）、`platform`、`name`（站台名，多个用 `/`）、`colors`、`negative`/`positive`（是否在轴线负端/正端真的有轨道离开车站范围；否则画成尽头）。
- `Turnout`：车站咽喉中的道岔：`from`（靠站台中心的道）、`to`（更深入咽喉的道）、`end`（-1 轴线起端，1 末端）。
- `Place`：地点：`id`（`s:<车站>`、`j:<道岔区根节点>`、`e:<节点>`）、`kind`（`station`/`junction`/`end`）、`name`、`x`/`z`（世界坐标）、`px`/`py`（布局像素）、`angle`（车站轴向，45° 取整并限制在 -90°…90°）、`lanes`、`turnouts`、`ports`（端口数）。
- `Track`：走廊的一条轨道：`aLane`、`bLane`（两端连到的车站道或道岔端口序号）、`colors`、`kind`（链上最大的轨道种类）。
- `Corridor`：走廊：`a`、`b`（地点 ID，按字典序 `a < b`）、`tracks`（轨道数）、`length`（最长链的真实长度）、`colors`、`rails`、`lines: Track[]`。
- `Spot`：某段轨道画在哪里：走廊第几条轨道上从 a 到 b 的比例位置 `{corridor, track, at}`，或车站的某道 `{place, lane}`。
- `Schematic`：`{ places, corridors, width, height, spots: Map<轨道序号, Spot> }`。

## 常量（内部）
- `KIND_PLATFORM = 1`；`THROAT = 80`：车站沿站台方向向外延伸的距离（咽喉，世界单位）；`SNAP = π/4`；`hex(color)`：RGB 整数 → `#rrggbb`。

## 函数
### `abstractNetwork(net, platforms = [])`（导出）
- 参数：`net` 路网数据；`platforms` 拓扑站台（用于给车站股道起名：中点相距 4 以内的站台）。
- 内部步骤与辅助：
  - `mid`、`lengthOf`：轨道中点、长度；`adjacent`：节点 → 轨道；`other(rail, node)`：轨道另一端。
  - `railColors`：按路线经过的相邻节点对给轨道加路线颜色。
  - 站台轨道归 200 以内最近的车站；按 64 格网索引节点，以最长站台轨道定车站轴 `u` 与法向 `n`，框内节点归该车站（`areaOf`）。
  - `across(k, area)`：节点在车站法向上的坐标。`platformName(rail)`：站台轨道的站台名。
  - `lanesOf(area)`：两端都在车站内且两端横向差不超过 1.5 的轨道按横向偏移（2.5 以内并为一道）分道，记站台、站名、颜色；再看离开车站范围的轨道落在哪道哪端，得出 `negative`/`positive`。
  - `laneOf(k)`：节点所在的道（最近者）。
  - `turnoutsOf(area)`：车站内横跨不同道的轨道作为道岔，判定在哪一端并保持由内向外的方向，去重。
  - 道岔区：不在车站内、连接超过 2 条轨道的节点，30 以内的用并查集（`find`）合并；成员按较长的坐标轴排序作为端口（`junctionPort`）。
  - `placeOf(k)`：节点所属地点 ID。
  - 链：从每个锚点沿未走过的轨道一直走到下一个锚点（车站内部轨道不算链）。
  - 走廊：同一对地点（不同地点）的链合并；`laneAt(k)` 取车站道或道岔端口；记录每段轨道在走廊中的位置（`railAt` → `spots`），车站内部轨道记为车站道。
  - 地点：为用到的车站、道岔区、尽头建 `Place`；车站轴向超出 ±90° 时翻转 180°，道的顺序、偏移和道岔端一起翻转，相关 `spots` 与走廊轨道的道号也随之翻转。
  - 调用 `layout`，再把坐标平移到留 80 像素边距。
- 返回：`Schematic`。
- 调用者：`views/NetworkSchematic.tsx:41`。

### `layout(places, corridors)`（导出）
- 地铁图布局：先按世界坐标缩放到约 1200 像素跨度；每条走廊的目标向量方向为 45° 取整，长度：涉及非车站地点时 42–130（`20 + 3√长度`），车站之间 140–320（`80 + 5√长度`）。迭代 600 轮，每轮让两端各向目标修正 1/4；前 400 轮（且地点不超过 600 个）把距离小于 100 且没有走廊相连的地点互相推开。
- 直接修改 `places` 的 `px`/`py`，无返回值。
- 调用者：本文件 `abstractNetwork`。
