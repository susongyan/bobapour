package io.github.susongyan.bobapour.engine.merge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import io.github.susongyan.bobachop.ast.SelectStatement;
import io.github.susongyan.bobapour.engine.bind.BoundStatement;
import io.github.susongyan.bobapour.engine.execute.QueryFanout;
import io.github.susongyan.bobapour.engine.merge.MergeSpec.OrderKey;

/**
 * 归并执行入口（T7）：按 {@link MergePlan.Kind} 把已打开的分片游标装配为对应归并结果集。
 * 策略入参（排序键/聚合名/TopN 窗口）经 {@link MergeSpec} 从同一 AST 提取——裁决层可归并性
 * 校验与执行层构造所见一致，无双轨。GROUP BY + LIMIT 无用户排序时窗口序取分组键
 * （改写层已按同清单补 ORDER BY 下推，两边键序一致）。
 */
public final class MergeExecutor {

    private MergeExecutor() {
    }

    /** 装配归并结果集（持有 fanout，关闭级联归还分片资源）。 */
    public static ResultSet execute(final MergePlan plan, final BoundStatement bound,
            final QueryFanout fanout) throws SQLException {
        if (!(bound.statement() instanceof SelectStatement)) {
            throw new IllegalArgumentException("归并只消费 SELECT（裁决不变量破坏）: "
                    + bound.statement().getClass().getSimpleName());
        }
        final SelectStatement select = (SelectStatement) bound.statement();
        switch (plan.kind()) {
            case PASSTHROUGH:
            case CONCAT:
                return new ConcatResultSet(fanout);
            case STREAM_MERGE:
                return new StreamMergeResultSet(fanout, MergeSpec.orderKeys(select));
            case TOP_N:
                final List<OrderKey> keys = null != select.orderBy()
                        ? MergeSpec.orderKeys(select) : MergeSpec.groupKeys(select);
                final long[] window = MergeSpec.limitWindow(select);
                return new TopNResultSet(fanout, keys, window[0], window[1]);
            case REASSEMBLE:
                return new ReassembleResultSet(fanout, MergeSpec.aggregates(select));
            default:
                throw new IllegalArgumentException("非查询归并策略: " + plan.kind());
        }
    }
}
