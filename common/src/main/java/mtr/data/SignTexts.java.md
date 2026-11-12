# SignTexts.java

铁路标志和车站名牌上的文字：繁体中文、英文、日文三种同时显示，与玩家的游戏语言无关（2026-11-09）。每种语言是以 `|` 分隔文本的一部分，标志按每部分画一行。

车站或线路名称自身提供各部分：第一个汉字部分为中文名，第一个非汉字部分为英文名，第二个汉字或假名部分为日文名（没有时用中文名）。没有任何汉字/假名部分的名字只显示英文行；没有非汉字部分的名字不显示英文行。被 `BlockRailwaySign`（标志文字）、`RenderRailwaySign`、`RenderStationNameTiled`（车站名渲染）使用。

## final class SignTexts

### 字段
- `TEXTS`：标志名（翻译键 `sign.mtr.<name>` 的 `<name>`）→ `{中文, 英文, 日文}`，在静态块中填入（机场、出入口、洗手间、升降机、轻铁、高铁等约 37 条）。
- `STATION = {"%s站", "%s Station", "%s駅"}`：车站名模板。
- `TO = {"往%s", "To %s", "%s方面"}`：去向模板。

### `private SignTexts()`
工具类，禁止实例化。

### `private static void put(String name, String chinese, String english, String japanese)`
- 作用：向 `TEXTS` 加一条（仅静态块调用）。

### `public static String forSign(String name)`
- 参数：标志文字的翻译名。
- 返回：`中文|英文|日文`；没有此名返回 `null`。
- 主要调用者：`BlockRailwaySign.java:396`；测试 `SignTextsTest.java:16`。

### `public static String station(String name)`
- 返回：车站名套上 `STATION` 模板，例如 `中環站|Central Station|中環駅`（规则见 `fill`）。
- 主要调用者：`RenderRailwaySign.java:261`、`RenderStationNameTiled.java:35`。

### `public static String to(String destinations)`
- 返回：去向文字，例如 `往中環|To Central|中環方面`。
- 主要调用者：测试 `SignTextsTest.java:26`（游戏代码调用未找到，未确定）。

### `public static String[] nameParts(String name)`
- 作用：按类说明把名字拆成 `{中文, 英文, 日文}`。按 `|` 分割，忽略空白部分，用 `IGui.isCjk` 区分汉字/假名与其它字母。中文：第一个 CJK 部分；没有则第一个其它部分；都没有则原名。英文：第一个其它部分，没有则等于中文。日文：第二个 CJK 部分，没有则等于中文。
- 主要调用者：本类 `ordered`、`fill`。

### `public static String ordered(String text)`
- 返回：名字（或经 `IGui#mergeStations` 合并、汉字部分在前的多个名字）按中文、英文、日文顺序重新拼成的 `a|b|c`；空或 `null` 返回 `""`。
- 主要调用者：`RenderRailwaySign.java:261`。

### `private static String fill(String[] templates, String name)`
- 作用：把模板应用到名字上：空名返回 `""`；名字不含 CJK 时只返回英文行（避免 "Airport站"）；所有非空部分都是 CJK 时只返回中文行与日文行；否则三行都返回。
