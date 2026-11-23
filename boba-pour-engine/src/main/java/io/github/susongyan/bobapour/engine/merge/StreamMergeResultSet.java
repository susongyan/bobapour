package io.github.susongyan.bobapour.engine.merge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.PriorityQueue;

import io.github.susongyan.bobapour.engine.execute.QueryFanout;
import io.github.susongyan.bobapour.engine.merge.MergeSpec.OrderKey;

/**
 * 流式归并（T7，矩阵"ORDER BY"格）：各分片已按同序排序下推，外层以 k 路优先队列
 * 归并——<b>内存上界 = 分片数 × (1 行预读 + 排序键值)</b>，永不整缓冲。排序键按
 * 列标签在分片结果集上取值（{@link MergeSpec} 已静态保证标签可寻）；null 视为最小
 * （MySQL 语义），方向翻转由键声明施加；同键平局按分片序定序（确定性）。
 *
 * <p>游标纪律：入队分片的游标<b>停在其头行上</b>（待交付行），交付后延迟到下一次
 * next() 才推进——活跃行在值读取期间位置不被预读破坏，取值零拷贝委托底层游标。</p>
 */
public class StreamMergeResultSet extends MergeResultSet {

    private final List<OrderKey> keys;
    private final int[][] keyIndexByShard;
    private final Object[][] keyValues;
    private final PriorityQueue<Integer> queue;

    /** 上次交付行所在分片（待推进；-1 = 无）。 */
    private int pending = -1;

    public StreamMergeResultSet(final QueryFanout fanout, final List<OrderKey> keys) throws SQLException {
        super(fanout);
        this.keys = keys;
        this.keyIndexByShard = new int[fanout.shards().size()][];
        this.keyValues = new Object[fanout.shards().size()][];
        this.queue = new PriorityQueue<Integer>(fanout.shards().size(), new HeadOrder());
        for (int i = 0; i < fanout.shards().size(); i++) {
            keyIndexByShard[i] = resolveKeys(fanout.shards().get(i).resultSet());
            keyValues[i] = new Object[keys.size()];
            if (fanout.shards().get(i).resultSet().next()) {
                loadKeys(i);
                queue.add(Integer.valueOf(i));
            }
        }
    }

    @Override
    public boolean next() throws SQLException {
        if (pending >= 0) {
            advance(pending);
            pending = -1;
        }
        if (queue.isEmpty()) {
            active = null;
            afterLast = true;
            return false;
        }
        final int shard = queue.poll().intValue();
        active = fanout.shards().get(shard).resultSet();
        pending = shard;
        yielded();
        return true;
    }

    private void advance(final int shard) throws SQLException {
        final ResultSet resultSet = fanout.shards().get(shard).resultSet();
        if (resultSet.next()) {
            loadKeys(shard);
            queue.add(Integer.valueOf(shard));
        }
    }

    private void loadKeys(final int shard) throws SQLException {
        final ResultSet resultSet = fanout.shards().get(shard).resultSet();
        for (int k = 0; k < keys.size(); k++) {
            keyValues[shard][k] = resultSet.getObject(keyIndexByShard[shard][k]);
        }
    }

    /** 队列序：逐键比较（null 最小、方向翻转），全平局按分片序。 */
    private int compareHeads(final Integer left, final Integer right) throws SQLException {
        for (int k = 0; k < keys.size(); k++) {
            int cmp = MergeValues.compareKeys(keyValues[left.intValue()][k], keyValues[right.intValue()][k]);
            if (0 != cmp) {
                return keys.get(k).descending() ? -cmp : cmp;
            }
        }
        return Integer.compare(left.intValue(), right.intValue());
    }

    private int[] resolveKeys(final ResultSet resultSet) throws SQLException {
        final int[] indexes = new int[keys.size()];
        for (int k = 0; k < keys.size(); k++) {
            try {
                indexes[k] = resultSet.findColumn(keys.get(k).label());
            } catch (final SQLException e) {
                throw new SQLException("排序键标签在分片结果集不可解析（分片列集不一致）: "
                        + keys.get(k).label(), e);
            }
        }
        return indexes;
    }

    /** 比较器桥（优先队列不接受受检异常，收口为 IllegalStateException——打开即放弃）。 */
    private final class HeadOrder implements java.util.Comparator<Integer> {

        @Override
        public int compare(final Integer left, final Integer right) {
            try {
                return compareHeads(left, right);
            } catch (final SQLException e) {
                throw new IllegalStateException("流式归并比较失败（连接/类型异常）", e);
            }
        }
    }
}
