package io.github.susongyan.bobapour.exception;

/**
 * 执行前显式拒绝（DESIGN §2.3 / 铁律"拒绝发生在执行前，失败显式且可读"）。
 * 携带四要素：构造名（如 {@code LIMIT}/{@code DISTINCT}/{@code JOIN}）、源位置（语句级为 -1）、
 * 命中规则（归并矩阵 ❌ 格 / guards / 表级 crossShardQuery 开关 / 白名单外）、
 * 当前值/上限值（guards 类拒绝必带）或补充原因。三类来源：
 * 归并矩阵裁决（{@code MergePlanner}）、guards 超限、解析白名单外（内核异常在决策链入口包装）。
 */
public final class UnsupportedQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String construct;
    private final int position;
    private final String rule;
    private final String detail;

    public UnsupportedQueryException(final String construct, final int position, final String rule,
            final String detail) {
        super(construct + "（位置 " + position + "）命中规则: " + rule
                + (null == detail || detail.isEmpty() ? "" : "；" + detail));
        this.construct = construct;
        this.position = position;
        this.rule = rule;
        this.detail = null == detail ? "" : detail;
    }

    /** 构造名。 */
    public String construct() {
        return construct;
    }

    /** 源文本偏移；语句级/非 AST 拒绝为 -1。 */
    public int position() {
        return position;
    }

    /** 命中规则。 */
    public String rule() {
        return rule;
    }

    /** 当前值/上限值或补充原因；无则空串。 */
    public String detail() {
        return detail;
    }
}
