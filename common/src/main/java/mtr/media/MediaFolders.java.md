# MediaFolders.java（0.74.0）

上传音频和图片的文件夹（用户 2026-11-13）：每个上传者名下可建文件夹，可嵌套（最多 `MAX_DEPTH` = 8 层），空的也保留。文件夹只用来整理：文件仍按"上传者命名空间:文件名"引用，音箱、广播器、画不受影响。一个文件夹属于一个上传者，只放他的文件。路径是从上传者顶层起的名字用 `/` 连起来，顶层为 `""`。音频库和图片库都用 `Store` 同样保存。

## 常量
- `MAX_DEPTH = 8`、`MAX_NAME_LENGTH = 32`、`MAX_PATH_LENGTH`。
- `INVALID = "gui.mtr.media_folder_invalid"`、`DONE`、`FAILED`、`NO_PERMISSION`：翻译键。
- 动作编号 `CREATE = 0`、`RENAME = 1`、`DELETE = 2`、`MOVE = 3`（两个服务端的包都用）。

## 静态方法
- `normalize(path)`：每层去首尾空格、去掉空层；名字不合规（见 `checkName`）或超过 8 层抛 `IllegalArgumentException(INVALID)`。
- `checkName(name)`：一个名字：非空、不超过 32 字、不能有 `/`、`\`、控制字符、不能只有点。
- `parent`、`name`、`child`、`depth`；`within(path, folder)`（不分大小写，`""` 包含一切）；`moved(path, from, to)`；`afterDelete(path, deleted)`（删除后它里面的东西移到它的上一层）；`withParents(paths)`（连同上级、去重、按不分大小写排序）。
- `apply(store, action, owner, ownerName, path, argument, keys, ownerOf, ownerPaths)`：执行一个动作（权限由调用者先查）。新建：在 `path` 里建 `argument`，同名拒绝；改名：`path` 改名为 `argument`；删除：`path`；移动：`keys` 都必须是 `owner` 的（否则 `NO_PERMISSION`），移到 `argument`。返回翻译键：`DONE`、`INVALID`（名字不合规、目标已存在、改到自己里面、改后超过 8 层）或 `FAILED`（数据库出错）。

## 类 `Folder`
- `owner`（上传者 UUID）、`ownerName`、`path`。

## 类 `Store`
- 构造：建文件夹表（`owner, path, owner_name, created_at`，主键 `owner, path`），文件表没有 `folder` 列时 `ALTER TABLE` 加上（默认 `''`）。
- `folders()`：保留的文件夹；`fileFolders()`：名字键 → 文件夹。
- `create`（连同上级）、`rename(owner, ownerName, from, to, paths)`（里面的文件夹和文件一起改；目标已存在、在自己里面、改后太深则拒绝；只改大小写可以）、`delete`（里面的东西上移一层，同名文件夹合并，不删文件）、`move(owner, ownerName, keys, folder)`（只改 `uploader = owner` 的行，目标文件夹保留）。都在一个事务里。
- `setFolder`、`insertWithParents`：给写文件的方法在它们自己的事务里用。

测试：`common/src/test/java/mtr/media/MediaFoldersTest.java`。
