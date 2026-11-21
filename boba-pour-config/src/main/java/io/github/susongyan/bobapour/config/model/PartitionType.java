package io.github.susongyan.bobapour.config.model;

/**
 * D13 分区类型：仅 RANGE/LIST（HASH 须 bit 级复刻方言哈希函数，兼容风险大不做）。
 */
public enum PartitionType {

    /** 连续区间分区：边界上界单调递增声明，连续性与不重叠由表示法结构性保证。 */
    RANGE,

    /** 枚举值分区：值清单跨分区不重叠。 */
    LIST
}
