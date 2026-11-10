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

### 网页媒体库工作区（本会话，工作区，2026-11-09）

- **改了什么**：高级编辑器分成"运行编辑"和"媒体库"两个工作区，在 `index.html` 顶栏切换、不重新加载（`shell.js`，地址 `#/timetable`、`#/media/audio`）；报站音频从单独的 `audio.html` 搬进媒体库（`media.js` 由 `audio.js` 改写，按类别表组织，以后的图片加一类即可）：搜索、上传者筛选、只看本机没有、右侧详情、拖放上传。`audio.html` 改成跳转页。运行编辑在媒体库显示时不画、少问游戏。新增颜色变量 `--control-line`、`--caution`（设计系统里的值）。
- **文件**：`common/src/main/resources/assets/mtr/editor/` 下 `index.html`、`shell.js`（新）、`media.js`（新）、`audio.html`、`audio.js`（删除）、`editor.js`（密钥改取 `MTREditor.key`，几处 `inTimetable()` 判断，草稿圆点）、`editor.css`、`i18n.js`；文档 `MTR-代码说明与流程.md`（第 51 行、8.6 节、11.1 节）、`MTR-模拟组件与解耦.md`、`MTR-测试项目清单.md`（U366–U369）、`README.md`。
- **协议和存档**：都没有变化，不用加 `mtm_version`。Java 没改：`EditorServer` 整个目录当静态文件提供，新文件自动能访问。
- **和别的改动的关系**：断点标签 `pre-media-workspace`，基线 jar 在 `P:/mtr-ante/test-jars/baseline-media-workspace`。和工作区里别的会话的站牌改动（`BlockRailwaySign` 等 Java 文件、`SignTexts`）不碰同一个文件；`editor.js` 若别的会话也在改，按块合并即可，这次只改了 key 读取、`renderDraftState`、`poll`、`showsDiagram`、`drawNetwork`、两个定时器、`btn-tree`、Esc 和 resize 的监听。
- **已经验证的**：`node --check` 全部通过；英文词典覆盖所有新文字；用本地模拟服务（假的 `api/audio`，别的接口 503）在内置浏览器里看过：旧地址跳转并带过密钥、媒体库列表 / 选中 / 详情 / 搜索、切到运行编辑再后退回来状态保留、手机宽度和深色模式。没有构建模组，没进游戏。
- **构建后要检查的**：编译和 jar 里 `assets/mtr/editor/` 有 `shell.js`、`media.js`、没有 `audio.js`；common 测试照常。
- **游戏内测试**：U366–U369（已写进测试清单）。
- **合并构建**（2026-11-09 23:58，`mtm_version` 0.64.1，和逻辑线路 `d4d94ea`、站牌三语一起）：编译通过，transit-sim 541、common 389 全过，附加 mod 兼容扫描 0 缺失，Fabric、Forge 服务端都能启动；jar 里 `assets/mtr/editor/` 有 `shell.js`、`media.js`，没有 `audio.js`。还没进游戏（U366–U369）。
- **已知风险**：别的会话若在 `editor.js` 新加读取 `location.hash` 的代码，会和路由冲突；浏览器缓存了旧 `media.js` 时要强制刷新一次（服务端是 no-cache，正常不会）。

- **构建留存（2026-11-10，`898e3cb`）**：从本工作树构建 1.20.1 一轮通过：transit-sim 541 项、common 389 项测试全过，四个附属 mod 的接口扫描 0 缺失，Fabric、Forge 测试服务器都正常启动（无 mixin 失败、无崩溃）。jar 在 `P:/mtr-ante/test-jars/merged`，另存一份在 `P:/mtr-ante/test-jars/web-editor-next-898e3cb`。断点标签 `pre-legacy-retire`（清退旧逻辑前）。之后按九步清退新页面对旧 `editor.js` 的依赖，第①步停站与股道求解已换成 `core/topology.ts`、第②步格式与时间工具已换成 `core/format.ts`、第③步车站行和班次构建已换成 `core/services.ts` 且旧引擎也改用它、第④步时刻编辑规则和草稿时刻请求已换成 `core/timetable.ts` / `core/draftTimes.ts`、第⑤步运用规则已换成 `core/duties.ts`、第⑥步网络设置已换成 `core/settings.ts`（都对拍一致；逻辑线路、区段归属、站台通过速度和停车位置、车厂配属和车型、寻路边界放进草稿并应用照旧即可；运用面板的问题、折返衔接、有车没车统计、按运行日组拆分编辑、改停站后运用跟着改号照旧即可；改时刻、拖动、平移、等间隔、接续、清空、添加班次进草稿后照旧算出时刻即可；运行图的车站行、班次和汇总表照旧即可；游戏里看停站、新建种别、各处时刻显示照旧即可）。游戏内 U370–U404 还没测。

### 新版网页编辑器，迁移第 0、1 步（本会话，分支 claude/modern-web-editor-design-8ec467，2026-11-10）

- **第 2–6 步第一轮**（断点标签 `pre-web-next-2`）：各层接进新外壳，迁移期间新页面运行构建时从 `editor.js` 生成的旧代码（`editor-web/src/legacy/engine.generated.js`、`markup.generated.ts`、`public/legacy.css`），旧视图和对话框原样托管；在假服务上看过时刻、车厂层的选择、表格输入、右键菜单、旧对话框改草稿和一步撤销，配线、归属、调度层要 `api/network`，只能进游戏看。**别的会话改了 `editor.js`、`editor.css` 或 `index.html` 后，统一构建前要先跑 `bash editor-web/build.sh`**，否则新版运行的是旧的一份。游戏内测试 U376–U378。之后各块逐个换成新设计（导航 React 重画，汇总表、检查栏、全网配线、调度台、对话框换样式，U380–U382）；运行图配色改在 `editor.js`/`editor.css`，旧版也一起变（U383）。之后检查栏改成新组件、线路卡片的配线图链接移到导航右键菜单（U384），又把旧版画法里写死的颜色和字体收成变量、虚线收成四种、拖柄放大（U385），这些也改在 `editor.js`/`editor.css`/`index.html`，旧版一起变。配线图在新版里改成平行股道的示意图（只改网页，U386），车厂层默认改成运用表（U387），停站编辑、新建线路和新建种别改成新版右侧面板（`StopsEditor.tsx`，旧的停站对话框只在"站内配线…"里还用，U388、U391），线路限速、直通关系、故障预案和时刻表的星期也换成右侧面板（`LinePanels.tsx`，U392、U393），编辑车厂换成右侧面板（`DepotPanel.tsx`，顺带修了旧对话框保存时丢掉草稿里逻辑线路等全网设置的问题，U394），出入库规则换成右侧面板，在图上选择（出入库路径、在全网配线图上选站）改成浮动的全网图小窗口——以前在新页面的车厂层和线路层里没有全网图，根本选不了（U395），车型设置、运用组、出入库计划也换成右侧面板，车厂层的旧对话框全部换完（`DepotExtras.tsx`，U397），剩下的旧对话框统一改成右侧面板样式、调度层改成示意图标列车加右栏调度台（U398），运行图用新代码重写（`RunningDiagram.tsx`，几何 `core/diagram.ts` 和旧版对拍一致，"旧版画法"开关可切回，U399），汇总时刻表也用新代码重写（`SummaryTable.tsx`，和旧表逐格对拍一致，U400），检查栏的运用详情、模拟问题、环线改成新组件（U401），调度台改成每列车一张卡片（`DispatchConsole.tsx`，U402），在图上选择的两个面板改成新组件、放在浮动小窗地图旁（`PickPanels.tsx`，U403），媒体库用新代码重写、不再托管旧 `media.js`（U404）；全网实际图保留旧画法，以后整体重新构想（见规格第 11 节），归属层加了画笔面板和逻辑线路勾选表（`OwnershipPanel.tsx`，U389），配线层默认改成全网示意配线图（之后按用户草图改成股道连续穿过车站的画法，方框画法挪到归属层当逻辑图，U396；`abstractNet.ts`，车站区域、区间股数和 45° 排版都是网页按 `api/network` 推出来的，假服务的小线网上看过，**要在 yel 等大存档里看车站有没有并错、股数对不对**，U390），要在真实存档里看分股是否合理，再决定要不要让服务端直接给股和道岔。媒体层同样运行旧的 `media.js`（U379）。顺带修了新版一直没连上 `i18n.js` 词典的问题（英文客户端原来会显示中文）。冒烟测试 `editor-web/test/legacy.test.ts` 在构建里跑。
- **第 1 步**（提交在第 0 步之后，断点标签 `pre-web-next-1`）：新版能审阅和应用草稿、处理冲突、看同步状态，逻辑和旧版对拍（`editor-web/test/`，14 项，`build.sh` 里跑）；没改 Java、包和存档。游戏内测试 U373–U375。


- **改了什么**：新版编辑器和旧版并存，放在 `assets/mtr/editor/next/`；源码在仓库根目录 `editor-web/`（React + TypeScript + Vite + Tailwind），构建结果一起提交，模组构建不需要 Node。这一步只有外框（顶栏、层级栏、状态栏、连接提示）、和旧版共用的密钥与草稿、各层只读概览（线路组的种别、车厂），编辑仍在旧版。旧版顶栏加"试用新版"。迁移步骤写在 `MTR-网页编辑器规格.md` 第 11 节。
- **文件**：新增 `editor-web/`（源码、`build.sh`、`mock/`）和 `common/src/main/resources/assets/mtr/editor/next/`（构建结果）；改 `assets/mtr/editor/index.html`（入口链接）、`editor.css`（`.try-next`）、`i18n.js`（新版的英文，45 条）；文档 `MTR-代码说明与流程.md`（第 51 行、8.7 节）、`MTR-模拟组件与解耦.md`、`MTR-测试项目清单.md`（U370–U372）、`MTR-网页编辑器规格.md`（第 9–11 节）、`README.md`。
- **协议和存档**：都没有变化，Java 没改（`EditorServer` 整个目录当静态文件，`next/` 自动能访问）。规格第 1 节写"网页改动也升 `mtm_version`"，但上一次媒体库工作区没升；统一构建时按那边的决定办。
- **和别的改动的关系**：断点标签 `pre-web-next-0`（`2075096`），基线 jar 在 `P:/mtr-ante/test-jars/baseline-web-next`。只在 `index.html`、`editor.css`、`i18n.js` 末尾各加了几行，和别的会话改 `editor.js` 不冲突；`i18n.js` 若同时有人在 en_us 末尾加词条，按块合并。`next/assets/` 里的文件名带哈希，每次 `build.sh` 都会换名，合并时以最后一次构建为准（重跑 `bash editor-web/build.sh`）。
- **已经验证的**：`npm run check`（TypeScript）通过；`build.sh` 构建通过（js 234 KB、css 10 KB，没有内联脚本）；`node --check i18n.js` 通过，新版用到的文字在 `i18n.js` 里都有英文。用 `editor-web/mock/serve-built.mjs`（和 `EditorServer` 一样的 CSP、要密钥）在内置浏览器里看过：从旧版点"试用新版"进新版、密钥带过去、读出数据、线路和车厂两层、层切换、草稿项数从共用草稿读到（写入 2 项后 5 秒内显示）、深色模式。没有构建模组，没进游戏。
- **构建后要检查的**：编译通过；jar 里 `assets/mtr/editor/next/index.html` 和 `next/assets/*.js`、`*.css` 都在；common 测试照常。
- **游戏内测试**：U370–U372（已写进测试清单）。
- **已知风险**：浏览器缓存了旧的 `next/index.html` 时，引用的带哈希文件可能已不在（服务端是 no-cache，正常不会）；`i18n.js` 先于 React 加载，若它加载失败新版只显示中文。

## 环线线路图画成闭合的环（2026-11-09）

- 改动：`RouteMapGenerator.generateLoopMap`。站台只停一条环线、线路牌不是竖排时画成闭合的环；`MapRoute.loopStationIds`；站名和换乘的画法提出到 `drawStationWithText`、`getInterchanges`（直线画法不变）。
- 已验证：只带这次改动在 HEAD 上构建通过，transit-sim 528 项、common 368 项测试全过。
- 未验证：没进游戏看过，见测试清单 U332。要和其他改动一起做合并构建、复制 jar。
