# KillCommandMixin.java（0.78.2 新增）

`/kill` 的 `KillCommand.kill(源, 目标)` 入口处把目标集合换成 `PaintingProtection.withoutProtected(目标)`：带导入图片的画不会被 `/kill @e` 之类删掉，其余实体照旧；提示里的数量也按去掉后的算。玩家手动打掉画、或先用刷子清掉图片，仍可移除。登记在 `mtr.mixins.json` 的通用 `mixins` 里（服务端生效）。Fabric 与 Forge 两份相同。
