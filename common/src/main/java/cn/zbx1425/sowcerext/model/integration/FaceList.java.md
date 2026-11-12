# FaceList.java（ANTE sowcer）

一种渲染类型的半透明面暂存：`addFace` 收集变换后的面，`commit` 时（需要时）从远到近排序，再逐顶点写进原版缓冲。

本分支的改动（0.73.4）：排序键（面中心到相机距离的平方）在面加入时算一次（`sortingDistance`），不再在每次比较里重算。
