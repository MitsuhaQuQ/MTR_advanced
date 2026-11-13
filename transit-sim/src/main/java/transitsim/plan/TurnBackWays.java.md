# TurnBackWays.java

两班次之间列车可走哪些折返走法、路径走哪一种（生成 `TurnBackSection`）：线路在末站后列出的折返轨的各条走法；没有列出时，直接搜索的路与绕经站台附近折返轨的走法中较短的（站后折返）。2026-11-13 从游戏的车厂路径构建（`mtr.data.DutyPathBuilder.findTurnBackSection`、`findConnectionTimed`）原样搬进组件（拆分车厂第 2c 步，0.76.1），规则和结果不变：宿主找每条路，保留哪些、哪条是默认在这里。

调用者：游戏 `DutyPathBuilder.findTurnBackSection`、`findConnectionTimed`（都经 `waysRound` 提供找路）。

## 接口 `Ways<N, T extends PlanTrack>`
- `find(node)`：经该节点从停站到下一班次起点的走法，没有为空。
- `sameRail(track, other)`：两段是否同一轨道、同方向。
- `unusable(node)`：默认什么都不做，宿主用来记失败。

## `static <N, T> TurnBackSection<T> section(List<N> nodes, int start, Ways<N, T> ways)`
- 规则：一条走法可用，须有至少两段轨道，且与第一条可用走法从同一轨道离开、到同一轨道到达（列车从同一处出发、回到同一处）。不可用的在 `ways` 里留 null 并通知 `unusable`。第一条可用的是默认走法。
- 返回：`TurnBackSection`（`start` 为走法在主路径上开始处），一条都不可用为 null。

## `static <N, T> TurnBackSection<T> connection(List<T> into, List<N> listedNodes, Supplier<List<T>> direct, Supplier<List<N>> nearbyNodes, Ways<N, T> ways)`
- 规则：线路列出了折返节点就只用它们（不论长短），`into` 填默认走法；列出了但都不可用时返回 null 且 `into` 为空。
- 没有列出时：`into` 填直接搜索的路（`direct`，只在这时才问），再问站台附近的折返节点（`nearbyNodes`，同样只在这时问）；绕经它们的走法存在、且（没有直接的路 或 比直接的路短）时，`into` 换成它并返回该区段；否则返回 null，`into` 保留直接搜索的路（一样长时也保留）。
- 返回：有折返走法时的区段，否则 null。

## `static <T> double length(List<T> way)`
走法有多少格（各段 `getTrackLength` 之和）。

## 测试
`TurnBackWaysTest`：第一条可用的为默认并决定其他走法的进出轨道、一条都不可用为 null、列出的不论长短都用、列出的没走法则无连接、没列出时比长度（短的赢、一样长用直接的）、没有直接的路时用绕行、两者都没有时为空、走法长度。
