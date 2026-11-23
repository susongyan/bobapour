package io.github.susongyan.bobapour.engine.merge;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * 归并值比较（T7）：流式归并排序键与重组装 MIN/MAX 共用的跨类型比较——数值族统一
 * BigDecimal 精确比较（Integer/Long/BigDecimal/Double 混型不分家），其余走
 * {@link Comparable}；不可比较类型显式失败，永不静默 string 化排序。
 */
final class MergeValues {

    private MergeValues() {
    }

    /**
     * 排序键语义比较：null 视为最小（MySQL 语义——ASC 首位、DESC 末位，方向翻转由调用方
     * 施加）；数值族 BigDecimal 化后比较；其余 {@link Comparable}；跨分片类型不一致
     * （如 Timestamp vs String）显式失败，永不静默错序。
     */
    static int compareKeys(final Object left, final Object right) throws SQLException {
        if (null == left && null == right) {
            return 0;
        }
        if (null == left) {
            return -1;
        }
        if (null == right) {
            return 1;
        }
        return compare(left, right);
    }

    /** MIN/MAX 语义比较：null 输家（跳过），胜者保留分片原生值（类型保真）。 */
    static Object pick(final boolean max, final Object champion, final Object candidate)
            throws SQLException {
        if (null == candidate) {
            return champion;
        }
        if (null == champion) {
            return candidate;
        }
        final int cmp = compare(candidate, champion);
        return max ? (cmp >= 0 ? candidate : champion) : (cmp <= 0 ? candidate : champion);
    }

    private static int compare(final Object left, final Object right) throws SQLException {
        try {
            return comparable(left).compareTo(comparable(right));
        } catch (final ClassCastException e) {
            throw new SQLException("归并值类型不可比较: " + left.getClass().getName() + " vs "
                    + right.getClass().getName(), e);
        }
    }

    /** 数值族 BigDecimal 化；其余要求实现 Comparable（不可比较 = 显式失败）。 */
    private static Comparable<Object> comparable(final Object value) throws SQLException {
        if (value instanceof BigDecimal || value instanceof Comparable && !(value instanceof Number)) {
            return cast(value);
        }
        if (value instanceof Number) {
            return cast(toBigDecimal((Number) value));
        }
        throw new SQLException("归并值类型不可比较: " + value.getClass().getName());
    }

    /** 跨类型数值统一（比较与重组装求和共用；double 走规范表示避免构造异常）。 */
    @SuppressWarnings("unchecked")
    private static Comparable<Object> cast(final Object value) {
        return (Comparable<Object>) value;
    }

    static BigDecimal toBigDecimal(final Number number) {
        if (number instanceof BigDecimal) {
            return (BigDecimal) number;
        }
        if (number instanceof Double || number instanceof Float) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.valueOf(number.longValue());
    }
}
