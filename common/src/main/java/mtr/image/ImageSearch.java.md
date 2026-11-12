# ImageSearch.java（本分支新增）

客户端画框图片界面中按名字搜索图片（0.65.28）：查询中的每个词（不区分大小写）都要出现在名字里，结果保持列表原顺序。被 `common/src/main/java/mtr/screen/ImportedPaintingScreen.java:92`、`:95` 使用；测试 `common/src/test/java/mtr/image/ImageSearchTest.java`。

## 类 `ImageSearch`（final，私有构造）

### `static List<Integer> filter(List<String> names, String query)`
- 作用：查询按空白拆词（转小写），返回所有匹配名字的下标；空查询返回全部下标。

### `static boolean matches(String name, String query)`
- 名字是否包含查询的所有词；没有词时为 true。

### `private static boolean matches(String name, String[] words)`
- 名字转小写后逐词 `contains`，空词忽略；`name` 为 null 视为空串。
