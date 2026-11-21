package io.github.susongyan.bobapour.config.model;

/**
 * 表级跨分片查询开关（DESIGN D4，只两档不造第三档）。默认 {@link #REJECT}：
 * 新表先拒，影子回放数据说话后逐表打开；纯分片键 OLTP 表的任何全路由都是 bug 或攻击。
 */
public enum CrossShardQuery {

    /** 跨分片查询执行前显式拒绝。 */
    REJECT,

    /** 允许进入跨分片归并矩阵（矩阵外形态仍拒绝）。 */
    WHITELIST
}
