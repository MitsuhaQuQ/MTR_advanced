# MediaTree.java（0.74.0）

上传文件的树：上传者 → 文件夹（可嵌套）→ 文件，给画的图片选择（`ImportedPaintingScreen`）一层层显示，和 `SoundTree` 一样的做法。

## 类 `MediaTree<T>`
- `root`：顶层（各上传者是它的子文件夹）。
- `add(uploaderName, folder, item)`：把文件放到上传者和文件夹下（没有名字的上传者记为 `?`）。
- `addFolder(uploaderName, folder)`：建空文件夹。
- `count()`：全部加完后统计每个文件夹（含下面所有层）的文件数。

## 类 `Node<T>`
- `name`、`parent`、`folders`（按名字不分大小写排序）、`items`、`total`。
- `folder(name)`：取或建子文件夹；`forEachItem`：这里和下面所有文件（先子文件夹）；`path()`："Steve / 1号线 / 上行"；`find(item)`：文件所在的文件夹。
