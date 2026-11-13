# PaintingProtection.java（0.78.2 新增）

带导入图片的画不让 `/kill` 删（用户 2026-11-14：`/kill @e` 会把世界里挂的图片全清掉）。

- `isProtected(entity)`：是实现了 `PaintingImage` 的画，且设置里的 `image` 不为空（用刷子清掉图片后设置还在、`image` 为空，不保护；设置读不出也不保护）。
- `withoutProtected(targets)`：去掉受保护的画；没有要去掉的就原样返回同一个集合。调用者：Fabric/Forge 的 `KillCommandMixin`。

玩家手动打掉、或用刷子清掉图片后仍可移除。测试：`common/src/test/java/mtr/image/PaintingProtectionTest.java`。
