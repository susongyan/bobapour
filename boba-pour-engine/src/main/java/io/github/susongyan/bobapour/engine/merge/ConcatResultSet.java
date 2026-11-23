package io.github.susongyan.bobapour.engine.merge;

import java.sql.ResultSet;
import java.sql.SQLException;

import io.github.susongyan.bobapour.engine.execute.QueryFanout;

/**
 * 拼接归并（T7，矩阵"无 ORDER BY / 聚合 / LIMIT"格）：分片序即行序（路由稳定序），
 * 逐分片耗尽后前进——无排序时跨分片行序本无语义，拼接为忠实策略；GROUP BY 含分片键
 * （组不跨分片）同走本策略：组完整性由路由保证，组间序即分片序。
 */
public final class ConcatResultSet extends MergeResultSet {

    private int index;

    public ConcatResultSet(final QueryFanout fanout) {
        super(fanout);
    }

    @Override
    public boolean next() throws SQLException {
        while (index < fanout.shards().size()) {
            final ResultSet shard = fanout.shards().get(index).resultSet();
            if (shard.next()) {
                active = shard;
                yielded();
                return true;
            }
            index++;
        }
        active = null;
        afterLast = true;
        return false;
    }
}
