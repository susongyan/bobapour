# BobaPour

基于 [BobaChop](https://github.com/susongyan/bobachop) 解析内核的分库分表 + 透明加密中间件（JDBC 驱动内嵌形态）。

- 三级能力模型：解析白名单 / 单分片透传 / 跨分片归并矩阵，裁决在执行前，拒绝显式可读
- 归并内存安全：白名单结构性有界 + guards 计数上限 + 驱动强制流式
- 迁移体系：对拍三关（决策链回放 / 改写对拍三相等 / 影子双跑）

- 设计规格（单一事实源）：[DESIGN.md](DESIGN.md)
- 模块规划：`boba-pour-driver` / `boba-pour-engine` / `boba-pour-config` / `corpus/`
- 状态：设计定稿（DESIGN v1.0），M0 未开工
