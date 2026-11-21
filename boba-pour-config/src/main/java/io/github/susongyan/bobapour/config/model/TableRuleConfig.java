package io.github.susongyan.bobapour.config.model;

import java.util.Objects;

/**
 * 分片表规则。actualDataNodes 形如 {@code ds_${0..1}.t_order_${0..7}}——
 * 两段（库.表），每段至多一个 {@code ${a..b}} 区间记号（a≤b，十进制），
 * 段内记号外文字为字面前缀/后缀。某段含区间则该段必须有对应分片策略
 * （库段→databaseStrategy，表段→tableStrategy）；两段全字面则策略可缺省（固定映射）。
 *
 * <p>crossShardQuery 默认 REJECT（D4）；partition 为 D13 表级开关（缺省=不开启）。</p>
 */
public final class TableRuleConfig {

    private final String actualDataNodes;
    private final StrategyConfig databaseStrategy;
    private final StrategyConfig tableStrategy;
    private final CrossShardQuery crossShardQuery;
    private final PartitionConfig partition;

    public TableRuleConfig(final String actualDataNodes, final StrategyConfig databaseStrategy,
            final StrategyConfig tableStrategy, final CrossShardQuery crossShardQuery,
            final PartitionConfig partition) {
        this.actualDataNodes = actualDataNodes;
        this.databaseStrategy = databaseStrategy;
        this.tableStrategy = tableStrategy;
        this.crossShardQuery = crossShardQuery;
        this.partition = partition;
    }

    public String actualDataNodes() {
        return actualDataNodes;
    }

    /** 库维策略；actualDataNodes 库段全字面时可为 null。 */
    public StrategyConfig databaseStrategy() {
        return databaseStrategy;
    }

    /** 表维策略；actualDataNodes 表段全字面时可为 null。 */
    public StrategyConfig tableStrategy() {
        return tableStrategy;
    }

    public CrossShardQuery crossShardQuery() {
        return crossShardQuery;
    }

    /** D13 分区定位配置；null = 未开启（默认）。 */
    public PartitionConfig partition() {
        return partition;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TableRuleConfig)) {
            return false;
        }
        final TableRuleConfig other = (TableRuleConfig) obj;
        return Objects.equals(actualDataNodes, other.actualDataNodes)
                && Objects.equals(databaseStrategy, other.databaseStrategy)
                && Objects.equals(tableStrategy, other.tableStrategy)
                && crossShardQuery == other.crossShardQuery
                && Objects.equals(partition, other.partition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(actualDataNodes, databaseStrategy, tableStrategy, crossShardQuery, partition);
    }

    @Override
    public String toString() {
        return actualDataNodes + " cross=" + crossShardQuery
                + (null == partition ? "" : " partition=" + partition);
    }
}
