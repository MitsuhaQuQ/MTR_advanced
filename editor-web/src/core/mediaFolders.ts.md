# core/mediaFolders.ts（0.74.0）

媒体库文件夹的规则，和游戏的 `mtr.media.MediaFolders` 一致：上传者名下的文件夹可嵌套，最多 8 层，空的保留；路径用 `/` 连，顶层为 `""`；文件只能在自己上传者的文件夹之间移动。

## 导出
- 常量 `MAX_DEPTH`、`MAX_NAME_LENGTH`、`DRAG_TYPE`（页面内拖动文件的数据类型）。
- 类型 `FolderRef`（`owner`、`path`）、`KeptFolder`、`FolderFile`（`uploader`、`uploaderName`、`folder`）、`FolderNode`（`owner`、`ownerName`、`path`、`name`、`children`、`files`（直接在里面的）、`total`（含下面所有层））。
- `parentOf`、`nameOf`、`childOf`、`depthOf`、`within`（不分大小写）、`compareNames`（和游戏一样按字符编码、不分大小写排序）、`sameFolder`。
- `nameProblem(name, siblings, parentDepth)`：`""` 或 `empty`、`long`、`character`（斜杠、反斜杠、控制字符）、`dots`、`deep`、`taken`（同一处已有同名，不分大小写）。
- `buildTree(files, folders, self)`：每个上传者一个根（路径 `""`），含保留的文件夹和文件所在的文件夹及其上级；自己的排最前，其余按名字。
- `findNode(roots, ref)`、`filesIn(files, ref, below)`（null 为全部；`below` 时含下面所有层）。
- `canDrop(files, target, canOrganise)`：都是目标所有者的文件、至少有一个不在目标里、当前玩家能整理目标所有者的文件。
- `uploadTargets(roots, self)`：自己能上传进的文件夹（顶层在前，按路径顺序）。

测试：`editor-web/test/mediaFolders.test.ts`。
