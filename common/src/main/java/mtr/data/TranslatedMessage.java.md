# TranslatedMessage.java

在不知道玩家语言的地方（例如服务端）生成的消息，以"翻译键 + 参数"编码成一个字符串保存/传输，到显示的地方再翻译。用于音频文件校验错误、编辑器动作结果等（`AudioFiles`、`AudioServer`、`EditorAudio`、`EditorActions`）。与组件无关。

## interface TranslatedMessage

### 常量（编码格式）
- `START = '\u0001'`：编码消息的首字符标记。
- `SEPARATOR = "\u001f"`：键与各参数之间的分隔符。
- 格式：`START + key (+ SEPARATOR + String.valueOf(arg))*`。参数一律转成字符串。

### `static String encode(String key, Object... arguments)`
- 作用：把翻译键和参数编码成一个字符串，稍后用 `decode` 翻译。
- 返回：编码字符串。
- 主要调用者：`AudioFiles.java:114`、`AudioFiles.java:117`、`AudioFiles.java:130`。

### `static String decode(String message)`
- 作用：在当前一侧的语言下翻译消息；若不是 `encode` 生成的（不以 `START` 开头），原样返回。
- 返回：翻译后的文本；`null` 返回 `""`。
- 主要调用者：`AudioServer.java:313`、`EditorAudio.java:60`、`EditorAudio.java:86`。

### `static MutableComponent toComponent(String message)`
- 作用：把消息转为 `MutableComponent`：非编码消息为 `Text.literal`，编码消息拆分为 `Text.translatable(key, args...)`（`split(SEPARATOR, -1)` 保留空参数）。
- 主要调用者：`AudioServer.java:315`、本类 `decode`。

### `static String get(String key, Object... arguments)`
- 作用：直接翻译（用于客户端生成的消息），等价 `Text.translatable(key, arguments).getString()`。
- 主要调用者：`EditorActions.java:130`、`EditorActions.java:134`、`EditorActions.java:142`。
