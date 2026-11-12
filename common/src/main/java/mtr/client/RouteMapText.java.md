# RouteMapText.java（本分支新增）

客户端：线路图上的两行文字（中文一行、英文一行），与玩家界面语言无关。从 `|` 分隔的多语言名称中取第一个含汉字的部分作中文（转为繁体）、第一个不含汉字的部分作英文，忽略含假名的部分。调用者：`common/src/main/java/mtr/client/RouteMapGenerator.java:256`、`:304`、`:306`，`common/src/main/java/mtr/client/ClientCache.java:230`；测试 `common/src/test/java/mtr/client/RouteMapTextTest.java`。

## 类 `RouteMapText`（final，私有构造）

### 常量 `TRADITIONAL`
- ICU `Simplified-Traditional` 转写器（Minecraft 自带 ICU）；可变对象，多个纹理生成线程共用，使用时加锁。

### `static String bilingual(String name)`
- `中文|英文`（任一缺失时只返回另一个）。

### `static String to(String name)`
- `往<中文>|To <英文>`。

### `static String loopNext(String kind, String name)`
- 环线下一站：`kind` 为 `clockwise` → `順時針環線 下一站…|Clockwise loop, next …`；`anticlockwise` → 逆时针；其他 → `環線 下一站…|Loop, next …`。

### `private static String format(String name, String chineseTemplate, String englishTemplate)`
- 作用：按上述规则拆分、转繁体、套模板；`name` 为空返回空串。
