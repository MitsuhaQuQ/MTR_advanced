# LogicalLines.java

叠加在轨道区段上的逻辑线路（逻辑线路，网页分层编辑，2026-11-09）：轨道本身的"线"，区别于在其上运营的运营线路。玩家通过区段的某条轨道把每个区段放入至多一条逻辑线路；运营线路可在为其选定的逻辑线路上运行，未选定时可在其站台所在的逻辑线路上运行。联络逻辑线路可被所有运营线路使用，不属于任何逻辑线路的区段也一样。

结果形式与 `TrackOwnership` 相同（每个区段可运行的运营线路集合），寻路以同样方式使用（`TrackOwnership.mayRun`）。调用者：`RoutePaths.java:313`、`RoutePaths.java:316`、`LogicalLinesTest.java:42`。

## 类 LogicalLines（final，不可实例化）

### `static Result compute(TrackSections.Snapshot graph, Map<UUID, Long> railLines, Set<Long> connecting, Map<String, ? extends Collection<Long>> chosen, Map<Long, ? extends Collection<String>> platformLines)`
- 作用：
  1. 按轨道键升序遍历 `railLines`，找出每条轨道所在区段（`graph.getSectionOf`），区段的逻辑线路取键最小的那条轨道的（`putIfAbsent`）。
  2. 对每个有"保存轨道 id"的区段，查该轨道上有站台的运营线路，把区段的逻辑线路加入这些运营线路的"站台逻辑线路"集合（无逻辑线路的站台也会让该运营线路出现，集合可能为空）。
  3. `allowed`：先取站台逻辑线路；在 `chosen` 中有非空选择的运营线路以选择覆盖。
  4. `owners`：对每个属于非联络逻辑线路的区段，列出 `allowed` 包含该逻辑线路的所有运营线路（可能为空集合 = 无线路可用）。联络线路和无逻辑线路的区段不出现在 `owners` 中（任何线路可用）。
- 参数：`graph` 区段快照；`railLines` 轨道键 → 逻辑线路 id；`connecting` 联络逻辑线路 id；`chosen` 运营线路 → 选定的逻辑线路；`platformLines` 保存轨道 id → 在该轨道有站台的运营线路。
- 返回：`Result`（各集合不可修改）。副作用：无。

## 内部类 Result（public static final）
- `owners`（`Map<Long, Set<String>>`）：非联络逻辑线路区段（按区段哈希）→ 可运行的运营线路；空集合表示该区段没有运营线路可用。
- `sectionLines`（`Map<Long, Long>`）：区段哈希 → 所属逻辑线路。
- `allowed`（`Map<String, Set<Long>>`）：运营线路 → 可运行的逻辑线路。
- 私有构造函数按字段赋值，由 `compute` 创建（第 93 行）。
