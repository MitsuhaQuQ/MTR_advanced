# MediaPermissions.java（本分支新增）

服务端媒体（广播音频与导入图片）共用的删除权限规则：管理员（由调用方的服务器权限检查得出）可以删任何条目，否则只能删自己上传的。调用者：`common/src/main/java/mtr/audio/AudioServer.java:331`、`common/src/main/java/mtr/image/ImageServer.java:43`。

## 类 `MediaPermissions`（final，私有构造）

### `static boolean canDelete(String owner, String actor, boolean administrator)`
- 参数：`owner` 条目上传者 UUID 字符串；`actor` 操作者 UUID 字符串；`administrator` 操作者是否有管理权限。
- 返回：`administrator` 为真，或 `owner` 非空且等于 `actor`。
