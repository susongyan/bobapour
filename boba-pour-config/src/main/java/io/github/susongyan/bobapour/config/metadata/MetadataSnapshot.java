package io.github.susongyan.bobapour.config.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.susongyan.bobapour.config.ConfigException;
import io.github.susongyan.bobapour.config.model.AlgorithmConfig;
import io.github.susongyan.bobapour.config.model.EncryptColumnConfig;
import io.github.susongyan.bobapour.config.model.GuardsConfig;
import io.github.susongyan.bobapour.config.model.PartitionConfig;
import io.github.susongyan.bobapour.config.model.PourConfig;
import io.github.susongyan.bobapour.config.model.TableRuleConfig;

/**
 * 元数据只读快照：{@link PourConfig} 经装配校验后的运行时唯一事实源——bind/route/MergePlan 裁决
 * 全部只读它、零锁（全 final 不可变字段；reload = 整体换引用）。装配期完成三件事：
 * ①actualDataNodes 模式展开（超限/非法翻译为带路径的 {@link ConfigException}）；
 * ②绑定组归属解析；③跨节交叉校验——加密列不得为分区键（D13，单节校验不出的矛盾在此拦下）。
 */
public final class MetadataSnapshot {

    private final List<String> datasourceNames;
    private final Map<String, TableSnapshot> tables;
    private final Set<String> broadcastTables;
    private final Map<String, List<String>> bindingByTable;
    private final Map<String, Map<String, EncryptColumnConfig>> encryptColumns;
    private final Map<String, AlgorithmConfig> encryptors;
    private final GuardsConfig guards;

    private MetadataSnapshot(final List<String> datasourceNames, final Map<String, TableSnapshot> tables,
            final Set<String> broadcastTables, final Map<String, List<String>> bindingByTable,
            final Map<String, Map<String, EncryptColumnConfig>> encryptColumns,
            final Map<String, AlgorithmConfig> encryptors, final GuardsConfig guards) {
        this.datasourceNames = datasourceNames;
        this.tables = tables;
        this.broadcastTables = broadcastTables;
        this.bindingByTable = bindingByTable;
        this.encryptColumns = encryptColumns;
        this.encryptors = encryptors;
        this.guards = guards;
    }

    /**
     * 装配快照：展开节点、解析绑定组、跨节校验。
     *
     * @throws ConfigException 配置矛盾（展开超限、加密列为分区键）
     */
    public static MetadataSnapshot of(final PourConfig config) {
        final Map<String, TableSnapshot> tables = new LinkedHashMap<String, TableSnapshot>();
        final Map<String, List<String>> bindingByTable = new HashMap<String, List<String>>();
        for (final List<String> group : config.bindingGroups()) {
            for (final String member : group) {
                bindingByTable.put(member, group);
            }
        }
        for (final Map.Entry<String, TableRuleConfig> entry : config.shardingTables().entrySet()) {
            final String name = entry.getKey();
            final TableRuleConfig rule = entry.getValue();
            final DataNodes nodes;
            try {
                nodes = DataNodes.of(rule.actualDataNodes());
            } catch (final IllegalStateException e) {
                throw new ConfigException("配置错误: sharding.tables." + name
                        + ".actualDataNodes: " + e.getMessage());
            }
            final PartitionConfig partition = rule.partition();
            tables.put(name, new TableSnapshot(name, rule, nodes,
                    null == partition ? null : PartitionIndex.of(partition),
                    bindingByTable.containsKey(name) ? bindingByTable.get(name) : null));
        }
        final Map<String, Map<String, EncryptColumnConfig>> encrypt = config.encrypt().tables();
        for (final Map.Entry<String, TableSnapshot> entry : tables.entrySet()) {
            final TableSnapshot table = entry.getValue();
            if (null == table.partition()) {
                continue;
            }
            final Map<String, EncryptColumnConfig> columns = encrypt.get(entry.getKey());
            if (null != columns && columns.containsKey(table.partition().key())) {
                throw new ConfigException("配置错误: encrypt.tables." + entry.getKey() + ".columns."
                        + table.partition().key() + ": 加密列不得为分区键（sharding.tables."
                        + entry.getKey() + ".partition.key，D13）");
            }
        }
        return new MetadataSnapshot(
                new ArrayList<String>(config.datasources().keySet()),
                Collections.unmodifiableMap(tables),
                Collections.unmodifiableSet(new HashSet<String>(config.broadcastTables())),
                Collections.unmodifiableMap(bindingByTable),
                Collections.unmodifiableMap(encrypt),
                config.encrypt().encryptors(),
                config.guards());
    }

    /** 数据源名清单（配置声明序）。 */
    public List<String> datasourceNames() {
        return Collections.unmodifiableList(datasourceNames);
    }

    /** 分片表快照；非分片表返回 null。 */
    public TableSnapshot table(final String logicalName) {
        return tables.get(logicalName);
    }

    public boolean isSharded(final String logicalName) {
        return tables.containsKey(logicalName);
    }

    public boolean isBroadcast(final String logicalName) {
        return broadcastTables.contains(logicalName);
    }

    /** 表所属绑定组（含本表）；无组返回 null。 */
    public List<String> bindingGroup(final String logicalName) {
        return bindingByTable.get(logicalName);
    }

    /** 表的加密列声明；无加密/未知表返回空映射（调用方免判空）。 */
    public Map<String, EncryptColumnConfig> encryptColumns(final String logicalName) {
        final Map<String, EncryptColumnConfig> columns = encryptColumns.get(logicalName);
        return null == columns ? Collections.<String, EncryptColumnConfig>emptyMap() : columns;
    }

    /** 加密器算法声明；未声明返回 null。 */
    public AlgorithmConfig encryptor(final String name) {
        return encryptors.get(name);
    }

    public GuardsConfig guards() {
        return guards;
    }

    /** 广播表节点 = 全部数据源 × 表名自身（全副本语义）。 */
    public List<DataNode> broadcastNodes(final String logicalName) {
        final List<DataNode> nodes = new ArrayList<DataNode>(datasourceNames.size());
        for (final String ds : datasourceNames) {
            nodes.add(new DataNode(ds, logicalName));
        }
        return nodes;
    }
}
