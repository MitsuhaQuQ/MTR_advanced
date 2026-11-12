# MeshBuildingRailChunk.java（ANTE）

轨道分块的网格版：把块里各轨道的各段模型变换后合成一个模型（`transformModel`；开了轨道变形时 `transformModelDeform` 逐顶点沿曲线弯），每段按中点取光照，后台线程合成、渲染线程上传，之后每帧只入队一次绘制。

本分支的改动（0.73.4）：`enqueue` 对平面轨道（`FLAT_RAIL_KEY`）保留顶点颜色（其他模型不拿轨道工具时盖成白色），拿着轨道工具时不画（MTR 那时逐段画彩色轨道）。
