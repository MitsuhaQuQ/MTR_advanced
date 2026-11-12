# BlockDirectNode.java（ANTE）

ANTE 原来的"直接节点"：节点自己设定所连轨道的角度。现在 MTR 自己的节点已经能以 0.1° 精度设任意角度并支持刷子，所以这个方块只为已有它的世界保留注册名 `mtrsteamloco:direct_node`，行为就是普通 MTR 火车节点：若删掉这个注册，世界里的这些方块会消失，MTR 会把连在上面的轨道一并移除。不再提供对应物品。在 `cn/zbx1425/mtrsteamloco/Main.java:72` 注册（`Main.init` 中 `registerBlock("direct_node", …)`）。

## 类 `BlockDirectNode`（extends `mtr.block.BlockNode`）

### 构造函数 `BlockDirectNode()`
- 作用：`super(TransportMode.TRAIN)`，即火车节点。其余行为全部继承 `BlockNode`。
- 调用者：`Main.BLOCK_DIRECT_NODE` 的注册对象工厂。
