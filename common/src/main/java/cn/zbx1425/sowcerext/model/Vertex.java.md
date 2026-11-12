# Vertex.java（ANTE sowcerext）

客户端模型的单个顶点：位置、法线、纹理坐标、颜色、光照。被 `RawMesh`、`ObjModelLoader`、`CapturingVertexConsumer`（`common/src/main/java/cn/zbx1425/mtrsteamloco/render/integration/CapturingVertexConsumer.java:24`）、`EyeCandyRegistry`（`cn/zbx1425/mtrsteamloco/data/EyeCandyRegistry.java:254`）等使用。按代码注释，`hashCode` 刻意不分配对象（百万面模型的每个顶点都会调用）。

## 类 `Vertex`

### 字段
- `position`（`Vector3f`，模型坐标）、`normal`（`Vector3f`）、`u`、`v`（纹理坐标）、`color`（ARGB 整数）、`light`（打包光照）。

### 构造函数
- `Vertex()`：全部为默认值（位置、法线为 null）。
- `Vertex(Vector3f position)`：法线为零向量。
- `Vertex(Vector3f position, Vector3f normal)`。
- `Vertex(DataInputStream dis) throws IOException`：按 `serializeTo` 的顺序读 8 个 float（位置 xyz、法线 xyz、u、v）；颜色和光照不读。

### `boolean equals(Object o)`
- 比较 `u`、`v`、位置、法线（不比较颜色、光照）。

### `int hashCode()`
- 由位置、法线、u、v 组合，不分配对象。

### `Vertex copy()`
- 深拷贝（法线也复制）。

### `Vertex copyWithNormal(Vector3f newNormal)`
- 作用：复制位置、u、v、颜色、光照，法线直接使用 `newNormal`（不复制，调用者不应再持有它）。

### `void serializeTo(DataOutputStream dos) throws IOException`
- 写 8 个 float：位置 xyz、法线 xyz、u、v。
