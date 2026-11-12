# EditTransferAssembler.java

把一次"逻辑编辑"的分片字节流按顺序拼回完整字节数组。客户端编辑（`ClientEditing`）把大的编辑负载切成 ≤24 KiB 的分片发送，`EditTransferPackets` 用本类组装；拼装未完成前不向编辑处理器暴露任何字节。纯数据结构，无组件对应物。

## final class EditTransferAssembler

### 常量
| 名称 | 值 | 含义 |
|---|---|---|
| `CHUNK_BYTES` | 24×1024 | 单个分片最大字节数 |
| `MAX_BYTES` | 1024×1024 | 一次传输总字节上限（1 MiB） |
| `TIMEOUT_MILLIS` | 30000 | 两个分片间隔超过此毫秒数则丢弃未完成缓冲 |

### 字段
- `buffer`：正在拼装的完整长度缓冲，`null` 表示空闲。
- `id`、`kind`：当前传输的 id 与种类（由调用方定义）。
- `received`：已收到的字节数（也是下一个分片应有的 offset）。
- `updated`：最近一次收到分片的时间（毫秒）。

### `public byte[] accept(long transferId, int transferKind, int total, int offset, byte[] bytes, long now)`
- 作用：接收一个分片。`offset == 0` 时开始新的传输（丢弃任何旧缓冲）；其余分片必须与当前 id/kind/total 一致且 `offset == received`（严格顺序）。
- 参数：`transferId` 传输 id；`transferKind` 种类；`total` 总字节数（1…`MAX_BYTES`）；`offset` 本片起始位置；`bytes` 本片内容（1…`CHUNK_BYTES` 字节）；`now` 当前时间毫秒。
- 返回：传输完成时返回完整字节数组（并清空内部状态）；未完成返回 `null`。
- 副作用：更新 `buffer/id/kind/received/updated`；若缓冲已超时先 `clear()`。
- 异常：大小非法（total 越界、分片空或过大、offset 越界）时 `clear()` 后抛 `IllegalArgumentException("Invalid edit fragment size")`；乱序或与当前传输不符时 `clear()` 后抛 `IllegalArgumentException("Out-of-order edit fragment")`。
- 主要调用者：`EditTransferPackets.java:25`、`EditTransferPackets.java:33`、`EditTransferPackets.java:53`。

### `public boolean expired(long now)`
- 作用：是否空闲或已超时（`buffer == null` 或距上次分片超过 `TIMEOUT_MILLIS`）。
- 主要调用者：`EditTransferPackets.java:44`。

### `public void clear()`
- 作用：丢弃缓冲，`buffer = null; received = 0`。
- 主要调用者：本类 `accept` 内部；`EditTransferPackets` 中的清理路径（行号未逐一确认）。
