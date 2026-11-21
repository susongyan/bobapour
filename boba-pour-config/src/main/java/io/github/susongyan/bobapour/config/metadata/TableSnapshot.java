package io.github.susongyan.bobapour.config.metadata;

import java.util.List;
import java.util.Objects;

import io.github.susongyan.bobapour.config.model.TableRuleConfig;

/**
 * 单张分片表的只读快照：规则 + 展开节点 + 分区索引 + 绑定组归属。
 * 由 {@link MetadataSnapshot#of} 装配一次、终身不可变（全 final 字段，无锁读取）；
 * 生命周期 = 配置生命周期（reload = 整体换快照引用，不做字段级更新）。
 */
public final class TableSnapshot {

    private final String logicalName;
    private final TableRuleConfig rule;
    private final DataNodes dataNodes;
    private final PartitionIndex partition;
    private final List<String> bindingGroup;

    TableSnapshot(final String logicalName, final TableRuleConfig rule, final DataNodes dataNodes,
            final PartitionIndex partition, final List<String> bindingGroup) {
        this.logicalName = Objects.requireNonNull(logicalName, "logicalName");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.dataNodes = Objects.requireNonNull(dataNodes, "dataNodes");
        this.partition = partition;
        this.bindingGroup = bindingGroup;
    }

    public String logicalName() {
        return logicalName;
    }

    /** 原始规则（策略/开关/分区声明）。 */
    public TableRuleConfig rule() {
        return rule;
    }

    /** 展开后的物理节点（稳定序，库维在外）。 */
    public DataNodes dataNodes() {
        return dataNodes;
    }

    /** 分区索引；未声明分区（D13 默认关）为 null。 */
    public PartitionIndex partition() {
        return partition;
    }

    /** 所属绑定组（含本表，声明序）；不属于任何组为 null。 */
    public List<String> bindingGroup() {
        return bindingGroup;
    }

    @Override
    public String toString() {
        return logicalName + " -> " + dataNodes;
    }
}
