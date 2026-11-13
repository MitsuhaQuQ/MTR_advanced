# views/MediaFolders.tsx（0.74.0）

媒体库（音频和图片共用）的文件夹树和上传目标确认。规则在 `core/mediaFolders.ts`，动作由库组件发送。

## 导出
### 类型 `FolderAction`
- `{ op: "create" | "rename" | "delete" | "move", owner, path, name?, folder?, keys? }`，库组件加上 `action: "folder"` 发给 `api/audio` 或 `api/images`。

### `FolderTree(props)`
- `roots`、`current`（null 为全部文件）、`onSelect`、`canOrganise(owner)`、`dragging`（列表里正在拖的文件）、`onAction(action, label)`。
- 第一行"全部文件"，然后各上传者及其文件夹（缩进，右边是文件数）。拖动的文件能放进的文件夹（`canDrop`）接受拖放，放下发 move。
- 选中的文件夹能整理时：新建文件夹（输入名字，页面里先查 `nameProblem`）、改名（改后选中新路径）、删除文件夹（`ConfirmButton`，说明里面的东西上移一层、不删文件，删后选中上一层）。

### `UploadTarget(props)`
- `files`、`targets`（`uploadTargets`）、`value`、`onChange`、`onConfirm`、`onCancel`：上传前的"上传到…"一栏（`data-upload-target`），可选自己的任一文件夹或顶层。

测试：`editor-web/test/mediaLibraryFolders.test.tsx`。
