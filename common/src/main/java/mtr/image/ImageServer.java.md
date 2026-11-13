# ImageServer.java（本分支新增）

服务端：整个世界共用的"导入图片"库（玩家上传 PNG/JPEG/GIF，用于原版画框显示），以及设置某幅画显示哪张图。所有修改都在服务器线程执行（经 `PacketGuard.execute`）。生命周期与调用者在 `mtr.MTR`：服务器启动 `open`（`common/src/main/java/mtr/MTR.java:594`）、停止 `close`（`:599`）、每 tick `tick`（`:537`）、玩家加入 `sendList`（`:557`）、离开 `forget`（`:572`）；C2S 包 `PACKET_IMAGE_ACTION` 由 `PacketGuard.register(..., ImageServer::receive)` 注册（`:523`）。下发包通道 `PACKET_IMAGE_DATA`。

## 包格式

### C2S `PACKET_IMAGE_ACTION`（第一个字节为动作）
- `0` 上传分块：`String 名字(≤64，经 AudioFiles.cleanName)`、`int 总大小`、`int 偏移`、`byte[] 分块(≤ CHUNK)`。
- `1` 请求下载：`String 哈希(≤64)`。
- `2` 设置画：`int 实体 id`、`String 设置 JSON(≤512)`。
- `3` 删除：`String 引用名(≤129)`。
- 其他值抛 `IllegalArgumentException("Unknown image action")`。

### S2C `PACKET_IMAGE_DATA`（第一个字节为类型）
- `0` 列表：`String 会话 id`、`boolean 能否上传`、`int 条数`，每条 `String 哈希, String 名字, int 宽, int 高, int 字节数, boolean 能否删除, String 上传者 UUID, String 上传者名, long 上传时间`，最后 `long 总容量上限字节`。
- `1` 下载分块：`String 哈希`、`int 总长`、`int 偏移`、`byte[] 分块`。
- `2` 结果：`boolean 成功`、`boolean 是否属于传输（上传）`、`String 翻译键`。

## 类 `ImageServer`（final，implements `IPacket`，私有构造）

### 静态字段
- `database`：世界的图片库（`<世界>/mtr/pictures.db`）。
- `ENTRIES`：当前图片列表缓存。
- `UPLOADS`（玩家 → 进行中的上传）、`DOWNLOADS`（玩家 → 待发下载队列）、`ATTEMPTS`（玩家 → 最近一小时上传开始时刻）、`PERMISSIONS`（玩家 → 上次发送的权限位）。
- `session`：每次打开生成的随机 UUID，客户端据此区分不同世界/服务器会话。
- `ticks`：tick 计数。

### `static void open(MinecraftServer server)`
- 先 `close()`，再打开数据库、读列表、生成会话 id；失败打印堆栈并关闭（功能停用）。

### `static void close()`
- 关闭数据库并清空全部状态。

### `static void forget(ServerPlayer player)`
- 清除该玩家的上传、下载和权限缓存（上传尝试计数保留）。

### `static boolean canEdit(ServerPlayer player)`
- 非旁观者且权限等级 ≥ `SecurityConfig.editPermissionLevel`（设置画所需）。

### `private static boolean owner(ServerPlayer)`
- 单人/局域网的世界主人（非专用服务器）。

### `private static boolean canUpload(ServerPlayer)`
- 非旁观者且（世界主人或权限 ≥ `SecurityConfig.imageUploadPermissionLevel`）。

### `private static boolean canDelete(ServerPlayer, ImageDatabase.Entry)`
- `MediaPermissions.canDelete(上传者, 玩家 UUID, 主人或权限 ≥ imageManagePermissionLevel)`。

### `private static String namespace(ServerPlayer)`
- 由玩家名得出命名空间，避开 `minecraft`、`mtr` 和服务器已有资源命名空间（`AnnouncementSound.getNamespace`）。

### `static void sendList(ServerPlayer player)`
- 发类型 0 列表包。

### `private static void result(ServerPlayer, boolean ok, String key)` / `result(..., boolean transfer)`
- 发类型 2 结果包（默认 `transfer = true`）。

### `static void receive(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet)`
- 作用：在网络线程读包，之后在服务器线程处理：
  - 0：`upload(...)`；
  - 1：库存在、哈希合法且在列表中时，加入下载队列（每人最多 16 个、不重复）；
  - 2：实体须是画、玩家能编辑、距离 ≤ 8 格（平方 64）、主手或副手拿刷子，否则回"无权限"；解析设置（`PaintingSettings.parse`），图片名必须是库里已有的（统一为库中大小写），写入画（`mtr$setImageSettings`，经实体同步给客户端），回"已保存"；设置不合法回 `gui.mtr.image_invalid_settings`；
  - 3：引用名合法、存在且有删除权限时删除、刷新列表并广播给所有玩家。
- 异常：未知动作抛 `IllegalArgumentException`（由 `PacketGuard` 处理）。

### `private static void upload(MinecraftServer server, ServerPlayer player, String name, int size, int offset, byte[] chunk)`
- 作用：权限、大小（1–5 MB）、偏移检查；偏移 0 表示新上传：一小时内已开始 30 次或已有进行中的上传回 `gui.mtr.image_busy`；分块必须与已收到的连续、名字一致；收齐后：按内容定扩展名并替换原扩展名，命名空间由玩家名得出；同名图片属于他人回无权限；新图片数达到 256 张或总容量超过 `SecurityConfig.imageMaxTotalMegabytes` 回 `gui.mtr.image_full`；否则写库（同名时保留原文件名大小写）、刷新列表、回"已上传"并广播列表。异常消息若是 `gui.mtr.image_*` 翻译键则原样回给玩家。

### `static void tick(MinecraftServer server)`
- 作用：每 20 tick 计算每个玩家的权限位（1 能上传图片、2 能管理图片、4 能上传音频、8 能管理音频），变化时重发图片和音频列表（`AudioServer.sendList`）；超过 30 秒无进展的上传作废并回 `gui.mtr.image_timeout`；每 tick 每个玩家最多发 2 个下载分块（每块 ≤ 30 KB），玩家离线则丢弃其队列。

## 私有内部类 `Upload`
- `name`、`bytes`（按总大小预分配）、`offset`（已收字节）、`updated`（最近收到时间）。

## 私有内部类 `Download`
- `hash`、`bytes`、`offset`（已发字节）。

## 0.74.0 媒体文件夹

- `FOLDERS`；列表包在总容量之后按行写每行的文件夹，然后文件夹列表，最后 `boolean 能否整理所有人的`。
- 上传分块（动作 0）末尾可带文件夹，规则同音频。
- 动作 4：文件夹动作 `String 所有者, byte 动作, String 路径, String 参数, int 数量, String 引用名...`，权限同删除，结果以消息回（不算上传）。

## 资源 ID 与替换（0.79.0）
- 列表包末尾追加每张图片的 `resourceId` 和曾用名（数量 + 名字），旧客户端读不到也不影响。
- 新动作 5：按 ID 替换上传（`String id`，其后同动作 0 的分块），由上传者或管理员发起；容量按替换后计算；成功提示 `gui.mtr.image_replaced`，所有玩家重发列表。
- `find(reference)`：按名字或曾用名找图片；刷子保存画的设置时用它把引用规范成现在的名字。
