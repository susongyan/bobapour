package io.github.susongyan.bobapour.config.metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * D13 运维校验的库内侧输入：catalog 巡检采集到的分区事实（名称 + 边界的原始文本形态）。
 * 纯数据载体——采集 SQL 属方言项（各方言巡检命令负责填充，PG 见任务 T11），
 * 与配置声明边界的比对逻辑在 {@link PartitionDiff}。
 */
public final class CatalogPartition {

    private final String name;
    private final String upper;
    private final List<String> values;

    private CatalogPartition(final String name, final String upper, final List<String> values) {
        this.name = Objects.requireNonNull(name, "name");
        this.upper = upper;
        this.values = values;
    }

    /** RANGE 分区的库内事实：上界原始文本（如 {@code 100}、{@code 2026-01-01 00:00:00}）。 */
    public static CatalogPartition range(final String name, final String upper) {
        return new CatalogPartition(name, Objects.requireNonNull(upper, "upper"), null);
    }

    /** LIST 分区的库内事实：值集原始文本。 */
    public static CatalogPartition list(final String name, final String... values) {
        return new CatalogPartition(name, null,
                Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(values))));
    }

    public String name() {
        return name;
    }

    /** RANGE 上界文本；LIST 分区为 null。 */
    public String upper() {
        return upper;
    }

    /** LIST 值集；RANGE 分区为 null。 */
    public List<String> values() {
        return values;
    }

    @Override
    public String toString() {
        return null == upper ? name + values : name + "[< " + upper + "]";
    }
}
