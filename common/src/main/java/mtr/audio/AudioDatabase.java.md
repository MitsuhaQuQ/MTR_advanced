# AudioDatabase.java（本分支新增）

广播音频的 SQLite 存储。服务端每个存档一个文件，放在铁路数据旁（`<世界>/mtr/announce.db`，`common/src/main/java/mtr/audio/AudioServer.java:75`–`:77`），不再一段录音一个文件；客户端缓存用同样格式但只存音频内容（`common/src/main/java/mtr/client/AudioCache.java:406`）。索引表每行对应玩家使用的一个名字（`AnnouncementSound`）：命名空间、文件名、上传者、音频哈希等；索引在启动时整体读入。音频按哈希只存一份，无论多少名字指向它，只在发送时才读取。名字只需在同一玩家的文件间唯一；内部一律按哈希。

## 表结构
- `audio_index(name_key TEXT PRIMARY KEY, namespace, file_name, hash, size, duration_millis, uploader, uploader_name, uploaded_at, sound_id TEXT NOT NULL DEFAULT '')`：`name_key` 为小写名字；`sound_id` 列是后加的，打开旧库时 `ALTER TABLE` 补上（旧文件为空串，之后由服务端补算）。索引 `audio_index_hash(hash)`。
- `audio_data(hash TEXT PRIMARY KEY, data BLOB NOT NULL)`。
- `PRAGMA journal_mode=WAL`、`synchronous=NORMAL`。

## 类 `AudioDatabase`（final，implements `AutoCloseable`）

### 常量 `FILE_NAME = "announce.db"`

### 字段
- `connection` 与预编译语句：`writeIndexStatement`（插入或替换索引）、`writeDataStatement`（插入音频，已存在忽略）、`readDataStatement`、`deleteIndexStatement`、`deleteUnusedDataStatement`（某哈希无引用时删除）、`setSoundIdStatement`。

### 构造函数 `AudioDatabase(Path path) throws Exception`
- 建目录、打开、建表/补列、准备语句；建表失败关闭连接并上抛。

### `void readIndex(IndexConsumer consumer) throws SQLException`
- 逐行回调索引；音频缺失或长度与 `size` 不符的名字跳过（客户端反正会校验）。

### `void readDataHashes(Consumer<String> consumer) throws SQLException`
- 回调所有已存音频的哈希（客户端缓存用：它只存无名音频）。

### `void writeData(String hash, byte[] data) throws SQLException` / `byte[] readData(String hash) throws SQLException`
- 写入（已存在则不变）/读取音频，没有返回 null。

### `void write(String nameKey, String namespace, String fileName, String hash, int size, long durationMillis, String uploader, String uploaderName, long uploadedAt, String soundId, byte[] data) throws SQLException`
- 名字与音频在同一事务写入，二者不会只存在其一；`replace(..., oldHash = null)`。

### `void replace(..., byte[] data, String oldHash) throws SQLException`
- 事务内写音频、写索引；若 `oldHash` 非空且与新哈希不同，旧音频无引用时删除；失败回滚并上抛。
- 参数：`size` 字节；`durationMillis` 时长毫秒；`uploader` UUID 字符串；`uploadedAt` 毫秒时间戳。

### `void setSoundId(String nameKey, String soundId) throws SQLException`
- 只更新某名字的 sound id。

### `void delete(String nameKey, String hash) throws SQLException`
- 事务内删除名字，音频无引用时一并删除。

### `void close() throws SQLException`

## 函数式接口 `IndexConsumer`
- `void accept(String namespace, String fileName, String hash, int size, long durationMillis, String uploader, String uploaderName, long uploadedAt, String soundId)`：`soundId` 对 sound id 出现之前上传的文件为空串。
