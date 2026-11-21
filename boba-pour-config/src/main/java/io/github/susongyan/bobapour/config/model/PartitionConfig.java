package io.github.susongyan.bobapour.config.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * D13 原生分区表定位改写配置（表级，默认整节缺省=不开启）。
 *
 * <p>边界由<b>配置显式声明</b>（非 catalog 采集）：RANGE 上界须全表同型（Long 或 String）
 * 且严格递增——区间连续与不重叠由"上界序列"表示法结构性保证；LIST 值跨分区不重叠。
 * 边界 miss 或键值不可静态定位时 route 回退父表原生行为（正确性不依赖改写）。</p>
 */
public final class PartitionConfig {

    private final String key;
    private final PartitionType type;
    private final List<PartitionBound> partitions;

    public PartitionConfig(final String key, final PartitionType type, final List<PartitionBound> partitions) {
        this.key = key;
        this.type = type;
        this.partitions = Collections.unmodifiableList(partitions);
    }

    /** 分区键列名（不得为加密列——由加载校验与加密规则联查保证，见 T3 快照）。 */
    public String key() {
        return key;
    }

    public PartitionType type() {
        return type;
    }

    public List<PartitionBound> partitions() {
        return partitions;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PartitionConfig)) {
            return false;
        }
        final PartitionConfig other = (PartitionConfig) obj;
        return Objects.equals(key, other.key) && type == other.type
                && Objects.equals(partitions, other.partitions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, type, partitions);
    }

    @Override
    public String toString() {
        return key + " " + type + partitions;
    }
}
