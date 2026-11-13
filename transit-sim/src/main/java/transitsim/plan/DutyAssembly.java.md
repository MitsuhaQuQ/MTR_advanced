# DutyAssembly.java（0.78.1 新增，拆分车厂第 2d 步）

把一份运用的各段拼成整条运用路径，从游戏的 `DutyPathBuilder.getDutyPlan` 原样搬来。宿主（游戏 `DutyPathBuilder`，或以后的组件宿主）只提供找到的东西，拼法在这里。

## 类 `Part<T, P>`
宿主做好的一段：线路、首末站下标和线路站数（`runsToEnd()`：是否跑到线路末站）、该段路径模板（不会被改）、首末站台及其 id、首站台停站时间。

## 接口 `Tracks<T>`
宿主轨道的比较与变换：`sameRail`、`oppositeRail`、`withStop`（带新的停站 id 和停站时间复制一份，原轨道不变）。

## 接口 `Host<T, P>`
- `part(k)`：第 k 段，找不到返回 null（整份运用失败）。
- `connect(connectingPath, before, after, routeBeforeId)`：两段之间的连接段填进 `connectingPath`（找不到留空，整份失败）；`routeBeforeId` 在上一段跑到末站时是它的线路，否则 0；返回可选的折返走法。
- `frontTurnBack(body, before, after, previousStart, nextStart, sections)`：上一段跑到末站、这一段从线路首站开始时调用（站前折返）。
- `entryLead(part)`：中途加入线路时从首站到加入站的引入段，没有返回 null。

## `static DutyPlan<T, P> assemble(key, segmentCount, startsOnLine, staysOnLine, host, tracks)`
逐段：
1. 上一段末站台和这段首站台不同时找连接段；折返走法多于一条且连接段从 body 末轨开始时记为折返区段（起点 body 末下标）；连接段前后各记一个下标边界；连接段接上时去掉重复的首轨。
2. 这段首轨与 body 末轨是同一条轨：段起点为末轨下标；从别的站台来到这段首站、首轨没有停站时间时补上停站（取末轨与首站台停站时间的较大者）。是同一条轨反向：站台折返，这段首轨去掉停站，只作出站。否则接在后面。
3. 站前折返交给宿主。
4. 记线路、截断站（每条线路第一次）、每段的截断和加入、引入段（每条线路一次，宿主找不到就不记加入站）。
最后生成 `DutyPlan`：下标边界含 0、各段起点、body 长度，`segmentsKnown = true`。

测试：`DutyAssemblyTest`（6 项）。验收：yel 开服导出与 0.78.0 逐字节相同。
