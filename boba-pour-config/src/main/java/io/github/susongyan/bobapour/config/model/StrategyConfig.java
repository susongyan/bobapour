package io.github.susongyan.bobapour.config.model;

import java.util.Objects;

/**
 * 分片策略（某一维度）：分片列 + 算法声明。库维度/表维度各一份（TableRuleConfig）。
 */
public final class StrategyConfig {

    private final String shardingColumn;
    private final AlgorithmConfig algorithm;

    public StrategyConfig(final String shardingColumn, final AlgorithmConfig algorithm) {
        this.shardingColumn = shardingColumn;
        this.algorithm = algorithm;
    }

    public String shardingColumn() {
        return shardingColumn;
    }

    public AlgorithmConfig algorithm() {
        return algorithm;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StrategyConfig)) {
            return false;
        }
        final StrategyConfig other = (StrategyConfig) obj;
        return Objects.equals(shardingColumn, other.shardingColumn) && Objects.equals(algorithm, other.algorithm);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shardingColumn, algorithm);
    }

    @Override
    public String toString() {
        return shardingColumn + "->" + algorithm;
    }
}
