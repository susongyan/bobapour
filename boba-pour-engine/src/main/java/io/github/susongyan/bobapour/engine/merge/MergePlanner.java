package io.github.susongyan.bobapour.engine.merge;

import java.util.List;

import io.github.susongyan.bobachop.ast.InsertStatement;
import io.github.susongyan.bobachop.ast.Limit;
import io.github.susongyan.bobachop.ast.SelectStatement;
import io.github.susongyan.bobachop.ast.Statement;
import io.github.susongyan.bobapour.config.metadata.DataNode;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.config.model.CrossShardQuery;
import io.github.susongyan.bobapour.engine.bind.BoundStatement;
import io.github.susongyan.bobapour.engine.bind.TableBinding;
import io.github.susongyan.bobapour.engine.route.RouteResult;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * 裁决器（T4 骨架）——三级能力模型的唯一裁决点（DESIGN §5.2）：形态 × 路由结果 × 表开关 ×
 * guards → 放行 + 策略 | 拒绝。纯内存零 IO，发生在执行之前；检查次序即矩阵行序的裁决优先级：
 * 直通（无受管表/单分片/广播读）→ 未托管混用 → 表级 crossShardQuery 开关 → guards 分片数 →
 * 矩阵 ❌ 格（子查询/派生表/UNION/DISTINCT/JOIN 非绑定/LIMIT 无排序/GROUP BY 非分片键/AVG）→
 * ✅ 格策略（拼接/流式/TopN/重组装）→ guards 窗口 → 写路径（D9/D10）。
 * 每一拒绝出口都带构造名与命中规则（回归用例逐格锁死，DESIGN §7"❌ 格的拒绝行为本身是验收项"）。
 */
public final class MergePlanner {

    private MergePlanner() {
    }

    /** 裁决：放行返回 MergePlan；拒绝抛 UnsupportedQueryException。 */
    public static MergePlan plan(final BoundStatement bound, final RouteResult route,
            final MetadataSnapshot snapshot) {
        final BoundStatement.Shape shape = bound.shape();
        final List<DataNode> targets = route.targets();
        final boolean crossShard = targets.size() > 1;
        if (bound.derivedManaged()) {
            throw new UnsupportedQueryException("派生表", -1,
                    "派生表内引用受管表（v0 不内绑派生表，不做直通猜测）", "");
        }
        if (bound.tables().isEmpty()) {
            return writeOrRead(bound, route, snapshot, shape);
        }
        if (!bound.unmanagedTables().isEmpty()) {
            throw new UnsupportedQueryException("表", -1, "受管表与未托管表混用（配置缺口）",
                    "未托管: " + bound.unmanagedTables());
        }
        if (shape.write) {
            return planWrite(bound, route, snapshot);
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException("存在分片表绑定但路由目标为空");
        }
        if (!crossShard) {
            return plan(MergePlan.Kind.PASSTHROUGH, "单分片，归并不存在", targets, snapshot, route);
        }
        for (final TableBinding binding : bound.tables().values()) {
            if (CrossShardQuery.REJECT == binding.table().rule().crossShardQuery()) {
                throw new UnsupportedQueryException("表级开关", -1, "crossShardQuery=REJECT（D4）",
                        "表: " + binding.table().logicalName());
            }
        }
        if (targets.size() > snapshot.guards().maxShardsPerQuery()) {
            throw new UnsupportedQueryException("guards.maxShardsPerQuery", -1, "分片数超限（D3）",
                    "当前值=" + targets.size() + "，上限值=" + snapshot.guards().maxShardsPerQuery());
        }
        if (shape.subquery) {
            throw new UnsupportedQueryException("子查询", -1, "相关子查询跨分片（矩阵 ❌，永久非目标）", "");
        }
        if (shape.derivedTable) {
            throw new UnsupportedQueryException("派生表", -1, "派生表跨分片未入矩阵（v1）", "");
        }
        if (shape.union) {
            throw new UnsupportedQueryException("UNION", -1, "UNION 跨分片未入矩阵（v1）", "");
        }
        if (shape.distinct) {
            throw new UnsupportedQueryException("DISTINCT", -1, "DISTINCT 跨分片（无界类别，矩阵 ❌）", "");
        }
        if (shape.join) {
            requireBindingGroup(bound);
        }
        if (shape.groupBy) {
            // 分组行先于"LIMIT 无排序"：GROUP BY + LIMIT 的窗口序 = 分组键（改写层补
            // ORDER BY 下推，矩阵"含分片键 GROUP BY"格），非矩阵 ❌ 的裸 LIMIT 格。
            return planGroupBy(bound, route, snapshot);
        }
        if (shape.limit && !shape.orderBy) {
            throw new UnsupportedQueryException("LIMIT", -1, "LIMIT 无 ORDER BY（结果跨分片非确定，矩阵 ❌）", "");
        }
        if (shape.aggregate) {
            if (shape.aggregateAvg) {
                throw new UnsupportedQueryException("AVG", -1, "AVG 跨分片（v1.1 拆 SUM+COUNT）", "");
            }
            // 重组装前提：全聚合投影（混合/表达式投影无跨分片合并语义——T7 执行层收紧）。
            MergeSpec.aggregates(selectOf(bound));
            return plan(MergePlan.Kind.REASSEMBLE, "SUM(SUM)/MAX(MAX)… 重组装", targets, snapshot, route);
        }
        if (shape.orderBy && shape.limit) {
            requireTopN(bound, snapshot);
            return plan(MergePlan.Kind.TOP_N, topNDescription(shape), targets, snapshot, route);
        }
        if (shape.orderBy) {
            // 流式归并前提：排序键可归并（裸列引用 + 标签可寻，见 MergeSpec）。
            MergeSpec.orderKeys(selectOf(bound));
            return plan(MergePlan.Kind.STREAM_MERGE, "优先队列流式归并，排序下推各分片", targets, snapshot, route);
        }
        return plan(MergePlan.Kind.CONCAT, "结果拼接", targets, snapshot, route);
    }

    private static MergePlan writeOrRead(final BoundStatement bound, final RouteResult route,
            final MetadataSnapshot snapshot, final BoundStatement.Shape shape) {
        if (shape.write && !bound.broadcastTables().isEmpty()) {
            final String table = bound.broadcastTables().iterator().next();
            return plan(MergePlan.Kind.BROADCAST_WRITE, "全副本广播写（D10）",
                    snapshot.broadcastNodes(table), snapshot, route);
        }
        if (shape.write && !bound.unmanagedTables().isEmpty()) {
            throw new UnsupportedQueryException("表", -1, "写目标为未托管表", "");
        }
        return plan(MergePlan.Kind.PASSTHROUGH, "无分片目标，直通", route.targets(), snapshot, route);
    }

    private static MergePlan planWrite(final BoundStatement bound, final RouteResult route,
            final MetadataSnapshot snapshot) {
        final List<DataNode> targets = route.targets();
        final Statement statement = bound.statement();
        if (statement instanceof InsertStatement) {
            if (bound.shape().insertSelect) {
                if (targets.size() > 1) {
                    throw new UnsupportedQueryException("INSERT-SELECT", -1,
                            "跨分片 insert-select 需执行层行级处理（T7 前不裁决）", "");
                }
                return plan(MergePlan.Kind.PASSTHROUGH, "单分片写", targets, snapshot, route);
            }
            if (targets.size() > 1) {
                if (null == route.insertRowTargets()) {
                    throw new UnsupportedQueryException("INSERT", -1,
                            "拆行需列清单与 VALUES 行（缺列清单不可静态提取行值）", "");
                }
                requireShardGuard(targets, snapshot);
                return plan(MergePlan.Kind.WRITE_SPLIT, "逐行按分片键拆行路由（D9）", targets, snapshot, route);
            }
            return plan(MergePlan.Kind.PASSTHROUGH, "单分片写", targets, snapshot, route);
        }
        if (targets.size() > 1) {
            requireShardGuard(targets, snapshot);
            return plan(MergePlan.Kind.WRITE_ROW_SUM, "多分片影响行数求和（D9，写路径无归并）",
                    targets, snapshot, route);
        }
        return plan(MergePlan.Kind.PASSTHROUGH, "单分片写", targets, snapshot, route);
    }

    private static void requireShardGuard(final List<DataNode> targets, final MetadataSnapshot snapshot) {
        if (targets.size() > snapshot.guards().maxShardsPerQuery()) {
            throw new UnsupportedQueryException("guards.maxShardsPerQuery", -1, "分片数超限（D3）",
                    "当前值=" + targets.size() + "，上限值=" + snapshot.guards().maxShardsPerQuery());
        }
    }

    private static MergePlan planGroupBy(final BoundStatement bound, final RouteResult route,
            final MetadataSnapshot snapshot) {
        final BoundStatement.Shape shape = bound.shape();
        if (!shape.groupByPlainColumns) {
            throw new UnsupportedQueryException("GROUP BY", -1, "GROUP BY 含表达式（不可判分片键归属）", "");
        }
        for (final String column : shape.groupByColumns) {
            if (!isShardingKey(bound, column)) {
                throw new UnsupportedQueryException("GROUP BY", -1,
                        "GROUP BY 不含分片键（组跨分片，无界类别，矩阵 ❌）", "列: " + column);
            }
        }
        if (shape.orderBy) {
            // 组不跨分片但组间序是全局序：拼接会破坏用户排序，须流式（T7 修正 T4 骨架）。
            MergeSpec.orderKeys(selectOf(bound));
            if (shape.limit) {
                requireTopN(bound, snapshot);
                return plan(MergePlan.Kind.TOP_N, topNDescription(shape), route.targets(), snapshot, route);
            }
            return plan(MergePlan.Kind.STREAM_MERGE, "组不跨分片 + 排序键可映射，流式归并",
                    route.targets(), snapshot, route);
        }
        if (shape.limit) {
            // 无用户排序：窗口序 = 分组键（改写层按同清单补 ORDER BY 下推）。
            requireTopN(bound, snapshot);
            return plan(MergePlan.Kind.TOP_N, topNDescription(shape), route.targets(), snapshot, route);
        }
        return plan(MergePlan.Kind.CONCAT, "组不跨分片，拼接",
                route.targets(), snapshot, route);
    }

    private static boolean isShardingKey(final BoundStatement bound, final String column) {
        for (final TableBinding binding : bound.tables().values()) {
            if (null != binding.table().rule().databaseStrategy()
                    && binding.table().rule().databaseStrategy().shardingColumn().equalsIgnoreCase(column)) {
                return true;
            }
            if (null != binding.table().rule().tableStrategy()
                    && binding.table().rule().tableStrategy().shardingColumn().equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    /** 绑定表 JOIN 前提：全部参与分片表同属一个绑定组（分片键等值对齐由改写层保证，v0 组校验）。 */
    private static void requireBindingGroup(final BoundStatement bound) {
        if (bound.tables().size() < 2) {
            return;
        }
        String group = null;
        for (final TableBinding binding : bound.tables().values()) {
            final List<String> mine = binding.table().bindingGroup();
            if (null == mine) {
                throw new UnsupportedQueryException("JOIN", -1, "JOIN 非绑定跨分片（联邦查询深渊，永久非目标）",
                        "无绑定组: " + binding.table().logicalName());
            }
            final String signature = String.join(",", mine);
            if (null == group) {
                group = signature;
            } else if (!group.equals(signature)) {
                throw new UnsupportedQueryException("JOIN", -1, "JOIN 非绑定跨分片（联邦查询深渊，永久非目标）",
                        "绑定组不一致: " + signature + " vs " + group);
            }
        }
    }

    private static void requireTopNWindow(final BoundStatement.Shape shape, final MetadataSnapshot snapshot) {
        if (shape.limitLiteral
                && shape.limitWindow > snapshot.guards().maxMergeWindowRows()) {
            throw new UnsupportedQueryException("guards.maxMergeWindowRows", -1, "TopN 窗口超限（D3）",
                    "当前值=" + shape.limitWindow + "，上限值=" + snapshot.guards().maxMergeWindowRows());
        }
    }

    /**
     * TopN 执行前提（T7 执行层落地后的静态裁决面，全部执行前拒绝）：①窗口为整数字面量
     * （参数标记需执行层参数注入，v1 不做——拒绝可读优于运行期半支持）；②窗口总量过
     * guards.maxMergeWindowRows；③LIMIT/OFFSET 书写形态（FETCH 形态无下推模板）；④窗口序
     * 键可归并（用户排序键或分组键，见 MergeSpec——标签可寻才可比）。
     */
    private static void requireTopN(final BoundStatement bound, final MetadataSnapshot snapshot) {
        final BoundStatement.Shape shape = bound.shape();
        if (!shape.limitLiteral) {
            throw new UnsupportedQueryException("LIMIT", -1,
                    "动态 LIMIT 窗口（参数标记/非整数字面量）跨分片需参数注入（v1 静态裁决不支持）", "");
        }
        requireTopNWindow(shape, snapshot);
        final SelectStatement select = selectOf(bound);
        if (Limit.Form.FETCH == select.limit().form()) {
            throw new UnsupportedQueryException("LIMIT", -1,
                    "FETCH 形态分页跨分片未支持（LIMIT/OFFSET 形态可用，下推模板仅 LIMIT 族）", "");
        }
        if (null != select.orderBy()) {
            MergeSpec.orderKeys(select);
        } else {
            MergeSpec.groupKeys(select);
        }
    }

    private static SelectStatement selectOf(final BoundStatement bound) {
        final Statement statement = bound.statement();
        if (!(statement instanceof SelectStatement)) {
            throw new IllegalStateException("归并裁决只消费 SELECT（不变量破坏）: "
                    + statement.getClass().getSimpleName());
        }
        return (SelectStatement) statement;
    }

    private static String topNDescription(final BoundStatement.Shape shape) {
        return shape.limitLiteral ? "下推 LIMIT offset+limit，外层取窗口"
                : "动态 LIMIT 窗口（参数标记），运行期校验 maxMergeWindowRows";
    }

    private static MergePlan plan(final MergePlan.Kind kind, final String description,
            final List<DataNode> targets, final MetadataSnapshot snapshot, final RouteResult route) {
        return new MergePlan(kind, description, targets, snapshot.guards(),
                route.locatedPartitions(), route.fallbackPartitions());
    }
}
