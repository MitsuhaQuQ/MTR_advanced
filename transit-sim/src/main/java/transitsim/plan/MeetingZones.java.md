# MeetingZones.java

找出对向列车交会、而交会处两股道都在同一个联锁区里的地方（2026-11-14，用户反馈：单线加一段会让线，道岔区域整片刷了联锁色，开跑 2 分钟就死锁）。联锁区按进路整块锁住，一列车停在会让线一股上、车尾还在联锁区里时，另一股也被它占着，对向车永远过不去。按用户的选择只提示，不改规则：联锁色应只刷道岔本身，不刷列车等着交会的股道和站台。

交会处的判定：一条两个方向都有车走的轨道（单线，或在其上掉头的站台），一个方向的车从轨道 A 驶上它，另一个方向的车从它驶上轨道 B，A、B 不同且各自只被一个方向走过，A 和 B 在同一个联锁区。纯组件，只看路径和轨道键，两个宿主都可用。调用者：游戏 `Depot.findMeetingZones`、工具 `SimulateDay --meetings`；测试 `MeetingZonesTest`。

## 类 MeetingZones（final，工具类）

### `static <T extends PlanTrack> List<Finding<T>> find(List<? extends List<T>> paths, Function<UUID, Long> zoneOf)`
- 作用：先走一遍所有路径，记下每条轨道第一次被走的方向、是否也被反向走过（`isOppositeOf`），以及每一次从一条轨道换到另一条的移动；再对每对移动（A→C，C'→B，C' 是反向走的 C）检查上面的条件，每个（联锁区、共用轨道）只报一次，按发现顺序。
- 参数：`paths` 列车走的路径；`zoneOf` 轨道键 → 联锁区编号，不在联锁区为 null。

## 内部类 Finding<T>（public static final）
- `zone`：联锁区编号；`from`：一个方向的车驶离的股道（其终点就是分岔处）；`shared`：两个方向共用的轨道；`to`：另一个方向的车驶上的股道。
