# LineWords.java

线路名称中表示方向或服务类型的词（例如"上行""快速""Express"），同一条"线"（line）的各线路不必共有这些词。线路按去掉这些词后的名字归为一条线（`Route.java:980` 的线路键计算）。词表读自配置目录下的 `mtr_line_words.json`，首次运行时从 jar 中的同名文件复制，服务器可以自行添加；服务器把词表发给玩家（`PacketTrainDataGuiServer.java:76` → `PacketTrainDataGuiClient.java:57`），使所有人以相同方式把线路归线。

## final class LineWords

### 字段与常量
- `configPath`、`startPattern`、`endPattern`、`servicePattern`、`wordsJson`：均为 volatile 静态字段；`wordsJson` 为当前使用的原始 JSON（初始 `"{}"`）。
- `FILE_NAME = "mtr_line_words.json"`；`DEFAULT_FILE = "/mtr_line_words.json"`（jar 内资源）。
- JSON 键：`directionWords`（方向词数组）、`serviceWords`（服务类型词数组）。
- `SEPARATORS`：空白、`-`、`_`、`/`、`:`、`：`、`·`、`・`。
- `OPEN_BRACKETS` / `CLOSE_BRACKETS`：半角/全角圆括号、方括号、`【】`。
- 静态块：类加载时用 jar 内默认文件设置词表（失败打印堆栈）。

### `private LineWords()`
工具类，禁止实例化。

### `public static void load(Path path)`
- 作用：记住路径并 `reload()`。
- 主要调用者：`MTR.java:589`。

### `public static void reload()`
- 作用：文件存在则读取设置；不存在则用默认文件设置并写出该文件。失败打印 "Failed to read ... line words"，词表保持原样。
- 主要调用者：`DashboardScreen.java:86`。

### `public static String getWordsJson()`
- 返回：当前词表 JSON，服务器发给玩家。
- 主要调用者：`PacketTrainDataGuiServer.java:76`。

### `public static void setWordsFromServer(String json)`
- 作用：客户端改用所加入服务器的词表；解析失败打印日志并保持原样。
- 主要调用者：`PacketTrainDataGuiClient.java:57`。

### `public static String findServiceWord(String text)`
- 返回：文本中出现的服务类型词（小写）；没有返回 `""`。
- 主要调用者：`Route.java:938`、`EditRouteScreen.java:79`。

### `public static String removeFromEnds(String text)`
- 作用：反复从文本两端去掉这些词（服务词与方向词），直到不再变化，并去空白。词表为空时只 `trim`。
- 主要调用者：`Route.java:980`；测试 `LineWordsTest.java:37`。

### `private static byte[] readDefaultFile()`
- 读 jar 内默认文件的全部字节。

### `private static void setWords(String json)`
- 作用：解析 JSON 并编译正则：`servicePattern` = 服务词的选择组；词组（服务词 + 方向词）生成：
  - `endPattern`：`[分隔符]*[左括号]?\s*(词组)[线線]?\s*[右括号]?$`；
  - `startPattern`：`^[左括号]?\s*(词组)\s*[右括号或分隔符]+`。
  均忽略大小写（Unicode）。最后保存 `wordsJson`。
- 异常：JSON 无效时抛出（由调用方捕获）。

### `private static String getWordGroup(List<String> words)`
- 作用：去重、按长度降序（避免 "upbound" 被当作 "up"）、逐个转正则，拼成 `(a|b|...)`。

### `private static void addWords(JsonObject jsonObject, String key, List<String> words)`
- 作用：把数组中非空（去空白后）的词加入列表。

### `private static String toRegex(String word)`
- 作用：纯英文字母/空格的词要求前后不是小写字母（防止 "Backup" 失去词尾 "up"）；其余词按字面量。
