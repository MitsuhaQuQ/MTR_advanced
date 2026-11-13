# MediaIds.java（0.79.0 新增）

上传的图片和音频的稳定资源 ID 与曾用名（用户 2026-11-14：以资源 id 为准，文件名可变）。

- 每个文件一个 8 位 ID（小写字母和数字，去掉易混的 l、o、0、1），存在索引表的 `resource_id` 列；旧存档打开时自动补上，之后不变。
- 文件被「替换」成另一个名字时，旧名记进曾用名表（图片 `picture_old_names`、音频 `audio_old_names`，列 `name_key`、`name`、`resource_id`），仍属于这个文件：世界里按旧名引用的画、音箱、报站器照样找到它；别的文件不能再用这个名字，直到文件被删除。改回曾用名时，那条曾用名去掉，刚才的名字记为曾用名。

## `static <E> E resolve(reference, entries, name, id, oldNames)`
先按现在的名字（不分大小写）找，找不到再按曾用名找到 ID、再找到文件；都没有返回 null。服务端 `ImageServer.find`、`AudioServer.findEntry`，客户端 `ImportedImages.find`、`AudioCache.find` 都用它。

## 类 `Store`
- 构造：没有 `resource_id` 列就加上，给没有 ID 的行发 ID，建曾用名表。
- `idFor(nameKey)`：这个名字现有文件的 ID，没有就发一个新的。
- `oldNameOwner(nameKey)`：这个名字是哪个文件的曾用名，没有返回 null。
- `rename(id, oldName, newName)`：替换改名时调用。
- `forget(id)`：删除文件时释放它的曾用名（删除语句里直接按 `resource_id` 删）。
- `oldNames()`：每个 ID 的曾用名，按记下的先后。

## 其它
`NAME_USED`（`gui.mtr.media_name_used`）：名字被别的文件用过时的提示。`newId`、`isId`、`byOldName`。

测试：`common/src/test/java/mtr/media/MediaIdsTest.java`（解析、图片替换改名与改回、旧库补 ID、音频替换）；游戏内 `autotest/cases/U468.spec.ts`。
