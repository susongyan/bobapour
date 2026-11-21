package io.github.susongyan.bobapour.engine.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.susongyan.bobapour.config.ConfigLoader;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.engine.DecisionChain;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * 归并矩阵逐格裁决黄金用例（T4 DoD："每 ❌ 格拒绝行为有回归用例"）：✅ 格落格正确 + ❌ 格
 * 拒绝四要素（构造名/命中规则/当前值-上限值）齐备 + 三级能力模型边界（透传/矩阵/拒绝）+
 * guards 双防线 + 写路径 D9/D10 + D13 分区定位/回退。全链走 {@link DecisionChain}（对外唯一入口）。
 */
final class MergePlannerTest {

    private static MetadataSnapshot snapshot;

    @BeforeAll
    static void 装配快照() {
        snapshot = MetadataSnapshot.of(ConfigLoader.load(String.join("\n",
            "datasources:",
            "  ds_0:",
            "    className: com.example.X",
            "  ds_1:",
            "    className: com.example.X",
            "sharding:",
            "  tables:",
            "    t_bench:",
            "      actualDataNodes: ds_${0..1}.t_bench",
            "      databaseStrategy:",
            "        shardingColumn: user_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      crossShardQuery: WHITELIST",
            "    t_watch:",
            "      actualDataNodes: ds_${0..1}.t_watch",
            "      databaseStrategy:",
            "        shardingColumn: user_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      crossShardQuery: WHITELIST",
            "    t_pair_a:",
            "      actualDataNodes: ds_${0..1}.t_pair_a",
            "      databaseStrategy:",
            "        shardingColumn: user_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      crossShardQuery: WHITELIST",
            "    t_pair_b:",
            "      actualDataNodes: ds_${0..1}.t_pair_b",
            "      databaseStrategy:",
            "        shardingColumn: user_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      crossShardQuery: WHITELIST",
            "    t_grid:",
            "      actualDataNodes: ds_${0..1}.t_grid_${0..3}",
            "      databaseStrategy:",
            "        shardingColumn: user_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      tableStrategy:",
            "        shardingColumn: grid_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 4",
            "      crossShardQuery: WHITELIST",
            "    t_pure:",
            "      actualDataNodes: ds_${0..1}.t_pure",
            "      databaseStrategy:",
            "        shardingColumn: user_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "    t_part:",
            "      actualDataNodes: ds_0.t_part",
            "      partition:",
            "        key: created_at",
            "        type: RANGE",
            "        partitions:",
            "          - name: p2025",
            "            upper: '2026-01-01'",
            "          - name: p2026",
            "            upper: '2027-01-01'",
            "broadcastTables:",
            "  - t_config",
            "bindingTables:",
            "  - - t_pair_a",
            "    - t_pair_b",
            "guards:",
            "  maxShardsPerQuery: 4",
            "")));
    }

    private static MergePlan decide(final String sql) {
        return DecisionChain.decide(sql, snapshot);
    }

    private static UnsupportedQueryException reject(final String sql) {
        return assertThrows(UnsupportedQueryException.class, () -> decide(sql));
    }

    // ---------- ✅ 格：落格正确 ----------

    @Test
    void 等值命中单分片_透传() {
        assertEquals(MergePlan.Kind.PASSTHROUGH, decide("SELECT * FROM t_bench WHERE user_id = 1").kind());
        assertEquals(1, decide("SELECT * FROM t_bench WHERE user_id = 1").targets().size());
    }

    @Test
    void 无形态跨分片_拼接() {
        assertEquals(MergePlan.Kind.CONCAT, decide("SELECT * FROM t_bench").kind());
    }

    @Test
    void 排序跨分片_流式归并() {
        assertEquals(MergePlan.Kind.STREAM_MERGE, decide("SELECT * FROM t_bench ORDER BY amount").kind());
    }

    @Test
    void 排序加分页_TopN() {
        final MergePlan plan = decide("SELECT * FROM t_bench ORDER BY amount LIMIT 5");
        assertEquals(MergePlan.Kind.TOP_N, plan.kind());
        assertTrue(plan.description().contains("下推"));
    }

    @Test
    void 聚合无分组_重组装() {
        assertEquals(MergePlan.Kind.REASSEMBLE, decide("SELECT COUNT(*) FROM t_bench").kind());
        assertEquals(MergePlan.Kind.REASSEMBLE, decide("SELECT SUM(amount), MAX(amount) FROM t_bench").kind());
    }

    @Test
    void 分组含分片键_拼接() {
        assertEquals(MergePlan.Kind.CONCAT, decide("SELECT user_id, COUNT(*) FROM t_bench GROUP BY user_id").kind());
    }

    @Test
    void 绑定表JOIN_拼接() {
        final MergePlan plan = decide(
                "SELECT * FROM t_pair_a JOIN t_pair_b ON t_pair_a.user_id = t_pair_b.user_id");
        assertEquals(MergePlan.Kind.CONCAT, plan.kind());
    }

    @Test
    void 广播表读_透通() {
        assertEquals(MergePlan.Kind.PASSTHROUGH, decide("SELECT * FROM t_config").kind());
    }

    @Test
    void 未托管表整句_直通() {
        assertEquals(MergePlan.Kind.PASSTHROUGH, decide("SELECT * FROM t_local").kind());
        assertEquals(MergePlan.Kind.PASSTHROUGH, decide("SELECT 1").kind());
    }

    // ---------- ❌ 格：拒绝行为回归（四要素齐备） ----------

    @Test
    void 拒绝_LIMIT无排序() {
        final UnsupportedQueryException e = reject("SELECT * FROM t_bench LIMIT 5");
        assertEquals("LIMIT", e.construct());
        assertTrue(e.rule().contains("非确定"));
    }

    @Test
    void 拒绝_DISTINCT跨分片() {
        assertEquals("DISTINCT", reject("SELECT DISTINCT user_id FROM t_bench").construct());
    }

    @Test
    void 拒绝_GROUP_BY不含分片键() {
        final UnsupportedQueryException e = reject("SELECT region, COUNT(*) FROM t_bench GROUP BY region");
        assertEquals("GROUP BY", e.construct());
        assertTrue(e.rule().contains("不含分片键"));
    }

    @Test
    void 拒绝_GROUP_BY表达式() {
        assertEquals("GROUP BY", reject("SELECT * FROM t_bench GROUP BY UPPER(region)").construct());
    }

    @Test
    void 拒绝_AVG() {
        assertEquals("AVG", reject("SELECT AVG(amount) FROM t_bench").construct());
    }

    @Test
    void 拒绝_相关子查询跨分片() {
        assertEquals("子查询", reject(
                "SELECT * FROM t_bench WHERE user_id IN (SELECT user_id FROM t_watch)").construct());
    }

    @Test
    void 拒绝_派生表跨分片() {
        assertEquals("派生表", reject("SELECT * FROM (SELECT user_id FROM t_bench) AS d").construct());
    }

    @Test
    void 拒绝_UNION跨分片() {
        assertEquals("UNION", reject(
                "SELECT user_id FROM t_bench UNION SELECT user_id FROM t_watch").construct());
    }

    @Test
    void 拒绝_JOIN非绑定跨分片() {
        final UnsupportedQueryException e = reject(
                "SELECT * FROM t_bench JOIN t_watch ON t_bench.user_id = t_watch.user_id");
        assertEquals("JOIN", e.construct());
        assertTrue(e.rule().contains("非绑定"));
    }

    @Test
    void 拒绝_表级REJECT开关() {
        final UnsupportedQueryException e = reject("SELECT * FROM t_pure");
        assertEquals("表级开关", e.construct());
        assertTrue(e.detail().contains("t_pure"));
    }

    @Test
    void 表级REJECT_单分片仍透传() {
        assertEquals(MergePlan.Kind.PASSTHROUGH, decide("SELECT * FROM t_pure WHERE user_id = 0").kind());
    }

    @Test
    void 拒绝_受管未托管混用() {
        final UnsupportedQueryException e = reject("SELECT * FROM t_bench JOIN t_local ON t_bench.x = t_local.x");
        assertEquals("表", e.construct());
        assertTrue(e.detail().contains("t_local"));
    }

    // ---------- guards（D3） ----------

    @Test
    void 拒绝_分片数超限_带当前值上限值() {
        final UnsupportedQueryException e = reject("SELECT * FROM t_grid");
        assertEquals("guards.maxShardsPerQuery", e.construct());
        assertTrue(e.detail().contains("当前值=8") && e.detail().contains("上限值=4"), e.detail());
    }

    @Test
    void 拒绝_TopN窗口超限() {
        final UnsupportedQueryException e = reject("SELECT * FROM t_bench ORDER BY amount LIMIT 6000 OFFSET 5000");
        assertEquals("guards.maxMergeWindowRows", e.construct());
        assertTrue(e.detail().contains("当前值=11000") && e.detail().contains("上限值=10000"), e.detail());
    }

    // ---------- 写路径（D9/D10）与 D13 ----------

    @Test
    void 更新单分片_透传() {
        assertEquals(MergePlan.Kind.PASSTHROUGH, decide("UPDATE t_bench SET amount = 1 WHERE user_id = 0").kind());
    }

    @Test
    void 更新多分片_影响行数求和() {
        assertEquals(MergePlan.Kind.WRITE_ROW_SUM, decide("UPDATE t_bench SET amount = 1").kind());
    }

    @Test
    void 删除等值_透传() {
        assertEquals(MergePlan.Kind.PASSTHROUGH, decide("DELETE FROM t_bench WHERE user_id = 1").kind());
    }

    @Test
    void 插入多行_拆行路由() {
        assertEquals(MergePlan.Kind.WRITE_SPLIT,
                decide("INSERT INTO t_bench (user_id, amount) VALUES (0, 1), (1, 2)").kind());
    }

    @Test
    void 插入单行_透传() {
        assertEquals(MergePlan.Kind.PASSTHROUGH,
                decide("INSERT INTO t_bench (user_id, amount) VALUES (0, 1)").kind());
    }

    @Test
    void 拒绝_插入行缺分片键() {
        final UnsupportedQueryException e = reject("INSERT INTO t_bench (amount) VALUES (1)");
        assertEquals("INSERT", e.construct());
        assertTrue(e.rule().contains("行缺分片键"));
    }

    @Test
    void 广播表写_全副本() {
        assertEquals(MergePlan.Kind.BROADCAST_WRITE,
                decide("INSERT INTO t_config (k, v) VALUES ('a', 1)").kind());
    }

    @Test
    void D13_分区定位与回退() {
        final MergePlan located = decide("INSERT INTO t_part (created_at, v) VALUES ('2025-06-01', 1)");
        assertEquals(1, located.locatedPartitions());
        assertEquals(0, located.fallbackPartitions());
        final MergePlan missed = decide("INSERT INTO t_part (created_at, v) VALUES ('2030-01-01', 1)");
        assertEquals(0, missed.locatedPartitions());
        assertEquals(1, missed.fallbackPartitions());
        final MergePlan mixed = decide(
                "INSERT INTO t_part (created_at, v) VALUES ('2025-06-01', 1), ('2026-06-01', 2), ('2030-01-01', 3)");
        assertEquals(2, mixed.locatedPartitions());
        assertEquals(1, mixed.fallbackPartitions());
        assertEquals(1, decide("UPDATE t_part SET v = 1 WHERE created_at = '2026-06-01'").locatedPartitions());
        assertEquals(1, decide("UPDATE t_part SET v = 1").fallbackPartitions());
    }

    // ---------- 链路入口 ----------

    @Test
    void 拒绝_解析层包装() {
        assertEquals("解析语法", reject("SELEC 1").construct());
    }

    @Test
    void 拒绝_MOD仅整数() {
        final UnsupportedQueryException e = reject("SELECT * FROM t_bench WHERE user_id = 'abc'");
        assertEquals("分片算法", e.construct());
        assertTrue(e.rule().contains("整数"));
    }
}
