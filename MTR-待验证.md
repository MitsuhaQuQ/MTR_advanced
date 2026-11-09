# 待验证的改动

并行的会话改完代码后不自己构建，把要验证的内容记在这里；做完以后由一个会话统一合并、构建、测试，再清掉对应的条目（解耦文档 5.1 节）。这样各会话不会抢同一个构建目录和测试 jar。

## 规则

- **不构建模组**：不跑 `par-build.sh`，不启动测试服务端，不往 `P:/mtr-ante/test-jars/` 放 jar。只读代码、写代码、写测试。
- **组件的单元测试可以单独跑**：`transit-sim` 不依赖 Minecraft，可以复制到自己的临时目录离线测（`./gradlew -p transit-sim test --offline`），不碰共用的构建目录。
- **每项改动一节**：按下面的格式写在"待验证"下面，写清楚改了哪些文件、要怎么验证。
- **协议版本不在分支里加**：改了网络包或存档，在条目里写明"需要加协议版本"，统一构建时一起加，免得各分支撞号。
- **统一构建时**：逐条合并，构建 1.20.1，跑组件和 common 测试、ABI 扫描、Fabric 和 Forge 服务端启动，复制测试 jar；通过的条目删掉，相关的游戏内测试项转进 `MTR-测试项目清单.md`；不通过的写明原因留在这里，交回原会话。

## 格式

```markdown
### <改动名>（<会话名>，<分支或"工作区">，<日期>）

- **改了什么**：一两句。
- **文件**：改动的文件或目录。
- **协议和存档**：没有变化 / 改了网络包（需要加协议版本）/ 新增了存档内容 / ……
- **和别的改动的关系**：会不会和别的条目改到同一个文件，要按什么顺序合并。
- **已经验证的**：例如组件单元测试 N 项通过；没跑的写"没有"。
- **构建后要检查的**：编译、某些单元测试、ABI 扫描要特别看的类、服务端启动时要看的日志。
- **游戏内测试**：要加进测试清单的条目（编号留空，统一时再编）。
- **已知风险**：可能出问题的地方。
```

## 待验证

2026-11-03 统一构建了 ANTE 脚本沙箱、线路图两项和圆形隧道创建器（协议版本加到 30），编译、common 测试、ABI 扫描、两个服务端启动都通过；游戏内测试转为测试清单 U56 至 U60、C17。

2026-11-03 统一构建了界面多语言（协议版本加到 32），编译、common 测试、合并后的语言文件、ABI 扫描、两个服务端启动都通过；游戏内测试转为测试清单 U64 至 U66。

2026-11-04 统一构建了自定义转向架（`3e599e3`）和编辑器的线路形状（`14ff674`），编译、common 测试、ABI 扫描（`TrainProperties`、`TrainClientRegistry` 无缺失）、两个服务端启动、mixin 无警告都通过；游戏内测试转为测试清单 U87、U88，已知风险一并写在那里。

### 核心类的类说明（本会话，工作区，2026-11-04）

- **改了什么**：给 34 个核心类加了类级 Javadoc（只加注释，不改代码），并新增代码说明文档 `MTR-代码说明与流程.md`（第 4 至 10 节按现在的代码写了具体实现，附 14 张 Mermaid UML 图：类图、时序图、活动图、状态图，都已用 Mermaid 10.9.1 解析和渲染通过）。文档已跟到提交 `4f880df`：按时刻表就位、共用线路的车厂共享班次、改动在处理器之前读出；`Depot` 的类说明加了共享班次。
- **文件**：`mtr/` 下 `MTR`、`MTRClient`、`data/`（`RailwayData`、`Siding`、`Depot`、`Train`、`TrainServer`、`TrainClient`、`Route`、`Rail`、`Station`、`Platform`、`SavedRailBase`、`DataCache`、`UpdateNearbyMovingObjects`、`RailwayDataFileSaveModule`）、`path/`（`PathData`、`PathFinder`）、`packet/`（`PacketGuard`、`PacketTrainDataGuiServer`、`PacketTrainDataGuiClient`）、`client/`（`ClientData`、`CustomResources`、`TrainClientRegistry`、`TrainProperties`）、`render/`（`RenderTrains`、`TrainRendererBase`、`JonModelTrainRenderer`）、`servlet/Webserver`；ANTE 的 `Main`、`MainClient`、`CustomResources`、`ScriptHolderBase`、`ScriptedTrainRenderer`。
- **协议和存档**：没有变化。
- **和别的改动的关系**：只在类声明前插入注释，和别的条目改同一文件时按文本合并即可。
- **已经验证的**：注释里 `{@link}` 指向的成员都确认存在；没有编译。
- **构建后要检查的**：编译通过即可（Manifold 预处理只认行首的 `#`，注释里的 `#init` 等不受影响）。
- **游戏内测试**：无。
- **已知风险**：无。

### 换车型后客户端整列替换（本会话，工作区，2026-11-09）

- **改了什么**：存车线换车型后列车 ID 可以不变，客户端原来只 `copyFromTrain` 抄路径和进度，节数、间距、模型还是旧车的，旧车的后几节超出存车线起点、叠在一点横在轨道上，重启服务器才好。现在 `Train.getSyncKey` 加进车型（`trainId`、`baseTrainType`）、节数、间距，换车型后服务端一定发整包；`ClientData.updateTrains` 遇到车型不同（新的 `Train.isSameVehicle`）就把旧 `TrainClient` 标 `isRemoved` 换成新的。
- **文件**：`common/src/main/java/mtr/data/Train.java`、`common/src/main/java/mtr/client/ClientData.java`；文档 `MTR-代码说明与流程.md`（第 507、511 行附近）、`MTR-模拟组件与解耦.md`（G2c 行）、`MTR-测试项目清单.md`（U318）、`README.md`（Rendering）。
- **协议和存档**：都没有变化（同步键只在服务端比较，包里照样是一个 int），不用升 `mtm_version`。
- **和别的改动的关系**：不碰工作区里另一会话正在改的 `Depot`、`LineDuties`、编辑器文件；断点标签 `pre-client-vehicle-sync`，旧 jar 在 `P:/mtr-ante/test-jars/baseline-client-vehicle-sync`。
- **已经验证的**：只读过代码，没有编译。ANTE 的 `TrainMixin` 在 `getSyncKey` 返回值上再乘 `customConfigs`，不受影响。
- **构建后要检查的**：编译通过；common 测试照常。
- **游戏内测试**：U318。
- **已知风险**：换车型时正坐在这列车上的玩家，客户端换成新对象后乘车状态要等服务端下一次乘车包才恢复，可能闪一下。

### 屏蔽门旁的方块敲了会回来（本会话，工作区，2026-11-09）

- **改了什么**：`BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase.writeCompoundTag` 以前在 `temp` 为真（刚放下的门）时直接 `setBlockAndUpdate` 改 `TEMP`。写数据发生在 `ChunkHolder.broadcastChanges` 发方块实体更新包的途中，这时改方块会让 `changedBlocksPerSection[i]` 留着、`hasChangedSections` 却在发完后被置回 false，之后这一截区块（16 格高）的所有方块变化都不再发给玩家，客户端敲掉的方块弹回来，直到重启。在 Q:\MTM_1.20.1 上用调试版确认过：`blockChanged` 有调用，之后每一刻 `broadcastChanges` 都是 `has changed sections false`，没有任何方块更新包。现在改成下一刻用 `TickTask` 改。
- **文件**：`common/src/main/java/mtr/block/BlockPSDAPGDoorBase.java`；文档 `MTR-代码说明与流程.md`（第 351 行）、`MTR-模拟组件与解耦.md`（第 4 节约束）、`MTR-测试项目清单.md`（U322）、`README.md`（Platforms）。
- **协议和存档**：都没有变化。
- **和别的改动的关系**：断点标签 `pre-psd-update-fix`。只碰这一个文件。
- **已经验证的**：同样的改动放在 0.52.0（`32de80f`）上、带调试日志的私有构建能编译（`C:/gtmp/dbg`，jar 在 `P:/mtr-ante/test-jars/debug-break/…-FIX-DEBUG.jar`），2026-11-09 01:21 用户在 Q:\MTM_1.20.1 上用它确认修好：敲掉的方块不再回来，日志里 `broadcastChanges` 为 `has changed sections true`，服务端发出 9 个方块更新包。U322 里另一名玩家、列车开关门后、重启后几项还没测。
- **构建后要检查的**：编译通过；common 测试照常。
- **游戏内测试**：U322。
- **已知风险**：新放的门要到下一刻才把 `TEMP` 改成 false，客户端重画晚一刻，看不出来。

## 环线线路图画成闭合的环（2026-11-09）

- 改动：`RouteMapGenerator.generateLoopMap`。站台只停一条环线、线路牌不是竖排时画成闭合的环；`MapRoute.loopStationIds`；站名和换乘的画法提出到 `drawStationWithText`、`getInterchanges`（直线画法不变）。
- 已验证：只带这次改动在 HEAD 上构建通过，transit-sim 528 项、common 368 项测试全过。
- 未验证：没进游戏看过，见测试清单 U332。要和其他改动一起做合并构建、复制 jar。
