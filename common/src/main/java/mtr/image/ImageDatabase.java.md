# ImageDatabase.java（本分支新增）

导入图片的 SQLite 存储，服务端与客户端各一份：服务端为世界目录下 `mtr/pictures.db`（`ImageServer.open`），客户端缓存为游戏目录下 `mtr_image_cache/pictures.db`（`common/src/main/java/mtr/client/ImportedImages.java:43`，所有世界和服务器共用，只存内容）。与音频库同样的设计：名字有所有者，图片字节按 SHA-256 只存一份（引用表 + 内容表，无引用的内容自动删除）。

## 表结构
- `picture_index(name_key TEXT PRIMARY KEY, namespace, file_name, hash, width, height, size, uploader, uploader_name, uploaded_at)`：`name_key` 为小写的 `namespace:file_name`；`uploader` 为上传者 UUID 字符串；`uploaded_at` 为毫秒时间戳。索引 `picture_index_hash(hash)`。
- `picture_data(hash TEXT PRIMARY KEY, data BLOB)`。
- 打开时设 `PRAGMA journal_mode=WAL`。

## 类 `ImageDatabase`（final，implements `AutoCloseable`）

### 构造函数 `ImageDatabase(Path path) throws Exception`
- 建父目录、用 SQLite JDBC 打开、建表。

### `List<Entry> entries() throws SQLException`
- 返回内容完整（`length(data) = size`）的所有图片，按上传时间降序、名字升序。

### `void add(String namespace, String fileName, byte[] bytes, String uploader, String uploaderName) throws Exception`
- 作用：校验引用名格式（`ImageFiles.isReference`）且文件扩展名与内容一致，否则抛 `IllegalArgumentException("gui.mtr.image_invalid")`；`ImageFiles.check` 取尺寸；事务内插入内容（已存在忽略）、插入或替换索引行、删除无引用内容；失败回滚并上抛。

### `byte[] read(String hash) throws SQLException`
- 按哈希读内容，没有返回 null。

### `void writeData(String hash, byte[] bytes) throws SQLException`
- 客户端缓存写内容：超过最大字节数或哈希不符抛 `IllegalArgumentException("Invalid cached image")`。

### `void deleteData(String hash) throws SQLException`
- 删除内容行。

### `void delete(String reference) throws SQLException`
- 事务内按名字（不区分大小写）删索引行并清理无引用内容。

### `private void deleteUnused() throws SQLException`
- 删除没有任何索引行引用的内容。

### `void close() throws SQLException`
- 关闭连接。

## 内部类 `Entry`（final）
- 字段：`namespace`、`fileName`、`hash`、`name`（`namespace:fileName`）、`uploader`、`uploaderName`、`width`、`height`（像素）、`size`（字节）、`uploadedAt`（毫秒）。
- 调用者：`ImageServer`、`common/src/main/java/mtr/client/ImportedImages.java:20`。

## 0.74.0 媒体文件夹

- 新字段 `folders`（表 `picture_folders`，`picture_index` 加 `folder` 列）。`Entry.folder`（另有不带文件夹的构造函数，顶层）。
- `add(…, folder)` 写明列名插入（以前按位置插入，加列后会错位），并保留文件夹；不带文件夹的 `add` 放顶层。`entries()` 读出文件夹。

## 资源 ID 与替换（0.79.0）
- 字段 `ids`（`MediaIds.Store`，表 `picture_old_names`）；`Entry` 加 `resourceId`、`oldNames`。
- `add` 同名上传原地更新时保留原 ID；新名字是别的文件的曾用名时抛 `MediaIds.NAME_USED`。
- `replace(id, fileName, bytes)`：按 ID 换内容，可换文件名（命名空间、上传者、文件夹不变），旧名记为曾用名；新名是别的文件现在的名字或曾用名时抛 `NAME_USED`；清掉没人用的旧内容。
- `delete` 同时删掉这个文件的曾用名。
