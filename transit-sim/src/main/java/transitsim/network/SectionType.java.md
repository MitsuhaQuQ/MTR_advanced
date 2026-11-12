# SectionType.java

网络区段的用途。站台、停车线、折返轨各自成为单独的区段。被 `Network.Section`、`TrackSections.Section`、`NetworkBuilder`、`RailAttributes`、`LegRouter`（判断停车线、折返轨、站台）等使用。

## 枚举 SectionType
- `NORMAL`：普通轨道。
- `PLATFORM`：站台。
- `SIDING`：停车线（车厂）。
- `TURN_BACK`：折返轨（列车可在其末端任意方向离开）。

### `static SectionType fromName(String name)`
- 作用：读取保存的类型名。网络迁入组件之前保存的区段使用宿主轨道类型名：同名的为站台/停车线/折返轨，其余一律视为 `NORMAL`。
- 返回：对应枚举值，未知名称返回 `NORMAL`。副作用：无。
- 调用者：`Network.java:199`、`TrackSections.java:332`。
