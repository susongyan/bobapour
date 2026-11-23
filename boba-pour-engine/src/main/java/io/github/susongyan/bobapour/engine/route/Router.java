package io.github.susongyan.bobapour.engine.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.susongyan.bobachop.ast.Assignment;
import io.github.susongyan.bobachop.ast.DeleteStatement;
import io.github.susongyan.bobachop.ast.InsertStatement;
import io.github.susongyan.bobachop.ast.Statement;
import io.github.susongyan.bobachop.ast.UpdateStatement;
import io.github.susongyan.bobapour.config.model.AlgorithmConfig;
import io.github.susongyan.bobapour.config.model.StrategyConfig;
import io.github.susongyan.bobapour.config.metadata.DataNode;
import io.github.susongyan.bobapour.config.metadata.DataNodes;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.config.metadata.PartitionIndex;
import io.github.susongyan.bobapour.engine.bind.BoundStatement;
import io.github.susongyan.bobapour.engine.bind.TableBinding;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * 路由器（T4 骨架）：绑定键值 → 物理目标。纯内存零 IO。
 * 算法面（诚实边界）：v0 内建唯一算法 MOD（{@code 索引 = 值 mod count}，仅整数字面量；
 * 算法 SPI 于 M2/T7 落地，未注册类型在此显式拒绝）。缺键维度 = 全量节点（全路由），
 * 不做猜测——全路由流量随后在裁决层按表开关/矩阵/guards 显式处置。
 * INSERT 逐行拆行路由（D9）：行缺所需分片键 = 不可静态路由，显式拒绝。
 * D13 分区定位仅写路径（读路径永不改写）：INSERT 逐行 locate（行级定位名）、UPDATE/DELETE
 * 语句级 locate（全部键值同一分区才定位；SET 含分区键强制回退）；miss/无键/类型不符回退父表。
 */
public final class Router {

    private Router() {
    }

    /** 路由一条已绑定语句（无分片表 → 空目标结果）。 */
    public static RouteResult route(final BoundStatement bound, final MetadataSnapshot snapshot) {
        final Map<String, List<DataNode>> tableTargets = new LinkedHashMap<String, List<DataNode>>();
        for (final TableBinding binding : bound.tables().values()) {
            tableTargets.put(binding.table().logicalName(), targetsOf(binding));
        }
        List<List<DataNode>> insertRowTargets = null;
        final TableBinding writeTarget = writeTarget(bound);
        if (bound.statement() instanceof InsertStatement && null != writeTarget
                && null != writeTarget.insertRows()) {
            // 缺列清单的 VALUES 行不可静态提取（bind 不产行值）——不产逐行目标，
            // 多目标时由裁决层显式拒绝（拆行是执行正确性前提）。
            insertRowTargets = insertTargets(writeTarget);
        }
        int located = 0;
        int fallback = 0;
        List<String> insertRowPartitions = null;
        String writePartition = null;
        if (null != writeTarget && null != writeTarget.table().partition()) {
            final PartitionIndex partition = writeTarget.table().partition();
            if (bound.statement() instanceof InsertStatement) {
                // INSERT 逐行口径：行值 locate（缺列/参数标记/miss → null=该行回退父表）；
                // insert-select 不可静态定位（D13 一律回退）。
                if (null != insertRowTargets) {
                    insertRowPartitions = new ArrayList<String>(writeTarget.insertRows().size());
                    for (final Map<String, Object> row : writeTarget.insertRows()) {
                        final String name = partition.locate(row.get(partition.key().toLowerCase()));
                        insertRowPartitions.add(name);
                        if (null == name) {
                            fallback++;
                        } else {
                            located++;
                        }
                    }
                } else {
                    fallback++;
                }
            } else {
                // UPDATE/DELETE 语句级口径：全部键值命中同一分区才定位；miss/多分区 → 回退。
                // SET 含分区键 = 跨分区更新（改写必违反分区约束），语句级强制回退（D13）。
                if (setContainsPartitionKey(bound.statement(), partition.key())) {
                    fallback++;
                } else {
                    final List<Object> values = writeTarget.partitionKeyValues();
                    if (values.isEmpty()) {
                        fallback++;
                    } else {
                        String agreed = null;
                        boolean uniform = true;
                        for (final Object value : values) {
                            final String name = partition.locate(value);
                            if (null == name) {
                                fallback++;
                                uniform = false;
                            } else {
                                located++;
                                if (null == agreed) {
                                    agreed = name;
                                } else if (!agreed.equals(name)) {
                                    uniform = false;
                                }
                            }
                        }
                        writePartition = uniform ? agreed : null;
                    }
                }
            }
        }
        return new RouteResult(tableTargets, insertRowTargets, located, fallback, insertRowPartitions, writePartition);
    }

    /** UPDATE SET 赋值列含分区键（跨分区更新，D13 语句级回退前提）。 */
    private static boolean setContainsPartitionKey(final Statement statement, final String partitionKey) {
        if (!(statement instanceof UpdateStatement)) {
            return false;
        }
        for (final Assignment assignment : ((UpdateStatement) statement).assignments()) {
            if (assignment.column().name().equalsIgnoreCase(partitionKey)) {
                return true;
            }
        }
        return false;
    }

    /** 语句级目标：库维 × 表维索引笛卡尔。 */
    private static List<DataNode> targetsOf(final TableBinding binding) {
        final DataNodes nodes = binding.table().dataNodes();
        final List<String> datasources = nodes.datasourceNames();
        final List<String> tables = nodes.tableNames();
        final List<Integer> dbIndexes = dimensionIndexes(binding.databaseKeyValues(),
                binding.table().rule().databaseStrategy(), "库维", datasources.size());
        final List<Integer> tableIndexes = dimensionIndexes(binding.tableKeyValues(),
                binding.table().rule().tableStrategy(), "表维", tables.size());
        final List<DataNode> targets = new ArrayList<DataNode>(dbIndexes.size() * tableIndexes.size());
        for (final int db : dbIndexes) {
            for (final int table : tableIndexes) {
                targets.add(new DataNode(datasources.get(db), tables.get(table)));
            }
        }
        return targets;
    }

    /** INSERT 逐行目标：行内键值 → 单节点；策略所在维度行缺键 = 不可静态路由，显式拒绝。 */
    private static List<List<DataNode>> insertTargets(final TableBinding binding) {
        final DataNodes nodes = binding.table().dataNodes();
        final List<String> datasources = nodes.datasourceNames();
        final List<String> tables = nodes.tableNames();
        final StrategyConfig dbStrategy = binding.table().rule().databaseStrategy();
        final StrategyConfig tableStrategy = binding.table().rule().tableStrategy();
        final List<List<DataNode>> rows = new ArrayList<List<DataNode>>(binding.insertRows().size());
        for (final Map<String, Object> row : binding.insertRows()) {
            final Object dbValue = keyOf(row, dbStrategy);
            final Object tableValue = keyOf(row, tableStrategy);
            if (null == dbValue || null == tableValue) {
                throw new UnsupportedQueryException("INSERT", -1, "行缺分片键，不可静态拆行路由（D9）",
                        "缺失: " + (null == dbValue && null != dbStrategy
                                ? dbStrategy.shardingColumn() : tableStrategy.shardingColumn()));
            }
            final int db = null == dbStrategy ? 0 : modIndex(dbValue, dbStrategy, "库维");
            final int table = null == tableStrategy ? 0 : modIndex(tableValue, tableStrategy, "表维");
            final List<DataNode> rowTargets = new ArrayList<DataNode>(1);
            rowTargets.add(new DataNode(datasources.get(db), tables.get(table)));
            rows.add(rowTargets);
        }
        return rows;
    }

    private static Object keyOf(final Map<String, Object> row, final StrategyConfig strategy) {
        return null == strategy ? Long.valueOf(0L) : row.get(strategy.shardingColumn().toLowerCase());
    }

    /** 维度索引集：无策略 = 字面单段 [0]；有键值 = 取模去重；缺键值 = 全量（全路由）。 */
    private static List<Integer> dimensionIndexes(final List<Object> values, final StrategyConfig strategy,
            final String dimension, final int available) {
        if (null == strategy) {
            final List<Integer> indexes = new ArrayList<Integer>(1);
            indexes.add(0);
            return indexes;
        }
        if (values.isEmpty()) {
            return range(available);
        }
        final List<Integer> indexes = new ArrayList<Integer>(values.size());
        for (final Object value : values) {
            final int index = Math.min(modIndex(value, strategy, dimension), available - 1);
            if (!indexes.contains(index)) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private static List<Integer> range(final int count) {
        final List<Integer> indexes = new ArrayList<Integer>(count);
        for (int i = 0; i < count; i++) {
            indexes.add(i);
        }
        return indexes;
    }

    private static int modIndex(final Object value, final StrategyConfig strategy, final String dimension) {
        if (!(value instanceof Long)) {
            throw new UnsupportedQueryException("分片算法", -1, "MOD（v0 内建）仅支持整数字面量",
                    dimension + "分片键值类型: " + value.getClass().getSimpleName());
        }
        return (int) Math.floorMod(((Long) value).longValue(), countOf(strategy, dimension));
    }

    private static int countOf(final StrategyConfig strategy, final String dimension) {
        final AlgorithmConfig algorithm = strategy.algorithm();
        if (!"MOD".equals(algorithm.type())) {
            throw new UnsupportedQueryException("分片算法", -1, "算法 SPI 未注册（M2/T7）",
                    "type=" + algorithm.type());
        }
        final String count = algorithm.props().get("count");
        if (null == count) {
            throw new UnsupportedQueryException("分片算法", -1, "MOD 缺 count 属性",
                    dimension + "策略 props");
        }
        return Integer.parseInt(count);
    }

    /** 写目标绑定（写语句单表）；SELECT/无分片表为 null。 */
    private static TableBinding writeTarget(final BoundStatement bound) {
        final Statement statement = bound.statement();
        final boolean write = statement instanceof InsertStatement || statement instanceof UpdateStatement
                || statement instanceof DeleteStatement;
        if (!write || bound.tables().isEmpty()) {
            return null;
        }
        return bound.tables().values().iterator().next();
    }
}
