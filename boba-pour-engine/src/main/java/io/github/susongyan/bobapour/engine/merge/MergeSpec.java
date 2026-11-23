package io.github.susongyan.bobapour.engine.merge;

import java.util.ArrayList;
import java.util.List;

import io.github.susongyan.bobachop.ast.ColumnRef;
import io.github.susongyan.bobachop.ast.Expr;
import io.github.susongyan.bobachop.ast.FunctionCall;
import io.github.susongyan.bobachop.ast.Limit;
import io.github.susongyan.bobachop.ast.Literal;
import io.github.susongyan.bobachop.ast.OrderByItem;
import io.github.susongyan.bobachop.ast.SelectItem;
import io.github.susongyan.bobachop.ast.SelectStatement;
import io.github.susongyan.bobachop.ast.Star;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * 归并形态提取器（T7）：从已放行 SELECT 的 AST 提取四策略执行所需的静态事实——
 * 排序键（标签序）、分组键（窗口序）、聚合名清单（投影位序）、TopN 窗口（offset/count）。
 * 裁决（可提取性 = 执行前提）与执行（构造策略入参）共用本提取器，两处所见一致。
 *
 * <p><b>排序键用列标签而非投影位</b>：归并比较器按 {@code findColumn(label)} 取值——
 * {@code SELECT * ORDER BY x} 天然可归并（Star 投影列集未知但驱动知道）；裸列引用的
 * 限定前缀（{@code ORDER BY a.x}）不进标签（结果集列名不带限定）。静态可归并性 =
 * 排序项为裸列引用，且标签命中投影列名/别名（Star 投影除外——列集未知交由驱动裁决）。
 * 表达式排序键（{@code ORDER BY UPPER(x)}）无标签可寻，显式拒绝。</p>
 */
public final class MergeSpec {

    private MergeSpec() {
    }

    /** 归并排序键：列标签 + 方向（比较器按标签在各分片结果集上取值）。 */
    public static final class OrderKey {

        private final String label;
        private final boolean descending;

        OrderKey(final String label, final boolean descending) {
            this.label = label;
            this.descending = descending;
        }

        /** 列标签（裸列名或投影别名）。 */
        public String label() {
            return label;
        }

        /** 是否降序（三态方向中 UNSPECIFIED 与 ASC 同为升序）。 */
        public boolean descending() {
            return descending;
        }

        @Override
        public String toString() {
            return label + (descending ? " DESC" : "");
        }
    }

    /** ORDER BY 排序键（可归并性校验 + 流式/TopN 比较器构造）。 */
    public static List<OrderKey> orderKeys(final SelectStatement select) {
        final List<OrderByItem> items = select.orderBy();
        final List<OrderKey> keys = new ArrayList<OrderKey>(items.size());
        for (final OrderByItem item : items) {
            final String label = bareColumnLabel(item.expr(), "ORDER BY", "排序键须为裸列引用");
            requireLabelInProjection(select, label, "ORDER BY");
            keys.add(new OrderKey(label, "DESC".equalsIgnoreCase(directionOf(item))));
        }
        return keys;
    }

    /** GROUP BY 分组键（TopN 无用户排序时的窗口序；改写层按同清单补 ORDER BY）。 */
    public static List<OrderKey> groupKeys(final SelectStatement select) {
        final List<OrderKey> keys = new ArrayList<OrderKey>(select.groupBy().size());
        for (final Expr expr : select.groupBy()) {
            final String label = bareColumnLabel(expr, "GROUP BY", "分组键须为裸列引用");
            requireLabelInProjection(select, label, "GROUP BY");
            keys.add(new OrderKey(label, false));
        }
        return keys;
    }

    /**
     * 投影位 → 聚合函数名（重组装入参）：全部投影项须为 COUNT/SUM/MIN/MAX 聚合调用——
     * 混合投影（裸列/表达式混入）无跨分片合并语义，显式拒绝（AVG 已在裁决层先行拒绝）。
     */
    public static List<String> aggregates(final SelectStatement select) {
        final List<String> names = new ArrayList<String>(select.items().size());
        for (final SelectItem item : select.items()) {
            final Expr expr = item.expr();
            if (!(expr instanceof FunctionCall)) {
                throw new UnsupportedQueryException("聚合", -1,
                        "聚合重组装须投影全聚合（混合/表达式投影无合并语义）",
                        "投影项: " + expr.getClass().getSimpleName());
            }
            final String name = ((FunctionCall) expr).name().toUpperCase();
            if (!"COUNT".equals(name) && !"SUM".equals(name) && !"MIN".equals(name) && !"MAX".equals(name)) {
                throw new UnsupportedQueryException("聚合", -1,
                        "聚合重组装不支持该聚合函数（COUNT/SUM/MIN/MAX 之外）", "函数: " + name);
            }
            names.add(name);
        }
        return names;
    }

    /** TopN 窗口 {offset, rowCount}：双整数字面量（裁决层已保证，此处 fail-loud 兜底）。 */
    public static long[] limitWindow(final SelectStatement select) {
        final Limit limit = select.limit();
        if (null == limit) {
            throw new IllegalStateException("TopN 窗口提取遇到无 LIMIT 语句（裁决不变量破坏）");
        }
        return new long[]{literalLong(limit.offset(), 0L), literalLong(limit.rowCount(), -1L)};
    }

    // ---------- 内部 ----------

    private static String bareColumnLabel(final Expr expr, final String construct, final String rule) {
        if (!(expr instanceof ColumnRef)) {
            throw new UnsupportedQueryException(construct, -1, rule + "（无列标签可寻，跨分片不可归并）",
                    "排序/分组项: " + expr.getClass().getSimpleName());
        }
        return ((ColumnRef) expr).name();
    }

    /** 标签可寻性：Star 投影列集未知交驱动裁决；否则须命中投影列名或别名。 */
    private static void requireLabelInProjection(final SelectStatement select, final String label,
            final String construct) {
        for (final SelectItem item : select.items()) {
            if (item.expr() instanceof Star) {
                return;
            }
            if (item.expr() instanceof ColumnRef
                    && ((ColumnRef) item.expr()).name().equalsIgnoreCase(label)) {
                return;
            }
            if (null != item.alias() && item.alias().equalsIgnoreCase(label)) {
                return;
            }
        }
        throw new UnsupportedQueryException(construct, -1,
                "排序/分组键不在投影内（比较器按列标签取值，投影外无标签）", "键: " + label);
    }

    private static String directionOf(final OrderByItem item) {
        return null == item.direction() ? "" : item.direction().name();
    }

    private static long literalLong(final Expr expr, final long absent) {
        if (null == expr) {
            return absent;
        }
        if (!(expr instanceof Literal)) {
            throw new UnsupportedQueryException("LIMIT", -1,
                    "LIMIT 窗口非整数字面量（参数标记需执行层参数注入，v1 静态裁决不支持）", "");
        }
        try {
            return Long.parseLong(((Literal) expr).text());
        } catch (final NumberFormatException e) {
            throw new UnsupportedQueryException("LIMIT", -1, "LIMIT 窗口非整数字面量", "");
        }
    }
}
