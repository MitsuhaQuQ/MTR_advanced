# FrontTurnBacks.java

站前折返：线路在列出其他站台可供折返的车站结束、下一条线路从同一车站开始时，列车可以在哪些站台结束、按什么顺序（下一条线路偏好的始发站台优先，种别优先），以及从倒数第二站经每个站台到下一线路第二站的走法合成一个 `TurnBackSection`。2026-11-13 从游戏的车厂路径构建（`mtr.data.DutyPathBuilder.addFrontTurnBackTimed`）原样搬进组件（拆分车厂第 2c 步，0.76.1），规则和结果不变：宿主找每条路，哪些站台算、什么顺序、何时成为区段在这里。

调用者：游戏 `DutyPathBuilder.addFrontTurnBackTimed`。

## 接口 `Ways<T extends PlanTrack>`
- `find(platformId)`：从倒数第二站进入该站台、折返出来到下一线路第二站的走法；没有（或不能代替通常走法）为 null。
- `unusable(platformId)`：默认什么都不做，宿主用来记失败。

## `static List<Long> order(long lastPlatformId, long nextFirstPlatformId, List<Long> terminalPlatforms, List<Long> startPlatforms, LongPredicate atStation)`
- 候选：下一条线路就从末站台开始时，末站台本身算一个；加上线路列出的可折返站台里不是末站台、未重复、`atStation` 为真（同一车站且还存在）的。
- 顺序：下一条线路偏好的始发站台（`startPlatforms`）里属于候选的先排（按偏好顺序，不重复），其余按候选顺序接在后面。

## `static <T> TurnBackSection<T> section(int stopRail, long lastPlatformId, long nextFirstPlatformId, List<Long> ordered, List<T> usualWay, Ways<T> ways)`
- 按 `ordered` 逐个取走法：是末站台时放通常走法并记为默认；否则问宿主，没有的留 null 并通知 `unusable`。
- 下一条线路不从末站台开始（站后折返）时，通常走法排在最后并为默认——先试各站台，都不行才走站后。
- 至少两条可用才算一个区段（只有一条就没得选），否则返回 null。`stopRail` 为走法在主体上开始处。

## 测试
`FrontTurnBacksTest`：偏好站台排最前、末站台只在下一线路从那里开始时算、别的车站或已删除的排除且不重复、站后折返时通常走法排最后、站前折返时通常走法保持位置、没走法的留 null 并通知、只有一条不成区段。
