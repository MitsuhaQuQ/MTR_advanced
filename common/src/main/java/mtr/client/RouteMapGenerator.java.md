# RouteMapGenerator.java（上游文件，本分支有较多改动）

客户端：生成各种动态纹理的图片（`NativeImage`）：像素文字、颜色条、站名牌、标志文字、电梯面板、出口字母、线路色块、方向箭头、路线图。由 `ClientCache.getResource` 在后台线程（`CompletableFuture.supplyAsync`）调用，生成后再注册为纹理（见 `ClientCache.java.md`）。文字像素来自 `ClientCache.getTextPixels`。注意：所有写像素操作都经 `invertColor`（`NativeImage` 内部为 ABGR，代码中颜色为 ARGB）。

本分支改动：路线图改为按"线路"（`Route.getLineKey`）而不是按颜色处理——同一线路多种服务颜色时各用自己的颜色，画同样东西的线路只画一次，换乘按线路列出（同色两条线都显示），只通过的站不算换乘；环线单独画成闭合环（`generateLoopMap`）；路线图与方向箭头文字只用繁体中文与英文（`RouteMapText`）；方向箭头对环线显示方向与下一站。

## 类 `RouteMapGenerator`（implements `IGui`）

### 静态字段与常量
- `scale`：基本尺寸像素 = `2^(Config.dynamicTextureResolution() + 5)`；`lineSize = scale/8`（线宽）；`lineSpacing = lineSize*3/2`（并行线间距）；`fontSizeBig = lineSize*2`；`fontSizeSmall = fontSizeBig/2`。
- `PIXEL_SCALE = 4`（像素文字每个点放大倍数；`ScrollingText.java:42` 使用）、`MIN_VERTICAL_SIZE = 5`（竖排路线图最小高度，单位为 `scale`）、`PIXEL_RESOLUTION = 24`（像素文字基础字号）。
- 资源：`LOGO_RESOURCE`（`textures/block/sign/logo.png`）、`EXIT_RESOURCE`、`ARROW_RESOURCE`、`CIRCLE_RESOURCE`。
- `TEMP_CIRCULAR_MARKER`：临时标记，用于识别环线终点文字（这种终点不加"往"）。

### `static void setConstants()`
- 按当前分辨率设置上述尺寸。调用者：`common/src/main/java/mtr/client/ClientCache.java:451`（每次生成前）。

### `static NativeImage generatePixelatedText(String text, int textColor, int maxWidth, float cjkSizeRatio, boolean fullPixel)`
- 列车 LED 风格文字：多行居中；`cjkSizeRatio > 0` 放大中文、`< 0` 放大非中文；`fullPixel` 时每点 1 像素，否则每点 4 像素（画 3×3，留缝）；亮度 > 0x7F 的点才画。失败返回 null。

### `static NativeImage generateColorStrip(long platformId)`
- 1 像素宽、每条线路一像素高的颜色条（`getRouteStream` 颜色）；无线路返回 null。

### `static NativeImage generateStationName(String stationName, float aspectRatio)`
- 高 `2*scale`、宽按宽高比，白字居中，透明背景。`aspectRatio <= 0` 返回 null（以下同）。

### `static NativeImage generateTallStationName(int textColor, String stationName, int stationColor, float aspectRatio)`
- 竖排站名（`IGui.formatVerticalChinese`），以车站色为背景后把背景色清为透明。

### `static NativeImage generateStationNameEntrance(int textColor, String stationName, float aspectRatio)`
- 左侧标志 + 右侧站名（车站入口牌）；黑字时用近黑色做临时背景以便清除。

### `static NativeImage generateSingleRowStationName(long platformId, float aspectRatio)`
- 站台所属车站名单行（`|` 换成 ` | `），白底黑字。

### `static NativeImage generateSignText(String text, HorizontalAlignment, float paddingScale, int backgroundColor, int textColor)`
- 标志上的文字（高 `scale`，内边距按比例），背景色清为透明；宽度 ≤ 0 返回 null。

### `static NativeImage generateLiftPanel(String text, int textColor)`
- 电梯面板文字（大写，每段一行）。

### `static NativeImage generateExitSignLetter(String exitLetter, String exitNumber, int backgroundColor)`
- 出口字母（可带数字下标），底图为 `EXIT_RESOURCE`。

### `static NativeImage generateRouteSquare(int color, String routeName, HorizontalAlignment)`
- 线路色块：背景为线路色，白字线路名（`RouteMapText.bilingual`）。

### `static NativeImage generateDirectionArrow(long platformId, boolean hasLeft, boolean hasRight, HorizontalAlignment, boolean showToString, float paddingScale, float aspectRatio, int backgroundColor, int textColor, int transparentColor)`
- 作用：方向牌：左/右箭头、线路颜色圆（每条线路一段，上面写站台名）、终点文字。终点取经过线路的 `getFormattedRouteDestination(…, TEMP_CIRCULAR_MARKER, true)` 合并（`IGui.mergeStations`）；环线终点（以标记开头）不加"往"；`showToString` 时用 `RouteMapText.to`，否则 `RouteMapText.bilingual`。全为终到线路时只画圆。`transparentColor != 0` 时把该色清为透明。
- 参数：`horizontalAlignment` 为 CENTER 时，有左箭头或无右箭头则从左到右排列。

### `static NativeImage generateRouteMap(long platformId, boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite)`
- 作用：路线图。取要画的线路（`getMapRoutes`）；横排且只有一条环线时改画 `generateLoopMap`。否则：把每条线路分为本站之前/之后的车站，相同颜色分组为并行线（`colorIndices`）；用 `setup` 排布前后两侧（各线路共有的车站对齐为"公共站"）；计算原始宽高（竖排时最小高度 5），按宽高比确定图片尺寸与横纵缩放；白底；画线段（已过的段为浅灰）；把同一车站在同一 x 的公共位置合并，取换乘线路（排除本图所画的颜色、线路键与名称）；画车站与文字（公共站文字上下交替；竖排文字在下并旋转 90°；本站反白；已过站浅灰）。`transparentWhite` 时白色清为透明。
- 参数：`vertical` 竖排；`flip` 左右翻转（本站之前的车站画在右侧）。

### `private static NativeImage generateLoopMap(MapRoute mapRoute, boolean flip, float aspectRatio, boolean transparentWhite)`（本分支新增）
- 作用：把环线画成闭合环：本站在下排第一位，前方车站沿下排，其余从上排返回（上排展开到下排宽度）；本站之后半途画一个比线宽的箭头指示方向（`flip` 时反向）；有换乘时环内留更多空间。站间距 0.5 `scale`。

### `private static void drawStationWithText(NativeImage, long stationId, int x, int y, float heightScale, int lines, boolean textBelow, boolean currentStation, boolean passed, List<Integer> interchangeColors, List<String> interchangeNames, int maxStringWidth, boolean vertical)`（本分支提取）
- 作用：画一个车站：非本站且有换乘时在线的一侧画换乘色短条与换乘线路名；画车站圆点（`lines` 为跨越的并行线数）；在另一侧画站名（本站黑底白字）。

### `private static void getInterchanges(Map<Long, Map<String, ColorNameTuple>>, long stationId, Set<Integer> currentRouteColors, Set<String> currentLineKeys, Set<String> currentRouteNames, List<Integer> interchangeColors, List<String> interchangeNames)`（本分支新增）
- 作用：该站的换乘线路（按颜色、名称排序），排除颜色、线路键或名称与本图线路相同者；结果去重追加到两个输出列表。

### `static void scrollTextLightRail(PoseStack, VertexConsumer, int rows, float availableWidth, float availableHeight, int imageWidth, int imageHeight)`
- 轻铁车头显示屏滚动：每行先停 3000 ms，再每 8 ms 移动一像素，多行轮流（按 `System.currentTimeMillis()`）。调用者：`common/src/main/java/mtr/model/ModelLightRail.java:1061`。

### `private static void setup(List<Map<Integer, StationPosition>> stationPositions, List<List<Long>> stationsIdLists, int[] colorIndices, float[] bounds, boolean passed, boolean reverse)`
- 作用：排布本站一侧的车站：找出所有线路都有的公共站；在相邻公共站之间按各线路站数均分横向位置，纵向按该区段中的线路序号展开；公共站对齐。写入 `stationPositions`（键为相对本站的站序偏移，`passed` 时为负），更新 `bounds`（`[0]` 最大 x、`[1]` 最小 y、`[2]` 最大 y）。`reverse` 时 x 取负。

### `private static float getLineOffset(int routeIndex, int[] colorIndices)`
- 并行线的纵向偏移（以 `scale` 为单位，按颜色组居中）。

### `private static List<Integer> getRouteStream(long platformId, BiConsumer<Route, Integer> nonTerminatingCallback)`
- 作用：经过该站台且在此停站（非通过）、未隐藏的线路按颜色排序；非终到的线路回调 `(线路, 本站序号)` 并收集颜色；若全部终到则返回终到线路的颜色。

### `private static List<MapRoute> getMapRoutes(long platformId)`（本分支新增）
- 作用：要画的线路，每条只保留停站。同一线路键在此有多种服务颜色（`Route.getOwnColor`）时用各自颜色，否则用线路颜色；只有一站的跳过；与已有线路画法相同（`drawsSameAs`）的跳过；按线路颜色、线路键、颜色排序。

### `private static String getLineKey(Route route)`
- `交通方式 + route.getLineKey()`。

### `private static long getStationId(long platformId)` / `private static String getStationName(long platformId)`
- 站台所属车站 id（无则 −1）/ 站名（无则空串）。

### `private static Map<Long, Map<String, ColorNameTuple>> getInterchangeRoutes()`（本分支新增）
- 每个车站在此停站（非通过）的未隐藏线路，按线路键（同色两条线也分开），值为 `(颜色, Route.getLineName(), 线路键)`。

### `private static void drawLine(NativeImage, StationPosition p1, StationPosition p2, float widthScale, float heightScale, float xOffset, float yOffset, int color)`
- 两站间的线：横向为主时用水平+45° 斜线（斜段放在离中线较近的一端）；纵向为主时两端 45° 斜线中间竖线。

### `private static void drawLine(NativeImage, int x, int y, int directionX, int directionY, int length, int color)`
- 沿方向画一段有宽度（`lineSize`，斜线宽按 √2 修正）的线。

### `private static void drawStation(NativeImage, int x, int y, float heightScale, int lines, boolean passed)`
- 车站圆点：白心黑（或浅灰）边；`lines > 0` 时纵向拉长跨越并行线。

### `private static void drawString(NativeImage, byte[] pixels, int x, int y, int[] textDimensions, HorizontalAlignment, VerticalAlignment, int backgroundColor, int textColor, boolean rotate90)`
- 把灰度文字像素按透明度混合到图上；背景色不透明时先填背景；`rotate90` 时逆时针旋转 90°。

### `private static void drawStringPixelated(NativeImage, byte[] pixels, int[] textDimensions, int textColor, boolean fullPixel)`
- 见 `generatePixelatedText`；纵向居中。

### `private static void drawResource(NativeImage, String resource, int x, int y, int width, int height, boolean flipX, float v1, float v2, int color, boolean useActualColor) throws IOException`
- 从资源包读图片并缩放绘制（只画纵向 `v1`–`v2` 部分）：`useActualColor` 用原图颜色，否则用原图透明度（双线性）乘以 `color`。

### `private static void blendPixel(NativeImage, int x, int y, int color)`
- 按 `color` 的透明度与现有像素混合（现有透明视为白），结果不透明。

### `private static void drawPixelSafe(NativeImage, int x, int y, int color)`
- 范围内才写（经 `invertColor`）。

### `private static int invertColor(int color)`
- ARGB ↔ ABGR（交换 R 与 B；有任何 alpha 位即视为不透明）。

### `private static void clearColor(NativeImage, int color)`
- 把等于该（已转换的）颜色的像素设为透明。

## 私有内部类 `StationPosition`
- `x`、`y`（以 `scale` 为单位，相对本站）、`isCommon`（公共站）。

## 私有内部类 `MapRoute`（本分支新增）
- 字段：`color`（本图所用颜色）、`lineColor`（线路颜色）、`lineKey`、`name`（`||` 前部分）、`stationIds`（停站序列，总含首末站与本站）、`currentIndex`、`loopStationIds`（环线时从本站开始绕一圈的每站，否则 null）。
- 构造：只保留停站（及首末站和本站）；若停站数 > 3、首末为同一已知车站且线路为环（`isLoop` 或有环线状态），则 `stationIds` 取本站前后各半圈（都到对面的车站为止），`currentIndex` 为后方半圈站数，并填 `loopStationIds`。
- `drawsSameAs(MapRoute)`：颜色、本站序号、站序列都相同。

## 私有内部类 `StationPositionGrouped`
- `stationPosition`、`stationOffset`（相对本站的站序，负为已过）、`interchangeColors`、`interchangeNames`。
