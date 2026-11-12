# RawMesh.java（ANTE sowcerext）

客户端模型的一个网格（同一材质下的顶点与面），在 CPU 侧编辑（变换、去重、生成法线）后上传为 GPU 网格 `Mesh`。被 OBJ/CSV 模型加载器、`RawModel`/`ModelCluster`、轨道区块渲染、装饰物和脚本动态模型使用。本分支（含合并时的 ANTE 优化）的要点：`distinct` 用自写的开放寻址表 `DistinctList` 代替 HashMap（百万面模型）；`generateNormals` 只复制需要新法线的顶点；`compact` 在上传后把顶点/面列表换成紧凑数组并经 `CompactArrays` 跨网格共享；`copy` 保持紧凑形态以支持多线程复制预加载模型。

## 类 `RawMesh`

### 字段与常量
- `materialProp`（final）：材质属性（着色器、纹理、半透明等）。
- `vertices`（`List<Vertex>`）、`faces`（`List<Face>`）：编辑形态的数据；可能被多个网格共享，所以替换而不是清空。
- `compactVertices`（`float[]`，每顶点 8 个：位置 xyz、法线 xyz、u、v）、`compactFaces`（`int[]`，每面 3 个顶点下标）：`compact()` 之后的数据，之前为 null。
- `FLOATS_PER_VERTEX = 8`、`VERTICES_PER_FACE = 3`。

### 构造函数
- `RawMesh(MaterialProp materialProp)`：空网格。
- `RawMesh(DataInputStream dis) throws IOException`：读材质、顶点数与顶点、面数与面（`serializeTo` 的逆）。

### `void append(RawMesh nextMesh)`
- 把另一网格的顶点（同一对象）和面（复制并偏移下标）追加进来。与自身追加抛 `IllegalStateException`。

### `void appendTransformed(RawMesh nextMesh, Matrix4f mat, int color, int light)`
- 作用：顶点经 `mat` 变换位置和法线、设统一颜色与光照后追加，面复制并偏移。调用者：`cn/zbx1425/sowcerext/model/RawModel.java:88`、`cn/zbx1425/mtrsteamloco/render/rail/MeshBuildingRailChunk.java:93`。

### `void clear()`
- 清空顶点和面列表。

### `void validateVertIndex()`
- 面引用了越界顶点下标时抛 `IndexOutOfBoundsException`。调用者：`CsvModelLoader.java:42`、本类 `upload`。

### `void triangulate()`
- 把所有多边形面拆成三角形（`Face.triangulate`）。

### `void distinct()`
- 作用：去除重复顶点（按 `Vertex.equals`：位置、法线、uv）和重复面，就地改写面的下标；最后把结果写回原列表对象（列表可能被共享）。调用者：`cn/zbx1425/sowcerext/model/loader/ObjModelLoader.java:199`、`CsvModelLoader.java:233`、本类上传前。

### `void generateNormals()`
- 作用：对每个面（≥ 3 顶点）用前三个顶点叉积求单位法线；法线为零的顶点复制一份并赋予该法线（退化面用 (0,1,0)），已有法线的顶点原对象复用；每个面使用各自的新顶点下标，因此会产生重复顶点。
- 注意：已有法线的顶点会在多处共用同一对象，所以必须先 `distinct()` 再修改任何顶点。调用者：`ObjModelLoader.java:196`、`CsvModelLoader.java:232`。

## 私有内部类 `DistinctList<T>`
- 保持插入顺序、保留首个相等元素的去重表；槽数组只存"下标+1"（0 表示空），容量为不小于 `maxItems` 两倍的 2 的幂（至少 16），乘法哈希 `hash * 0x9E3779B9 >>> shift`，线性探测。
- `private int add(T item)`：返回该元素（或相等元素）在列表中的下标。

### `void upload(Mesh mesh, VertAttrMapping mapping)`
- 立即把数据写入已有 GPU 网格（`_uploadAsync(mapping).accept(mesh)`）。

### `Mesh upload(VertAttrMapping mapping)`
- 校验下标，新建顶点缓冲/索引缓冲/网格并上传，返回。须在渲染线程调用。调用者：`cn/zbx1425/mtrsteamloco/render/rail/RailRenderDispatcher.java:177`、`InstancedRailChunk.java:104`。

### `Supplier<Mesh> uploadAsync(VertAttrMapping mapping)`
- 作用：在当前线程准备字节数据，返回一个 Supplier，在渲染线程调用时才创建 GL 对象并上传。调用者：`cn/zbx1425/sowcerext/model/ModelCluster.java:72`、`MeshBuildingRailChunk.java:72`。

### `private Consumer<Mesh> _uploadAsync(VertAttrMapping mapping)`
- 作用：先 `distinct()`；按映射中各属性是否放在顶点缓冲（位置 3 float、颜色 int、纹理 uv 2 float、光照 int、法线 3 字节 ×127）和填充字节写入堆外顶点缓冲；写索引缓冲（每面 3 个 int）；返回只执行一次的上传函数（上传后释放堆外内存并设面数）。

### `private static int getVertBufPos(VertAttrMapping, int vertId, VertAttrType)`
- 计算某顶点某属性在缓冲中的偏移（当前只在注释掉的代码里用）。

### `private static boolean vecIsZero(Vector3f vec)`
- 三分量都为 0。

### 几何变换（作用于 `vertices`，需在 `compact` 之前调用）
- `applyMatrix(Matrix4f)`：位置与法线（3×3 部分）变换。
- `applyTranslation(float x, float y, float z)`：平移。
- `applyRotation(Vector3f axis, float angle)`：绕轴旋转 `angle` 度（位置与法线）。
- `applyScale(float x, float y, float z)`：缩放位置，按逆转置修正法线；缩放系数之积为负时翻转所有面。
- `applyMirror(boolean vx, vy, vz, nx, ny, nz)`：位置/法线按轴取反；位置取反轴数为奇数时翻转面。
- `applyUVMirror(boolean u, boolean v)`：`u → 1-u`、`v → 1-v`。调用者：`cn/zbx1425/mtrsteamloco/data/EyeCandyRegistry.java:195`、`RailModelRegistry.java:127`。
- `applyShear(Vector3f dir, Vector3f shear, float ratio)`：位置沿 `shear` 方向偏移 `ratio × (dir·pos)`，法线相应修正并归一化。调用者：`CsvModelLoader.java:139`。

### `void setRenderType(String type)`
- 作用：按 BVE 风格类型名设置材质：`exterior`（`rendertype_entity_cutout`）、`exteriortranslucent`（`rendertype_entity_translucent_cull`，半透明）、`interior`/`interiortranslucent`（同前但光照固定最亮 `15<<4 | 15<<20`）、`light`（`rendertype_beacon_beam`，cutoutHack）、`lighttranslucent`（beacon，半透明，不写深度）。会先复制 `attrState`。
- 异常：未知类型抛 `IllegalArgumentException`。调用者：`CsvModelLoader.java:186`、`RawMeshBuilder.java:22`。

### `void compact()`
- 作用：已紧凑或存在非三角形面时不做；否则把顶点/面转成数组，经 `CompactArrays.share` 共享，并把列表替换为新的空列表（不清空原列表，因可能被共享）。之后只有 `writeBlazeBuffer`（光影包需要）还会读取网格。颜色与光照不保留（上传后不再影响网格）。调用者：`cn/zbx1425/mtrsteamloco/render/integration/SowcerModelAgent.java:47`、`ModelCluster.java:121`、`TrainModelPreloader`（对预加载模型）。

### `boolean sharesCompactDataWith(RawMesh other)`（包级可见）
- 两网格是否共用同一紧凑数组。只被测试使用（`common/src/test/java/cn/zbx1425/sowcerext/model/RawMeshTest.java:181`）。

### `int getVertexCount()` / `int getFaceCount()`
- 分别按列表或紧凑数组返回数量。

### `double getRadiusSquared()`
- 所有顶点到原点距离平方的最大值（模型坐标）。调用者：`cn/zbx1425/mtrsteamloco/render/integration/DynamicTrainModelLoader.java:284`。

### `private Vertex getCompactVertex(int index)`
- 从紧凑数组构造一个新 `Vertex`（无颜色、光照）。

### `private void expand()`
- 把紧凑数组还原为列表并清空数组（仅 `serializeTo` 使用）。

### `void writeBlazeBuffer(FaceList vertexConsumer, Matrix4f matrix, int color, int light, int overlay, DrawContext drawContext)`
- 作用：逐个三角形把变换后的顶点交给 `vertexConsumer.addFace`（原版顶点消费者路径，用于光影包兼容），并在 `drawContext` 记录面数。紧凑与列表两种形态都支持。调用者：`ModelCluster.java:101`、`:114`。

### `RawMesh copy()`
- 深拷贝；紧凑网格的拷贝是列表形态的新对象，而原网格不展开、保持紧凑（预加载模型会被多个线程复制，原件需保持小）。

### `RawMesh copyForMaterialChanges()`
- 复制材质，顶点/面列表和紧凑数组共享引用（只改材质时用）。调用者：`cn/zbx1425/mtrsteamloco/scripting/util/client/DynamicModelHolder.java:16`。

### `void serializeTo(DataOutputStream dos) throws IOException`
- 先 `expand()`（网格因此离开紧凑形态），再写材质、顶点数与顶点、面数与面。调用者：`cn/zbx1425/sowcerext/model/RawModel.java:264`（`RawModel.serializeTo` 逐网格写出）。

### `void setMatixProcess(Function<Matrix4f, Matrix4f> matrixProcess)`
- 转给材质属性，设置绘制前对矩阵的处理函数。
