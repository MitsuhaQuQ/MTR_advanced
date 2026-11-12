# ClientCache.java（上游文件，本分支有改动）

客户端：客户端的数据索引（继承服务器端也用的 `DataCache`），外加客户端专用的查询缓存（车站→站台、车厂→停车轨、站台→经过线路、位置→站台/停车轨）和"动态纹理"（用 Java AWT 把站名、路线图、方向箭头等画成图片再注册为游戏纹理）。唯一实例 `ClientData.DATA_CACHE`（`common/src/main/java/mtr/client/ClientData.java:101`）；ANTE 通过 `cn/zbx1425/mtrsteamloco/mixin/ClientCacheAccessor.java:9` 访问其私有字段。图片内容由 `RouteMapGenerator` 生成。

本分支改动：`ColorNameTuple` 增加 `lineKey`（`Route.getLineKey()`，同一线路不同颜色/名称的线路归为一条）；`requestStationIdToPlatforms` 只保留确实属于该站的站台（重叠车站时不重复）；`getFormattedRouteDestination` 对环线（标为顺/逆时针或首尾相同的线路）显示方向与下一站，并新增 `routeMap` 参数；新增 `getPlatformsInArea`、`getStationsAt`。

## 类 `ClientCache`（extends `DataCache`，implements `IGui`）

### 字段
- `font`、`fontCjk`：AWT 字体（`font/noto-sans-semibold.ttf`、`font/noto-serif-cjk-tc-semibold.ttf`，首次生成资源时从资源包加载）。
- `stationIdToRoutes`（public）：车站 id → (线路颜色 → `ColorNameTuple`)，只含未隐藏线路。
- `liftsClient`、`liftsClientIdMap`：客户端电梯及其 id 索引。
- `posToPlatforms`、`posToSidings`：按交通方式，轨道中点位置 → 站台/停车轨列表（地图控件用）。
- `stationIdToPlatforms`、`depotIdToSidings`、`platformIdToRoutes`：按需建立的查询缓存。
- `clearStationIdToPlatforms`、`clearDepotIdToSidings`、`clearPlatformIdToRoutes`：同步后待清除的键队列（每帧清一个，见 `clearDataIfNeeded`）。
- `dynamicResources`（键 → `DynamicResource`，有序）、`resourcesToRefresh`（待重新生成的键）、`resourceRegistryQueue`（生成完成后在渲染线程注册纹理的任务队列）。
- 常量：`LINE_HEIGHT_MULTIPLIER = 1.25F`（行高 = 字号 × 1.25）；`DEFAULT_BLACK/WHITE/TRANSPARENT_RESOURCE`（`textures/block/black|white|transparent.png`，生成前或失败时的占位）。

### 构造函数 `ClientCache(Set<Station>, Set<Platform>, Set<Siding>, Set<Route>, Set<Depot>, Set<LiftClient> lifts)`
- 传给 `DataCache`（服务器端的电梯集合传空集），为每种交通方式建好位置索引。

### `protected void syncAdditional()`（覆盖 `DataCache`）
- 作用：每次 `sync()` 后：同步电梯 id；重建位置→站台/停车轨索引；重建 `stationIdToRoutes`（线路名取 `||` 前部分）；把三个查询缓存的现有键加入待清除队列（逐帧失效，而不是一次全清）。

### `void resetFonts()`
- 清空字体并刷新全部动态纹理。调用者：`common/src/main/java/mtr/client/CustomResources.java:49`（资源包重载）。

### `void refreshDynamicResources()`
- 把所有已有动态纹理的键标为待重新生成。调用者：`ClientData.java:404`、`ClientEditing.java:338`、`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:567`。

### `void syncLiftIds()`
- 重建 `liftsClientIdMap`。调用者：`ClientData.java:251`、`:274`。

### `Map<Long, Platform> requestStationIdToPlatforms(long stationId)`
- 返回：该站范围内、交通方式相符、且 `platformIdToStation` 归属就是该站的站台（缓存）；车站不存在返回空表。调用者：`common/src/main/java/mtr/render/RenderPIDS.java:476`、`common/src/main/java/mtr/client/editor/EditorLayout.java:143`、`cn/zbx1425/mtrsteamloco/scripting/util/client/MinecraftClientUtil.java:145`。

### `Map<Long, Siding> requestDepotIdToSidings(long depotId)`
- 返回：车厂范围内的停车轨（缓存）。调用者：`common/src/main/java/mtr/screen/DashboardScreen.java:302`、`common/src/main/java/mtr/screen/EditDepotScreen.java:72`。

### `List<PlatformRouteDetails> requestPlatformIdToRoutes(long platformId)`
- 返回：经过该站台的每条线路：名称、颜色、环线状态、本站序号，以及每站的站名与换乘线路（排除本线颜色）。缓存。调用者：`common/src/main/java/mtr/screen/PIDSConfigScreen.java:239`、`common/src/main/java/mtr/screen/RailwaySignScreen.java:85`。

### `String[] requestLiftFloorText(BlockPos pos)`
- 返回：`{楼层号, 楼层描述}`，取自该位置的电梯楼层方块实体；否则两个空串。未缓存（源码 TODO）。调用者：`common/src/main/java/mtr/render/RenderLiftButtons.java:84`、`common/src/main/java/mtr/render/RenderLiftPanel.java:102`。

### `Set<Station> getConnectingStationsIncludingThisOne(Station station)`
- 返回：该站及与其相连（区域相邻）的车站。调用者：`RailwaySignScreen.java:88`。

### `Map<Integer, ColorNameTuple> getAllRoutesIncludingConnectingStations(Station station)`
- 返回：该站及相连车站的全部线路（按颜色合并）。调用者：`common/src/main/java/mtr/render/RenderRailwaySign.java:187`、`RailwaySignScreen.java:87`。

### `String getFormattedRouteDestination(Route route, int currentStationIndex, String circularMarker)` / `(…, boolean routeMap)`
- 作用：显示用的终点文字。线路自定义终点（`route.getDestination`）优先；环线（标为顺/逆时针，或 `route.isLoop()` 首尾相同）：显示方向与下一个停站（跳过通过站；在一圈末尾时下一站为起点后第一站），`routeMap` 为 true 时用 `RouteMapText.loopNext`，否则用翻译 `gui.mtr.<clockwise|anticlockwise|loop>_next[_cjk]`（首尾相同但未标顺/逆时针时为 `loop`）；`circularMarker` 加在前面。非环线返回末站站名。之后的"经由/开往"分支（原上游逻辑：取后续第一个有多条线路的车站作为经由站）因前面的环线判断已涵盖所有环线状态，实际只在非环线时返回末站名。任何异常返回空串。
- 调用者：`common/src/main/java/mtr/client/RouteMapGenerator.java:278`、`RenderPIDS.java:184`、`:221`。

### 动态纹理获取（均经 `getResource`，键为参数拼接字符串，生成函数在 `RouteMapGenerator`）
- `getPixelatedText(text, textColor, maxWidth, cjkSizeRatio, fullPixel)`：像素风文字（列车显示屏）。调用者：`common/src/main/java/mtr/client/DynamicTrainModel.java:298`、`common/src/main/java/mtr/model/ModelClass377.java:1762`。
- `getColorStrip(platformId)`：站台经过线路的颜色条，占位透明。调用者：`common/src/main/java/mtr/render/RenderPSDTop.java:107`、`common/src/main/java/mtr/render/RenderAPGGlass.java:39`。
- `getStationName(stationName, aspectRatio)`、`getTallStationName(textColor, stationName, stationColor, aspectRatio)`、`getStationNameEntrance(textColor, stationName, aspectRatio)`、`getSingleRowStationName(platformId, aspectRatio)`：各种站名牌。
- `getSignText(text, horizontalAlignment, paddingScale, backgroundColor, textColor)`：标志文字。调用者：`RenderRailwaySign.java:276`。
- `getLiftPanelDisplay(originalText, textColor)`：电梯面板显示。
- `getExitSignLetter(exitLetter, exitNumber, backgroundColor)`：出口字母。
- `getRouteSquare(color, routeName, horizontalAlignment)`：线路色块。
- `getDirectionArrow(platformId, hasLeft, hasRight, horizontalAlignment, showToString, paddingScale, aspectRatio, backgroundColor, textColor, transparentColor)`：方向箭头与"往…"。调用者：`RenderRailwaySign.java:240`、`common/src/main/java/mtr/render/RenderRouteBase.java:83`。
- `getRouteMap(platformId, vertical, flip, aspectRatio, transparentWhite)`：路线图。调用者：`RenderRouteBase.java:85`、`common/src/main/java/mtr/render/RenderRouteSign.java:81`。
- 参数单位：`aspectRatio` 宽高比；颜色为 ARGB/RGB 整数；`paddingScale` 相对比例。

### `byte[] getTextPixels(String text, int[] dimensions, int fontSizeCjk, int fontSize)`
- 单行版本（`horizontalAlignment = null`，最大高度为较大字号 × 1.25）。

### `byte[] getTextPixels(String text, int[] dimensions, int maxWidth, int maxHeight, int fontSizeCjk, int fontSize, int padding, HorizontalAlignment horizontalAlignment)`
- 作用：把文字画成灰度图。按 `|` 分段（`Config.languageOptions()` 为 1/2 时只保留中文或非中文段，过滤后为空则全部保留）；中文段（或主字体显示不了）用 CJK 字号；逐字选择能显示的字体（主字体 → CJK 字体 → 系统任一字体）。`horizontalAlignment == null` 时各段横排一行（段间距 `padding`）；否则各段竖排，每段宽度不超过 `maxWidth`（必要时横向压缩），总高度超过 `maxHeight` 时纵向压缩，按对齐方式放置，四周留 `padding`。
- 参数：`dimensions` 输出数组，写入 `[宽, 高]`（像素）。`maxWidth <= 0` 时输出 0×0。
- 返回：`TYPE_BYTE_GRAY` 像素数组（行优先）。调用者：`RouteMapGenerator.java:49`、`:93`、`:115`。

### `void clearDataIfNeeded()`
- 每次调用从三个待清除队列各取一个键并删除对应缓存。调用者：`common/src/main/java/mtr/render/RenderTrains.java:368`（每帧）。

### `Map<BlockPos, List<Platform>> getPosToPlatforms(TransportMode)` / `Map<BlockPos, List<Siding>> getPosToSidings(TransportMode)`
- 调用者：`common/src/main/java/mtr/screen/WidgetMap.java:199`、`:216`、`:586`。

### `private DynamicResource getResource(String key, Supplier<NativeImage> supplier, DefaultRenderingColor defaultRenderingColor)`
- 作用：首次加载字体；每次调用从注册队列取出一个任务执行（把已生成的图片注册为纹理，替换并释放旧纹理）；已有且不需刷新则直接返回；否则 `RouteMapGenerator.setConstants()` 后异步生成图片，完成后排队注册（纹理名 `mtr:dynamic_texture_<URL 编码后的键，非 [0-9a-z_] 换成 _>`；图片为 null 时用占位）。
- 返回：已有的纹理（刷新期间仍返回旧的），或首次时的占位纹理。

### `Map<Long, Platform> getPlatformsInArea(Station station)`（本分支新增）
- 与 `requestStationIdToPlatforms` 不同，包含属于重叠车站的站台，不缓存。调用者：`DashboardScreen.java:282`。

### `List<Station> getStationsAt(BlockPos pos)`（本分支新增）
- 返回：区域包含该位置的所有车站，按 id 排序。调用者：`common/src/main/java/mtr/screen/PlatformScreen.java:109`。

### `private static … areaIdToSavedRails(U area, Set<V> savedRails)`
- 区域内（按轨道中点，交通方式相符）的站台/停车轨，id → 对象。

### `private static … mapPosToSavedRails(Map<BlockPos, List<U>>, Set<U>, TransportMode)`
- 清空后按 `getMidPos(true)` 分组。

## 内部类 `PlatformRouteDetails`
- `routeName`、`routeColor`、`circularState`、`currentStationIndex`、`stationDetails`。
- 内部类 `StationDetails`：`stationName`、`interchangeRoutes`（`ColorNameTuple` 列表）。

## 内部类 `ColorNameTuple`
- `color`、`name`、`lineKey`（本分支新增；两参数构造时等于 `name`）。调用者：`RouteMapGenerator.java:404`、`:517`。

## 内部类 `DynamicResource`
- `width`、`height`（像素，无图时 16×16）、`resourceLocation`。
- `remove()`：非占位纹理时从纹理管理器释放并关闭。调用者见 `ScrollingText.java:14`。

## 私有枚举 `DefaultRenderingColor`
- `BLACK`、`WHITE`、`TRANSPARENT`：各自的占位 `DynamicResource`。
