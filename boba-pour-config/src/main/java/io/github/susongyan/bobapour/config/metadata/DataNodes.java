package io.github.susongyan.bobapour.config.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * actualDataNodes 模式展开结果：两段模式（库段.表段，每段至多一个 ${a..b} 区间记号）笛卡尔展开，
 * 库维在外、表维在内——序确定且稳定（执行 fan-out 与回放报告的基准序）。
 * 区间值不做零填充（${0..9} → 0..9，${10..19} → 10..19）；展开总节点数上限 {@link #MAX_NODES}
 * （防笔误区间撑爆内存，超限抛 {@link IllegalStateException}，由快照装配翻译为带路径的配置错误）。
 * 模式语法（两段/记号形态）的校验属 {@code ConfigLoader}；本类是同一文法的展开侧，共用
 * {@link #splitSegments(String)} 切分器，漂移面收拢在这一个方法。
 */
public final class DataNodes {

    /** 展开总节点数上限（16 分片 × 4096 表已属超大规模部署）。 */
    public static final int MAX_NODES = 65536;

    private final List<DataNode> nodes;

    private DataNodes(final List<DataNode> nodes) {
        this.nodes = nodes;
    }

    /** 展开已通过加载校验的 actualDataNodes 模式；非法形态在此属不可能状态（IllegalStateException）。 */
    public static DataNodes of(final String pattern) {
        final List<String> segments = splitSegments(pattern);
        if (2 != segments.size()) {
            throw new IllegalStateException("须为两段 '库.表' 形态: " + pattern);
        }
        final List<String> datasources = expand(segments.get(0));
        final List<String> tables = expand(segments.get(1));
        if (datasources.size() * (long) tables.size() > MAX_NODES) {
            throw new IllegalStateException("展开后节点数超上限 " + MAX_NODES + ": " + pattern);
        }
        final List<DataNode> out = new ArrayList<DataNode>(datasources.size() * tables.size());
        for (final String ds : datasources) {
            for (final String table : tables) {
                out.add(new DataNode(ds, table));
            }
        }
        return new DataNodes(out);
    }

    /**
     * 段切分（模式文法的单一事实源，{@code ConfigLoader} 校验侧同用）：
     * 只认 ${..} 记号外的 '.'——记号内的 ".."（区间）与数字不被切。
     */
    public static List<String> splitSegments(final String pattern) {
        final List<String> out = new ArrayList<String>();
        final StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (!inToken && '$' == c && i + 1 < pattern.length() && '{' == pattern.charAt(i + 1)) {
                inToken = true;
                cur.append("${");
                i++;
            } else if (inToken && '}' == c) {
                inToken = false;
                cur.append(c);
            } else if (!inToken && '.' == c) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** 展开单段：无记号 = 字面段；含一个 ${a..b} = 前缀/后缀保持、区间逐值代入。段级同受 {@link #MAX_NODES} 约束。 */
    private static List<String> expand(final String segment) {
        final int open = segment.indexOf("${");
        if (0 > open) {
            return Collections.singletonList(segment);
        }
        final int close = segment.indexOf('}', open);
        if (0 > close) {
            throw new IllegalStateException("区间记号未闭合: " + segment);
        }
        final String inner = segment.substring(open + 2, close);
        final int sep = inner.indexOf("..");
        if (0 > sep) {
            throw new IllegalStateException("区间记号须为 ${a..b} 形态: " + segment);
        }
        final long from;
        final long to;
        try {
            from = Long.parseLong(inner.substring(0, sep));
            to = Long.parseLong(inner.substring(sep + 2));
        } catch (final NumberFormatException e) {
            throw new IllegalStateException("区间记号须为十进制整数: " + segment, e);
        }
        if (from > to) {
            throw new IllegalStateException("区间下界大于上界: " + segment);
        }
        if (to - from + 1 > MAX_NODES) {
            throw new IllegalStateException("区间展开数超上限 " + MAX_NODES + ": " + segment);
        }
        final String prefix = segment.substring(0, open);
        final String suffix = segment.substring(close + 1);
        final List<String> out = new ArrayList<String>((int) (to - from + 1));
        for (long v = from; v <= to; v++) {
            out.add(prefix + v + suffix);
        }
        return out;
    }

    /** 全部节点，库维在外（稳定序）。 */
    public List<DataNode> all() {
        return Collections.unmodifiableList(nodes);
    }

    /** 去重后的数据源名，按首次出现序。 */
    public List<String> datasourceNames() {
        return new ArrayList<String>(new LinkedHashSet<String>(datasourcesOf()));
    }

    /** 去重后的物理表名，按首次出现序（同一模式跨数据源同名，首库序即全序）。 */
    public List<String> tableNames() {
        final LinkedHashSet<String> names = new LinkedHashSet<String>();
        for (final DataNode node : nodes) {
            names.add(node.tableName());
        }
        return new ArrayList<String>(names);
    }

    private List<String> datasourcesOf() {
        final List<String> out = new ArrayList<String>(nodes.size());
        for (final DataNode node : nodes) {
            out.add(node.datasourceName());
        }
        return out;
    }

    public int size() {
        return nodes.size();
    }

    /** 便捷判定：节点集合是否包含指定数据源。 */
    public boolean coversDatasource(final String datasourceName) {
        for (final DataNode node : nodes) {
            if (node.datasourceName().equals(datasourceName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 与另一表节点集的数据源对齐性（绑定表路由前提：同库维布局）。
     * 供快照装配期校验；运行期只读。
     */
    public boolean sameDatasourceLayout(final DataNodes other) {
        return datasourceNames().equals(other.datasourceNames());
    }
}
