package io.github.susongyan.bobapour.engine.merge;

import java.sql.SQLException;
import java.util.List;

import io.github.susongyan.bobapour.engine.execute.QueryFanout;
import io.github.susongyan.bobapour.engine.merge.MergeSpec.OrderKey;

/**
 * TopN 归并（T7，矩阵"ORDER BY + LIMIT/OFFSET"格）：流式归并之上的窗口取行——跳过
 * offset 行、取 count 行。<b>内存上界 = 分片数 × 1 行预读</b>（改写层已把各分片下推为
 * {@code LIMIT offset+count}，窗口总量受 guards.maxMergeWindowRows 裁决期封顶）；
 * 消费行数 ≤ offset+count + 分片数预读，永不整缓冲。
 */
public final class TopNResultSet extends StreamMergeResultSet {

    private long toSkip;
    private long remaining;

    public TopNResultSet(final QueryFanout fanout, final List<OrderKey> keys, final long offset,
            final long count) throws SQLException {
        super(fanout, keys);
        this.toSkip = offset;
        this.remaining = count;
    }

    @Override
    public boolean next() throws SQLException {
        while (toSkip > 0L) {
            toSkip--;
            if (!super.next()) {
                remaining = 0L;
                break;
            }
        }
        if (remaining <= 0L || !super.next()) {
            remaining = 0L;
            active = null;
            afterLast = true;
            return false;
        }
        remaining--;
        return true;
    }
}
