# core/trackSchematic.ts

全网配线的"真实轨道"画法：保留每段轨道和共享节点（包括剪刀渡线的两条斜线），在整个路网上用同一个按距离压缩的投影（相近轨道一起移动），并在投影的拐点处细分轨道，保证分段投影不会增加或消除交叉。车站标记只装饰真实的站台轨道，不取代车站道岔。结果 `TrackMap` 由 `views/NetworkSchematic.tsx` 直接画，或先交给 `core/orthogonalTracks.ts` 变成横竖 45° 示意图；`views/TrackMapDrawing.tsx` 负责绘制。

## 类型
- `Point`：`[x, y]`（绘制单位）。
- `DrawRail`：一段要画的轨道：`rail`（`net.rails` 中的序号）、`nodes`（两端节点）、`points`（折线）、`kind`（轨道种类，1 为站台；绘图中 2、3 另有标注，含义未确定）、`colors`（经过的路线颜色）、`station?`（`s:<车站ID>`）、`name`（站台名）。
- `TrackMap`：`rails`、`stations`（`{id, name, x, y（站名位置）, vertical}`）、`ends`（尽头点）、`junctions`（分岔点）、`width`、`height`。

## 函数
- `compressedAxis(values)`（导出）：对一个坐标轴上所有取值构建单调递增的压缩投影：相邻取值差 ≤8 放大 4 倍，≤24 为 `32 + (差-8)`，更大为 `48 + 12√(差-24)`；只保留斜率改变处的拐点。返回 `{ knots（拐点原坐标）, project(value) }`（超出最后拐点按 2 倍延伸）。调用者：本文件 `trackSchematic`。
- `trackSchematic(net, platforms = [])`（导出）：
  - 对用到的节点分别在 x、z 轴上建压缩投影；每段轨道的采样点（`r[4]` 起成对的中间点）投影，并在跨越拐点处插入细分点。
  - 按路线经过的节点对给轨道加颜色；站台轨道匹配中点 4 以内的拓扑站台得到站台名，并归到 200 以内的车站（优先与站台所属车站一致，其次最近）。
  - 每个车站按其站台轨道的包围盒决定站名位置（竖向时放右侧，横向时放下方）。
  - 统计节点度数得出尽头（度 1）和分岔（度 >2）。
  - 用并查集找连通分量；与最大分量相距超过 200 的远处分量（如测试轨道）依次排到主网下方，避免空白地理间隔压小所有站名。
  - 最后整体平移到左上留 60，宽高各加 220/140。
  - 返回 `TrackMap`。调用者：`views/NetworkSchematic.tsx:39`。
- `railMidpoint(points)`（导出）：折线按长度一半处的点。调用者：`views/TrackMapDrawing.tsx:13`、`views/TrackMapDrawing.tsx:14`、`views/TrackMapDrawing.tsx:26`。
