# SecurityConfig.java（本分支新增）

服务器端设置（也在客户端集成服务器中加载）：读取 `<服务器目录>/config/mtr_security.json`，包含网页服务器安全、编辑权限、音频/图片上传权限与容量、以及运行规则开关（咽喉锁、网页日模拟、ANTE 运行钩子、联锁/留置闭塞颜色、闭塞分区切分参数）。所有字段为 `volatile` 静态字段，供各处直接读取。加载：服务器启动时 `common/src/main/java/mtr/MTR.java:587`（随后 `apply` `:588`），客户端 `common/src/main/java/mtr/MTRClient.java:407`、`:408`。测试：`common/src/test/java/mtr/SecurityConfigTest.java`。文件不存在时以默认值写出；读取失败时"失败即关闭"：关闭网页服务器并把音频单文件上限设为 0。

## 类 `SecurityConfig`（final，私有构造）

### 设置项（字段 · JSON 键 · 默认 · 范围 · 含义 · 主要读取者）
- `webserverEnabled` · `webserverEnabled` · true · 是否启动 MTR 网页服务器 · `common/src/main/java/mtr/servlet/Webserver.java:89`。
- `webserverBindHost` · `webserverBindHost` · `127.0.0.1` · 绑定地址 · `Webserver.java:103`。
- `webserverToken` · `webserverToken` · 空 · 访问令牌 · `common/src/main/java/mtr/servlet/WebSecurityFilter.java:83`、`Webserver.java:84`。
- `webserverCorsOrigin` · `webserverCorsOrigin` · 空 · 允许的跨域来源 · `common/src/main/java/mtr/servlet/IServletHandler.java:24`。
- `webserverAllowedHosts` · `webserverAllowedHosts`（数组，转小写）· 空 · 允许的 Host 头 · `WebSecurityFilter.java:75`。
- `editPermissionLevel` · 同名 · 0 · 0–4 · 编辑铁路数据所需的权限等级 · `common/src/main/java/mtr/data/RailwayData.java:1704`、`common/src/main/java/mtr/image/ImageServer.java:40`。
- `audioUploadPermissionLevel` / `audioManagePermissionLevel` · 同名 · 2 / 2 · 0–4 · 上传广播音频 / 删除任何人的音频所需权限 · `common/src/main/java/mtr/audio/AudioServer.java:387`、`:364`。
- `imageUploadPermissionLevel` / `imageManagePermissionLevel` · 同名 · 2 / 2 · 0–4 · 图片上传 / 管理 · `ImageServer.java:42`、`:43`。
- `audioMaxFileKilobytes` · 同名 · 5120 · 0…`AudioFiles.MAX_FILE_BYTES/1024` · 单个音频上限（KB）· `AudioServer.java:514`。
- `audioMaxTotalMegabytes` · 同名 · 256 · 0–4096 · 服务器音频总量（MB）· `AudioServer.java:518`。
- `imageMaxTotalMegabytes` · 同名 · 256 · 1–4096 · 图片总量（MB）· `ImageServer.java:53`。
- `audioMaxSeconds` · 同名 · `DEFAULT_AUDIO_MAX_SECONDS = 300` · 1–3600 · 单个音频最长秒数；读到旧默认值 60（`LEGACY_AUDIO_MAX_SECONDS`）时迁移为 300（旧版本把旧默认写进了文件）· `AudioServer.java:166`。
- `audioUploadsPerHour` · 同名 · 30 · 0–3600 · 每人每小时上传次数 · `AudioServer.java:420`。
- `throatLocks` · 同名 · true · 运用中的列车在车站咽喉加锁通过（L2，`ThroatLocks`）；关闭则照旧运行 · 经 `apply` 写入 `transitsim.sim.ThroatLocks.enabled`。
- `editorSimulation` · 同名 · false · 网页编辑器的一天快速模拟（"模拟运行"，S5，调试工具，大世界中很占服务器时间）；关闭时编辑器不提供、服务器拒绝 · `common/src/main/java/mtr/advanced/SandboxRuns.java:108`、`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:106`。
- `anteRunHooks` · 同名 · false · ANTE 改变列车运行方式的 mixin（在其装饰方块和 Metropolis 站台开门、其轨道类型的手动驾驶速度、其判定玩家附近轨道的方式）；关闭时运行 MTR 自己的规则，ANTE 的模型、方块、脚本与存档数据都保留 · `cn/zbx1425/mtrsteamloco/mixin/RailwayDataMixin.java:32`、`cn/zbx1425/mtrsteamloco/mixin/TrainMixin.java:193`。
- `interlockColor` · 同名（小写颜色名）· MAGENTA · 联锁区信号闭塞颜色（L2）：车站咽喉中此颜色的闭塞被经过的进路整体占用，同时仍是普通信号闭塞 · `RailwayData.java:577`、`common/src/main/java/mtr/advanced/SimExport.java:94`。
- `stablingColor` · 同名 · BROWN · 留置闭塞颜色（运用补完第 4 步）：每个留置一列等待下一班的列车，在其中的折返轨折返，作为站台外折返轨之外的另一种折返方式 · `common/src/main/java/mtr/data/DataCache.java:93`、`common/src/main/java/mtr/data/RailwayDataPathGenerationModule.java:115`。
- `blockDeceleration` · 同名 · 1.0 · 0.1–10（m/s²）· 长轨道按其限速下以此减速度的制动距离切分闭塞分区（`BlockSections`）。
- `blockMinLength` · 同名 · 400 · 16–10000（方块）· 闭塞分区最短长度。

### 常量
- `DEFAULT_BIND_HOST = "127.0.0.1"`、`LEGACY_AUDIO_MAX_SECONDS = 60`、`DEFAULT_AUDIO_MAX_SECONDS = 300`（包内可见）。
- `KEY_*`：各 JSON 键（图片相关键直接写字符串）。

### `static void load(Path path)`
- 作用：文件存在则逐项读取（缺失用默认，数字限制在范围内，颜色名无效时联锁用 MAGENTA、留置用 BROWN）；不存在则写出默认文件；任何异常打印并关闭网页服务器、音频单文件上限置 0。

### `static void apply()`
- 把仿真组件读取的设置交给组件：`ThroatLocks.enabled`、`BlockSections.deceleration`、`BlockSections.minLength`。

### `static boolean isLoopbackBinding()`
- 绑定地址是否为本机回环（`localhost`、`::1`、`127.*`）。调用者：`WebSecurityFilter.java:76`、`Webserver.java:84`。

### `private static String getString(JsonObject, String key, String defaultValue)` / `private static int getInt(JsonObject, String key, int defaultValue, int minimum, int maximum)`
- 取值（缺失用默认），整数限制在范围内。

### `private static void write(Path path) throws Exception`
- 以当前值写出格式化 JSON（`webserverAllowedHosts` 写为空数组），先建目录。

### `static DyeColor parseColor(String name)` / `static DyeColor parseColor(String name, DyeColor fallback)`
- 不区分大小写匹配染料颜色名，否则返回后备（默认 MAGENTA）。调用者：测试 `common/src/test/java/mtr/data/TimetableConflictsTest.java:108`。
