# 本分支加载 MTR 4 资源包：现状与转换设计

- **对照版本**：本分支 `ante-merge`（3.2.2-hotfix-2 合并 ANTE，`1540a85`）；4.x = `P:\MTR4` 的 `4.0.6` 分支（`240524a1`）。
- **方法**：静态读代码。读了 4.x 的资源 schema（`buildSrc/src/main/resources/schema/resource`）、资源类（`org.mtr.mod.resource`）和反方向的转换器 `LegacyVehicleResource`，本分支的 `CustomResources`、`DynamicTrainModel`、`ResourcePackCreatorProperties`，以及 ANTE 的 `CustomResourcesMixin`、`DynamicTrainModelLoader`。
- **未做**：没有构建，没有进游戏，**手上没有真实的 4.x 资源包**（`P:\MTR4\examples` 里的样例包是 3.x 格式）。下文的"可用""丢失"都是根据代码推断的，第 6 节列出要拿真实资源包核对的项。
- **对照文档**：`P:\MTR4\MTR4-旧版资源兼容设计.md` 是反方向（4.x 读 3.x）的设计，本文的格式和它对应。

## 1. 结论

能做，而且不碰运行核心：只改客户端的资源加载层，再给渲染器加几处小扩展，不改协议、不改存档、不改 `Train` 和 `Siding`。

做法是**转换，不是移植**：
- 4.x 的资源代码约 5000 行，依赖它的映射层 `org.mtr.mapping`、核心的序列化器、构建时生成的 schema 类和它自己的渲染器，整体搬过来等于重写渲染。
- 本分支已有两条能渲染自定义列车的路：MTR 3 的 `DynamicTrainModel`（`.bbmodel` 加 `model_properties`），和 ANTE 扩展出来的 OBJ 路径（同一套 `model_properties`，模型可以是 `.obj`，还能用 `|` 把几个模型按车厢号拼在一列车里）。
- 4.x 的部件规则 `modelProperties` 就是 3.x `model_properties` 的后继，两边字段大多一一对应。4.x 自己的旧版转换器 `LegacyVehicleResource` 把 3.x 转成 4.x，**把它反过来**就是本文的映射表。

所以在读取 `mtr_custom_resources.json` 时识别 4.x 格式，把每个 4.x 条目转成 3.x 的 `custom_trains`、`custom_signs` 条目（部件规则在内存里合成），再交给现有流程。

**唯一躲不开核心的**：同一列车里车厢长度不同（G1）。

## 2. 现状

### 2.1 两边的格式

| 项目 | 3.x（本分支） | 4.x |
|---|---|---|
| 入口文件 | `assets/mtr/mtr_custom_resources.json` | **同名同路径** |
| 顶层 | `custom_trains`（对象，键是 ID）、`custom_signs` | `vehicles`、`signs`、`rails`、`objects`、`lifts`（都是数组，条目带 `id`） |
| 一个条目是 | 一种**车型**：所有车厢用同一个模型，头尾差别靠部件的 `whitelisted_cars`、`blacklisted_cars` | 一节**车厢**：自己的长度、宽度、转向架位置、贯通道、屏障、声音；编组时在车厂里把不同车厢拼起来 |
| 车长和车宽 | 整数，写在 `model_properties` 的 `length`、`width`，或基础车型 ID `train_19_2` 里；车距 = 长度 + 1 | 小数 `length`、`width`，另有 `couplingPadding1`、`couplingPadding2`；4.x 的旧版转换器用"4.x 长度 = 3.x 长度 + 1" |
| 模型 | 一个 `model`。ANTE 允许 `.obj`，以及 `a.obj|白名单;黑名单;reversed|b.obj|…` 的多模型写法（**只对 OBJ**） | `models[]`，每个是 `modelResource`（`.bbmodel`、`.obj`、`.mqo`、`.mqoz`）加 `textureResource`、`modelPropertiesResource`、`positionDefinitionsResource`、`flipTextureV` |
| 贴图 | 每个车型一个 `texture_id` | 每个模型各自一个 |
| 部件位置 | 部件里直接写 `positions: [[x, z], …]`，单位是像素（1/16 格），没有 y；`mirror` 表示转 180° | 单独的 `positionDefinitions` 文件：每个定义有 `positions[{x, y, z}]` 和 `positionsFlipped[…]`，部件按名字引用 |
| 转向架 | 用基础车型的默认转向架 | `bogie1Position`、`bogie2Position`，加自定义的 `bogie1Models`、`bogie2Models` |
| 贯通道、屏障 | `gangway_connection_id`、`train_barrier_id` 是贴图前缀，后缀固定（`_side`、`_roof`、`_floor`、`_exterior`），尺寸固定 | 每个面单独的贴图，宽、高、偏移都可调；`hasGangway1/2`、`hasBarrier1/2` 分两端 |
| 声音 | `bve_sound_base_id`，或旧式的 `speed_sound_base_id` 等 | `bveSoundBaseResource`，或 `legacy*` 字段 |

### 2.2 读取流程

- 本分支的 `CustomResources.reload` 读所有资源包里的 `mtr:mtr_custom_resources.json`，只认 `custom_trains`、`custom_signs`。4.x 格式的文件里没有这两个键，现在**整份被静默忽略**，没有任何提示。
- ANTE 的 `CustomResourcesMixin` 接管了 `readResource`：`.obj` 或带 `|` 的路径走 OBJ 加载器 `DynamicTrainModelLoader.loadObjInto`，其余照常读 JSON。转换出来的 `model_properties` 要通过这里拿到（4.2 节）。
- 服务端只存车型 ID 字符串和基础车型（如 `train_19_2`），车距从基础车型解析。所以客户端怎么画都不影响服务端，只要换算出的整数长度和宽度对。

## 3. 缺口清单

按"不改核心能不能做"分组。每项写明转换后会怎样。

### 只能记下、不改核心做不了

- **G1：车厢长度不同的混编**。4.x 的头车可以比中间车长；3.x 一列车所有车厢等长，车距写在 `Train`、`Siding` 里。转换时，长度换算后不相等的车厢**不拼编组**，只各自生成单车型（4.4 节）。要真正支持，得等以后车厢模型进组件时一起改（解耦文档 S2 以后）。
- **G2：非整数车长和车宽**。3.x 只认整数。换算后四舍五入，差的部分表现为车厢之间多出或少了一点缝。报告里写出原值和取整值。

### 有损，但能转

- **G3：部件条件**。`AT_DEPOT`、`CHRISTMAS_LIGHT_*` 在 3.x 没有对应。`AT_DEPOT` 的部件不画；圣诞灯部件当作常亮（`ALL`），闪烁丢失。
- **G4：门**。4.x 每个部件有自己的 `doorXMultiplier`、`doorZMultiplier`、`doorAnimationType`，还有开关门时间窗 `renderFrom/Until*DoorTime`。3.x 是每个部件一个 `door_offset` 枚举，整车一个 `door_max` 和一个 `door_animation_type`。换算见 4.3 节；各部件的门幅或动画类型不一致时取出现最多的，其余写进报告；时间窗丢失。`PLUG_SLOW_2`、`PLUG_SLOW_3` 在 3.x 没有，映射成 `PLUG_SLOW`。
- **G5：显示屏**。`DEPARTURE_INDEX`、`ROUTE_COLOR`、`ROUTE_COLOR_ROUNDED` 没有对应，这些部件不画。选项 `SPACE_CJK`、`SCROLL_LIGHT_RAIL`、`SEVEN_SEGMENT` 和字段 `displayMaxLineHeight`、`displayPadZeros`、`displayDefaultText` 丢失。
- **G6：转向架模型**。能做，只改渲染，见 4.8 节。分两级：先跟车体固定（和 3.x 内置转向架一样），再做沿轨道转向。车体仍按 3.x 的方式由两端在轨道上的点定位（4.x 用两个转向架的点），所以弯道上车体和转向架的相对位置和 4.x 略有不同。
- **G7：贯通道和屏障**。4.x 的各面贴图名如果是同一个前缀加 3.x 的固定后缀，就换算成 `gangway_connection_id`、`train_barrier_id`，否则不画；4.x 自定义的尺寸和偏移丢失。两端分开的开关（`hasGangway1/2`）只能合成"有或没有"。
- **G8：一个车厢多个模型、贴图不同**。OBJ 可以用 ANTE 的 `|` 写法拼（OBJ 自带材质）。`.bbmodel` 只能把几个模型合并成一个（4.5 节），要求贴图相同，否则只用第一个模型，写进报告。
- **G9：部件高度**。3.x 的位置只有 x、z。不扩展的话 y 丢失，挂在车顶的显示屏、灯会掉到地板高度。所以 P1 就给渲染器加上可选的 y（5.2 节）。
- **G10：模型整体高度**。4.x 有 `modelYOffset`；它的旧版转换器给 3.x 模型填 1，说明 3.x 模型相当于 4.x 的偏移 1。换算时把 `(modelYOffset − 1) × 16` 像素加到每个位置的 y 上（依赖 G9 的扩展）。方向要用真实资源包核对。
- **G11：碰撞部件**。`FLOOR`、`DOORWAY`、`SEAT` 在 4.x 只用于乘客行走和座位，不渲染（`ModelPropertiesPart` 只把它们加进碰撞框）。3.x 乘车按整节车厢算，这些部件直接丢掉，没有影响。

### 引用不到的资源

- **G12：引用 4.x 内置资源**。4.x 资源包可能直接引用 4.x jar 里的模型、贴图、声音（`mtr:models/vehicle/…` 等），本分支没有这些文件。转换时检查每个引用的资源是否存在，缺的写进报告，这个车型不注册。
- **G13：MQO 模型**。本分支不认 `.mqo`、`.mqoz`。4.x 的 `MqoModelConverter`（546 行，只依赖 commons-lang 和 fastutil）可以移植过来，转成 OBJ 文本再交给 ANTE 的 OBJ 加载器（P3）。
- **G14：电梯**。3.x 的电梯样式是固定的，`lifts` 不转换。

### 现在就有的问题

- **G15：错误不可见**。4.x 格式的文件现在被静默忽略。转换层的每一项丢失和失败都要进报告（4.6 节）。

## 4. 设计

所有新类放在客户端包 `mtr.client.mtr4`，只依赖 Gson 和本分支已有的类。转换本身是"JSON 进、JSON 出"的纯函数，不碰 Minecraft，可以单元测试。

### 4.1 识别格式

`CustomResources.reload` 读每份 `mtr_custom_resources.json` 时：
- 有 `custom_trains` 或 `custom_signs`：照旧按 3.x 处理；
- 有 `vehicles`、`signs`、`rails`、`objects`、`lifts` 中任何一个数组：交给 `Mtr4Converter`，把结果并入同一轮的 `custom_trains`、`custom_signs`；
- 两种键都有：两边都处理；
- 都没有：写一条报告，不再静默。

4.x 条目转出来的 ID 加前缀 `mtr4_`，避免和 3.x 车型重名。车型的完整 ID 是 `mtr_custom_train_mtr4_<4.x id>`。

### 4.2 合成资源

3.x 的车型条目通过路径引用 `model` 和 `model_properties`。转换出来的部件规则和合并后的模型不是真实文件，所以加一个内存表 `VirtualResources`：路径（`mtr4virtual:<id>/properties.json` 之类）→ 合成的 `JsonObject`。

ANTE 的 `CustomResourcesMixin.readResource` 在 HEAD 接管了读取，所以查表要放在这个注入的最前面：命中就直接回调合成的对象，不去资源管理器找。每次重载开始时清空这张表。

### 4.3 车厢：`Mtr4VehicleConverter`

每个 4.x `vehicle` 生成一个 3.x 车型条目：

| 3.x 字段 | 来自 4.x |
|---|---|
| `name`、`color`、`description`、`wikipedia_article` | 同名字段 |
| `model` | `models[]`：一个 `.bbmodel` 直接用；`.obj` 一个或多个时用 ANTE 的 `a.obj|;;|b.obj|;;` 写法；多个 `.bbmodel` 见 4.5 节 |
| `texture_id`、`flipV` | 第一个模型的 `textureResource`、`flipTextureV` |
| `model_properties` | 合成：`transport_mode` = `transportMode`；`length` = round(`length`) − 1；`width` = round(`width`)；`door_max` 和 `parts` 见下 |
| `gangway_connection_id`、`train_barrier_id` | G7 |
| `rider_offset` | `legacyRiderOffset` |
| `bve_sound_base_id` | `bveSoundBaseResource` |
| `speed_sound_base_id`、`speed_sound_count`、`accel_sound_at_coast`、`const_playback_speed`、`door_sound_base_id`、`door_close_sound_time` | 对应的 `legacy*` 字段 |

**部件**：读模型的 `modelPropertiesResource` 和 `positionDefinitionsResource`。4.x 每个部件的每个名字、每个位置定义各生成 3.x 部件：

- `name` = 部件名；`stage` = `renderStage`（`LIGHT` → `LIGHTS`，`ALWAYS_ON_LIGHT` → `ALWAYS_ON_LIGHTS`，其余同名）。
- `positions` = 定义的 `positions`，每个写成 `[x, z, y]`（y 见 G9、G10）；`positionsFlipped` 不为空时，再生成一个同样的部件，`mirror: true`，位置用 `positionsFlipped`。
- `render_condition`：`NORMAL` → `ALL`，`DOORS_OPENED` → `DOORS_OPEN`，`DOORS_CLOSED` → `DOORS_CLOSED`，`ON_ROUTE_FORWARDS` → `MOVING_FORWARDS`，`ON_ROUTE_BACKWARDS` → `MOVING_BACKWARDS`，其余见 G3。
- 门：
  - `doorZMultiplier` 为 0 时没有门。不为 0 时，`door_max` = |`doorZMultiplier`|。OBJ 模型的 z 方向和 `.bbmodel` 相反，先乘 −1（和 4.x 旧版转换器的 `isObj ? -1 : 1` 对应）。
  - `door_offset`：x > 0 是 `LEFT`，x < 0 是 `RIGHT`（`mirror: true` 的部件反过来）；z > 0 是 `POSITIVE`，z < 0 是 `NEGATIVE`，拼成 `LEFT_POSITIVE` 等。
  - `door_animation_type`：取各部件出现最多的（G4）。
- `type: DISPLAY` 的部件加 `display` 对象：
  - `x_padding`、`y_padding`、`cjk_size_ratio`、`color`、`color_cjk` 同名换算；
  - `type`：`NEXT_STATION` → `NEXT_STATION_PLAIN`，其余同名，没有对应的见 G5；
  - `displayOptions` 里的 `SCROLL_NORMAL`、`UPPER_CASE`、`SINGLE_LINE` 换成 `should_scroll`、`force_upper_case`、`force_single_line`。
- `FLOOR`、`DOORWAY`、`SEAT` 不生成（G11）。

这些对应关系都从 `LegacyVehicleResource` 反推，每条在测试里各有一个用例。

### 4.4 编组：`Mtr4Formations`

单车型（每节都是同一个 4.x 车厢）总是生成。除此之外，再按下面的规则把一套车拼成一个 3.x 车型：

- **自动识别**：ID 去掉结尾的 `_cab_1`、`_cab_2`、`_cab_3`、`_trailer` 后相同，且换算后的长度和宽度都相等，就算一套。`cab_1` 放第 1 节（白名单 `1`），`cab_2` 放最后一节（白名单 `-1`），`trailer` 放中间（黑名单 `1,-1`）。后缀规则要拿真实资源包核对（第 6 节）。
- **手动指定**：本分支自己的扩展文件 `assets/mtr/mtr4_formations.json`，写 `{id, name, head, middle, tail}`，引用 4.x 车厢 ID。资源包作者或玩家都可以加。
- **怎么拼**：OBJ 用 ANTE 的 `|` 写法，每个模型带白名单和黑名单；`.bbmodel` 按 4.5 节合并，每个部件带上对应的 `whitelisted_cars`、`blacklisted_cars`。
- 长度不等的不拼（G1），写进报告。

### 4.5 合并 `.bbmodel`

一节车厢有多个 `.bbmodel`，或编组要把头车和中间车拼起来时，把几个模型合成一个：`elements` 和 `outliner` 拼接，元素的 `uuid` 和组名加上来源前缀，部件名跟着改；分辨率不同或贴图不同就不合并（G8）。合成的模型放进 `VirtualResources`。

### 4.6 报告：`Mtr4Report`

- 每个资源包一段，列出：转成了哪些车型、哪些条目没转、每个有损字段（字段名、原值、处理方式）、缺失的资源路径。
- 写进日志，并加到 ANTE 已有的加载错误列表 `MtrModelRegistryUtil.loadingErrorList`，重载结束时用它的错误界面弹出。只有"没转成"的条目才进错误界面，有损的只写日志，免得每次重载都弹窗。

### 4.7 标牌、装饰物、轨道模型

- **标牌**：`signs` 换成 `custom_signs`：`textureResource` → `texture_id`，`flipTexture` → `flip_texture`，`customText` → `custom_text`，`flipCustomText` → `flip_custom_text`，`small` 同名，`backgroundColor` → `background_color`（两边的颜色写法要核对）。
- **装饰物** `objects`、**轨道模型** `rails`：换成 ANTE 的装饰物（`EyeCandyRegistry`）和轨道模型（`RailModelRegistry`）定义。两边都是"模型 + 贴图 + 平移、旋转、缩放"，字段要在 P4 逐个对照。

### 4.8 转向架

**现状**：3.x 内置转向架在 `JonModelTrainRenderer.renderCar` 里，用车体的矩阵画在 ±`bogiePosition` 处（铰接转向架只画在首尾），所以弯道上跟着车体转，不跟着轨道转。3.x 自定义车型没有自己的转向架模型，`TrainClientRegistry.register` 只接收基础车型的 `bogiePosition`。

**一级：跟车体固定**。
- 4.x 的 `bogie1Models`、`bogie2Models` 和车厢模型一样，转成 3.x 的模型和部件规则，各成一个 `DynamicTrainModel`（OBJ 走 ANTE 的路径）。
- 车型注册时带上这两个模型和 `bogie1Position`、`bogie2Position`（4.x 两端可以不对称，3.x 只有一个 `bogiePosition`，所以位置也要分开存）。`TrainProperties` 加这几个字段，附属模组读的已有字段不变。
- `renderCar` 里，有自定义转向架就用车体矩阵平移到各自位置画它，没有才画内置的 `ModelBogie`。
- 效果和 3.x 内置转向架相同。

**二级：沿轨道转向**。
- `Train` 加一个只读查询：给出车厢号和沿车身的偏移，返回轨道上那一点的位置。它和现有的私有方法 `getRoutePosition` 用同一套路径和里程，只是偏移可以是任意值，**不改任何推进规则和状态**。
- 渲染每节车时，取两个转向架中心前后各一小段的两个轨道点，算出转向架的位置、偏航和俯仰；转向架用自己的世界矩阵画，不再挂在车体矩阵下。
- 每节车每帧多 4 次路径查询（按里程二分查找），开销很小。远处的车（超出细节半径）退回一级，不做查询。
- 3.x 内置的 `ModelBogie` 也可以走这条路，用客户端设置开关，默认关，避免改变现有资源包的外观。
- 乘车时车体的位置和玩家偏移照旧由车体决定，不受影响。

**和 4.x 的差别**：4.x 车体由两个转向架在轨道上的点定位，3.x 由车厢两端的点定位。直线上一样；弯道上 3.x 车体中部相对轨道的偏移略不同，转向架仍在轨上。要完全一样，得改车体定位，那会影响乘车位置和车门检测，不在本设计范围内。

## 5. 要改的地方

### 5.1 新增（客户端，`mtr.client.mtr4`）

- `Mtr4Converter`：入口，识别格式，分派。
- `Mtr4VehicleConverter`、`Mtr4Formations`、`Mtr4ModelMerger`（4.5 节）、`Mtr4Report`。
- `VirtualResources`：合成资源表。
- P3：`MqoToObj`（从 4.x 的 `MqoModelConverter` 移植，MIT 协议，保留出处）。

### 5.2 修改

- `CustomResources.reload`：按 4.1 节识别格式。
- ANTE 的 `CustomResourcesMixin.readResource`：最前面查 `VirtualResources`。
- **部件位置的 y**（G9）：`DynamicTrainModel` 读 `positions` 时，接受可选的第三个值作为 y（像素），没有时是 0，所以 3.x 资源包行为不变。ANTE 的 OBJ 路径也读这些部件位置，两处都要改。
- P5：`TrainProperties` 加自定义转向架模型和两端位置；`JonModelTrainRenderer.renderCar` 画自定义转向架；`Train` 加只读的轨道位置查询（4.8 节）。
- 不改：协议、存档、服务端、`Train` 和 `Siding` 的推进规则和状态。

## 6. 实施顺序

| 步 | 内容 | 完成标准 |
|---|---|---|
| P0 | 找 2 到 3 个真实的 4.x 资源包：一个 `.bbmodel` 的、一个 `.obj` 的，最好再有一个 `.mqo` 的。核对 G10 的方向、4.4 节的后缀规则、声音 ID 和颜色的写法 | 文本里"要核对"的项都有结论，写回本文 |
| P1 | 识别格式和报告（G15）；标牌；单个 `.bbmodel` 车厢；部件、条件、门、显示屏的换算；部件位置的 y；声音 | 转换器单元测试通过；`.bbmodel` 资源包的车厢能选、能开、门和显示屏正确；3.x 资源包行为不变 |
| P2 | `.obj` 车厢、一节多个模型；编组（自动识别和 `mtr4_formations.json`）；`.bbmodel` 合并 | `.obj` 资源包能用；一套头、中、尾车能拼成一个车型 |
| P3 | MQO（G13） | `.mqo` 资源包能用 |
| P4 | 装饰物、轨道模型 | 能放置、能显示 |
| P5 | 转向架：一级跟车体固定，二级沿轨道转向（4.8 节） | 4.x 车厢显示自己的转向架；开二级后弯道上转向架在轨上、跟着轨道转；关掉时 3.x 资源包外观不变 |
| 以后 | 分面贯通道（G7）；长度不同的混编（G1）等车厢模型进组件 | 另议 |

这一项只动客户端资源层，不受运行规则冻结的限制，可以和 T79 至 T89 的实测并行。

## 7. 验证

- **单元测试**（common，纯 JSON）：
  - 4.3 节每条对应关系一个用例（条件、门的四个方向和 OBJ 取反、显示屏类型和选项、`positionsFlipped` 变成镜像部件、`FLOOR` 等被丢掉）；
  - 长度和宽度取整；
  - 编组：后缀识别、长度不等不拼、手动文件；
  - `.bbmodel` 合并后 `uuid` 不重复、部件名对应；
  - 3.x 格式的文件原样通过；
  - 缺资源、坏 JSON 只进报告，不抛到外面。
- **游戏内**：P0 找到的资源包逐个加载，检查车型列表、外观、开关门、显示屏、灯（前进、后退）、声音；同时加载 3.x 资源包，确认不受影响；打开报告界面，确认能看出哪些丢了。
- 每次构建照旧跑 ABI 扫描（这一项不改公开类的字段，扫描只是保险）。

## 8. 不在本设计范围内

- 车厢长度不同的混编（G1），要改车厢模型。
- 4.x 的电梯样式（G14）。
- 4.x 的 PIDS、线路图等其他资源类型（4.x 资源 schema 里没有，属于它的模组内置功能）。
- 把 4.x 的渲染器搬过来。

## 9. 实测资源包：botamochi129 Rail Structure Pack v1.5（标注 `_mtr4`，2026-11-03）

**结论：这个包不是 4.x 核心格式，而是 NTE/ANTE 格式的装饰物和轨道包，脚本按 MTR 3 + ANTE 的 API 写。** 本分支已合并 ANTE，绝大部分内容不用转换就能加载；找到两处脚本沙箱的问题，已改（未构建，条目见 `MTR-待验证.md`）。第 6 节 P0 要的"真实 4.x 车辆资源包"仍然没有，这个包不能替代。

### 9.1 包里有什么

| 内容 | 数量 | 本分支的处理 |
|---|---|---|
| `assets/mtrsteamloco/rails/botamochi129_rail.json`：轨道模型（桥、高架、隧道、路基、墙、屋顶、柱） | 109 个，字段只有 `name`、`model`、`repeatInterval`、`yOffset`、`flipV` | `RailModelRegistry` 原样支持 |
| `assets/mtrsteamloco/eyecandies/botamochi129_eyecandies.json`：装饰物 | 17 个，字段 `name`、`model`、`textureId`、`flipV`、`rotation`、`asPlatform`、`scriptFiles` | `EyeCandyRegistry` 原样支持 |
| OBJ + MTL + PNG 模型 | 约 120 个 | ANTE 的 OBJ 加载器 |
| 10 个 JS 脚本（发车表、到站牌、发车铃、站台门、坡道、上野乘车引导牌） | 3139 行 | 见 9.2 |
| `sounds.json` + 3 个 ogg、3 个 ttf 字体 | — | 原版声音系统；`Resources.readFont` |

没有 `assets/mtr/mtr_custom_resources.json`，所以第 4 节的转换器和这个包无关。

### 9.2 脚本用到的 API

逐个核对了脚本里的全局对象和方法，以下都在本分支存在：`Resources`（`id`、`manager`、`readFont`、`readBufferedImage`、`ANTE_FLAG`）、`MTRClientData`（`STATIONS`、`ROUTES`、`TRAINS`、`SCHEDULES_FOR_PLATFORM`、`DATA_CACHE.requestStationIdToPlatforms`）、`MinecraftClient.getStationAt/setScreen`、`IScreen.ClothConfig2`、`ConfigResponder.TextField/CycleToggle`、`TickableSound`、`SoundHelper`、`ModelManager.loadRawModel/loadPartedRawModel`、`DynamicModelHolder`、`TextUtil`、`ComponentUtil`、`RateLimit`、`ClusterDrawCall`、`Matrix4f`、`Vector3f`，以及方块实体的 `doorValue`、`doorTarget`、`setShape`、`setCollisionShape`、`registerCustomConfig`、`getCustomConfig`、`getWorldPosVector3f`。

用的数据结构全是 3.x 的：`ScheduleEntry.arrivalMillis`、`TrainClient.path` 和 `getThisRoute()`、`Route.platformIds.get(i).platformId`、`getDestination(int)`、`getLastPlatformId()`、`platform.dwellTime`（Graal 的 nashorn 兼容模式把它映射到 getter）。在 4.x 上这些都不存在，所以文件名里的 `mtr4` 不代表 4.x 格式。

### 9.3 找到并已改的问题

- **S1：`java.lang` 被沙箱挡住**。`ScriptHolderBase` 在脚本加载完后只放行 `java.awt`、`java.util`、`mtr` 开头的类。发车表和到站牌在 `create` 和定时任务里用 `new java.lang.Runnable({...})`、`new java.lang.Long(id)`，加载后查找被拒，`create` 报错，牌子不显示。改为额外放行单个类：`Runnable`、`Iterable`、`Comparable`、`CharSequence`、`Number`、各包装类型、`String`、`StringBuilder`、`Math`。`System`、`Runtime`、`Thread`、`Class` 等仍然关闭。
- **S2：脚本自建线程池并发进入 JS 上下文**。发车表和到站牌在 `Resources.ANTE_FLAG` 为真时用 `Executors.newScheduledThreadPool(1)` 每 0.5 秒在自己的线程里调用 `tick`。GraalJS 不允许两个线程同时进入同一个上下文，和脚本线程撞上时抛 `IllegalStateException`，`scheduleAtFixedRate` 遇到异常就不再执行，牌子停止更新；重载资源后旧线程池也一直在跑。改为注入 `ScriptExecutors` 顶替脚本里的 `Executors`（定义了同名全局变量，`importPackage` 不再生效）：定时照旧，每次执行交给脚本线程并等它跑完；任务出错只记日志，不停止定时；线程是守护线程；`resetRunner`（资源重载、F3+5）时关掉所有这类线程池。

### 9.4 有意不改的

- **网络**：发车表每 30 秒用 `java.net.URL` 取延误信息（包里地址是空的 `http://`）。`java.net`、`java.io` 不放行，`new URL` 抛错被脚本自己的 `try` 接住，每 30 秒打一行日志，延误信息为空。资源包不应能访问网络，保持关闭。
- **`setDelay(1.5)`**：发车铃给 `TickableSound.setDelay(int)` 传小数。本分支的类型映射把 1.5 四舍五入成 2（单位是 tick），不报错。
- **显式写全名的 `java.util.concurrent.Executors`** 不经过 `ScriptExecutors`，仍是原来的线程池，会有 S2 的问题。这个包没有这样写。
- **加载阶段放行所有类**（`loading[0]`）：脚本顶层代码在加载时能拿到任何类，这是 ANTE 原有的行为，不在本次范围，另记。

### 9.5 游戏内要验证的

见 `MTR-待验证.md` 的"ANTE 脚本沙箱：资源包定时器和 java.lang"条目。

## 10. 转向架（4.8 节的 P5）已实现，未构建（2026-11-04）

没有 4.x 资源包，先把 4.8 节两级都做了，入口是本分支 `custom_trains` 的扩展字段，以后 4.x 转换器直接生成这些字段即可。条目见 `MTR-待验证.md`。

### 10.1 资源包写法

`custom_trains` 的条目可加 `bogie1`、`bogie2` 两个对象（对应 4.x 的 `bogie1Position`/`bogie1Models` 等）：

```json
"bogie1": {
  "model": "mtr:custom/bogie.bbmodel",
  "model_properties": "mtr:custom/bogie_properties.json",
  "texture_id": "mtr:custom/bogie",
  "flipV": false,
  "position": -7.5
}
```

- `position`：沿车身的位置，单位格，车模自己的坐标（与部件位置同向，只是单位是格不是像素）。4.x 惯例是 `bogie1` 为负、`bogie2` 为正；正负对应哪一端**要用真实 4.x 资源包核对**。
- `model` 可以是 `.bbmodel`，也可以是 `.obj` 或 ANTE 的 `|` 写法，走车体同一条加载路径；必须同时给 `model_properties`（部件、条件和车体一样处理，门恒为关）。
- `texture_id`、`flipV` 不写时用车体的贴图。
- 只写 `position` 不写 `model`：在该位置画内置转向架（可做不对称位置）。
- 只写其中一个也可以；写了任何一个，就不再画内置转向架。
- 基础车型（`base_train_type`）是带自定义转向架的车型时，没写的那个继承过去。
- 铰接转向架车型（`isJacobsBogie`）不使用自定义转向架，照旧。

### 10.2 渲染

- **一级（跟车体固定）**：超出细节半径的车厢，转向架挂在车体矩阵下，平移 `position` 画出。
- **二级（沿轨道转向）**：细节半径内，转向架中心取轨道上 `position` 处的点，朝向和俯仰取该点前后各 1 格的两个轨道点（`BOGIE_HALF_LENGTH`），用自己的世界矩阵画，不受车体矩阵和 ANTE 车体侧倾影响。
- `Train.getTrackPosition(car, offset)`：只读查询，和 `getRoutePosition` 用同一套里程、路径和 `getModelZOffset`，不改任何状态。车厢编号同 `calculateCar`，正偏移从车厢第一端指向第二端（正向列车里程减小，反向列车里程增大），与车体矩阵的 +z 一致，所以一级和二级位置相同。
- **内置转向架**：客户端设置"内置转向架沿轨道转向"（`ClientConfig` 的 `bogiesFollowTrack`），**默认开**（按用户要求，默认列车用 4.x 的转向架表现）；关掉回到 3.x 的跟车体。铰接转向架仍画在车体和贯通道上，不走二级。
- 车体定位分两种（用户定：4.x 资源按 4.x 的逻辑）：写了 `bogie1`/`bogie2` 的车型（4.x 资源）按 10.4 节由转向架定位；其余车型（3.x 资源和内置车型）仍由两端点定位。

### 10.3 改动的类

- 新增 `mtr.client.CustomBogie`。
- `TrainProperties`：新增 `bogie1`、`bogie2` 和 `hasCustomBogies()`；原构造函数保留，附属模组不受影响。
- `TrainClientRegistry.register`：新增带两个转向架参数的重载，原重载保留。
- `ICustomResources`：`CUSTOM_TRAINS_BOGIE_1/2`、`CUSTOM_TRAINS_BOGIE_POSITION`。
- `CustomResources`：`readBogie`；在读车体 `texture_id` 之前读转向架，因为 ANTE 的 mixin 记住最后一次读到的贴图给下一个 OBJ/Blockbench 模型用。
- `Train.getTrackPosition`；`JonModelTrainRenderer` 的 `renderBogieOnBody`、`renderBogieOnTrack`、`renderBogieModel`。
- `ClientConfig`、`ConfigScreen`、`en_us.json`、`zh_cn.json`：新设置。

### 10.4 4.x 资源的车体由转向架定位

照 4.x `PositionAndRotation(bogiePositions, vehicleCar, hasPitch)`：

- 两个转向架中心各取轨道上 `position` 处（前后各 1 格两点的中点）；车体朝向和俯仰取低位置转向架指向高位置转向架；车体中心 = 两转向架中点沿车身方向退回两者 `position` 的平均值。只有一个转向架或两个位置相同时，朝向取那个转向架自己的。
- 只在渲染里做（`JonModelTrainRenderer.getPlacementOnBogies`），远近车厢都用，所以出了细节半径车体不跳。转向架位置按里程（沿轨道弧长）算，和 4.x 核心一致。
- **没改的**（仍按车厢两端点，和车体画出来的位置在弯道上差几厘米到十几厘米，直道上完全一致）：乘客站位和下车点、开门检测（`calculateCar`、`scanDoors`）、贯通道和屏障（`renderConnection`、`renderBarrier`）、声音位置、服务端。要完全一致得把车厢定位放进组件，见解耦文档。

