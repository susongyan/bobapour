package io.github.susongyan.bobapour.engine.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回放报告（T4 DoD 产物）：落格分布（裁决 kind → 计数）、拒绝清单（逐条四要素）、
 * D13 分区定位/回退分布。纯数据 + 文本渲染（人读/CI 断言两用）。
 */
public final class ReplayReport {

    private final int total;
    private final Map<String, Integer> cells = new LinkedHashMap<String, Integer>();
    private final List<String> rejections = new ArrayList<String>();
    private int locatedPartitions;
    private int fallbackPartitions;

    ReplayReport(final int total) {
        this.total = total;
    }

    void tally(final String cell) {
        final Integer count = cells.get(cell);
        cells.put(cell, null == count ? 1 : count + 1);
    }

    void reject(final String line) {
        rejections.add(line);
    }

    void partitions(final int located, final int fallback) {
        locatedPartitions += located;
        fallbackPartitions += fallback;
    }

    public int total() {
        return total;
    }

    /** 落格分布（裁决标签 → 计数，声明序稳定）。 */
    public Map<String, Integer> cells() {
        return new LinkedHashMap<String, Integer>(cells);
    }

    /** 拒绝清单行（[id] 构造名 命中规则 detail）。 */
    public List<String> rejections() {
        return new ArrayList<String>(rejections);
    }

    public int locatedPartitions() {
        return locatedPartitions;
    }

    public int fallbackPartitions() {
        return fallbackPartitions;
    }

    /** 人读渲染：落格分布 + 拒绝清单 + D13 分布。 */
    public String render() {
        final StringBuilder out = new StringBuilder();
        out.append("落格分布（").append(total).append(" 条）：\n");
        for (final Map.Entry<String, Integer> cell : cells.entrySet()) {
            out.append("  ").append(cell.getKey()).append(": ").append(cell.getValue()).append('\n');
        }
        out.append("拒绝清单（").append(rejections.size()).append(" 条）：\n");
        for (final String line : rejections) {
            out.append("  ").append(line).append('\n');
        }
        out.append("D13 分区：定位 ").append(locatedPartitions).append(" 次，回退 ").append(fallbackPartitions)
                .append(" 次\n");
        return out.toString();
    }
}
