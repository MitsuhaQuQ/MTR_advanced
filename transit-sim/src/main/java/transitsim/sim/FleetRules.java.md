# FleetRules.java

车厂停车线池及其列车的规则（P2-C、R3），两个宿主共用这一份（S7）：游戏的 `Siding` 与 `Depot`，以及组件的 `TickRunner`。每个方法接受宿主已知的情况，给出该怎么做，使两边不会出现分歧。

调用者：游戏侧 `Siding.java:1796`（`whatToMake`）、`Siding.java:1765`（`keepsSecondStanding`）、`Depot.java:1791`/`:1804`（`hasRoom`）、`Depot.java:2423`（`sumFleets`）、`Depot.java:1706`（`linePool`）；组件 `TickRunner.java:1598`、`:346`、`:1571`、`:280`；plan 包 `LineDuties.java:50`（`sumFleets`）。

## 类 FleetRules（final，不可实例化）

### 枚举 Make
停车线在一个 tick 中对"停在其中的列车"要做的事。
- `NONE`：不做（已有车停着，或池中列车已够）。
- `FROM_SHED`：取出车库中存放最久的那列停在这里。
- `NEW`：在此新建一列池中的车。

### `static Make whatToMake(boolean free, boolean noTrains, boolean shedEmpty, int poolFleet, int poolTrains, int poolSlots, int held, int capacity, int trains)`
- 作用：按运用运行时，没有车停着且没有车刚出发的停车线先从车库取最久的；车库为空时，若池设定了总车数（`poolFleet ≥ 0`），只有池现有车数少于总数且停车线有空位才新建；若"按需"（−1），停车线一列车都没有时新建，或池现有车数少于其运用数且停车线未满时新建（每条停车线至少有一列）。
- 参数：`free` 停车线内没有车停着或刚离开不远；`noTrains` 停车线一列车都没有（javadoc 未列出，含义据代码推断）；`shedEmpty` 车库为空；`poolFleet` 池总车数或 −1（按运用所需）；`poolTrains` 池现有车数（在线、停放、存放）；`poolSlots` 池的运用数；`held` 停在停车线和存放在车库中的车数；`capacity` 停车线容量或 −1（不限）；`trains` 停车线的车数（在线与停放）。
- 返回：`Make`。副作用：无。
- 调用者：`Siding.java:1796`、`TickRunner.java:1598`、`FleetRulesTest.java:16`。

### `static boolean hasRoom(int held, int coming, int capacity)`
- 作用：停车线能否再容纳一列：容量 < 0（不限）或 停放/存放 + 正在回来的 < 容量。
- 调用者：`Depot.java:1791`、`TickRunner.java:346`；`whatToMake`。

### `static int sumFleets(Iterable<Integer> fleets)`
池的总车数 = 各车厂给出的数之和；任一为 −1（按需）则返回 −1。调用者：`Depot.java:2423`、`LineDuties.java:50`。

### `static boolean keepsSecondStanding(boolean byDuties)`
按运用运行时停车线中的第二列停放车存入车库而不是被移走；否则（游戏一直以来的做法）移走。返回值即 `byDuties`。调用者：`Siding.java:1765`、`TickRunner.java:1571`。

### `static <S> List<S> linePool(S own, Iterable<S> candidates, ToLongFunction<S> depotOf, Function<S, Object> poolKeyOf, Function<S, String> linePoolOf)`
- 作用：停车线在其运营线路范围内的池：先是本车厂中池键相同的停车线（含自身），然后是同运营线路其他车厂中线路池名相同（非空）的停车线；各一次，按给定顺序；自身不在时放在最前。
- 参数：`own` 停车线；`candidates` 可能在池中的停车线（固定顺序，如按 id）；`depotOf` 所属车厂；`poolKeyOf` 同车厂内可互相执行运用的停车线的共同键（不为 null）；`linePoolOf` 跨车厂的线路池名，或空。
- 返回：池列表。调用者：`Depot.java:1706`、`TickRunner.java:280`。
