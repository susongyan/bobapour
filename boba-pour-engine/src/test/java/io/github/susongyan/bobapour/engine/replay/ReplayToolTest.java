package io.github.susongyan.bobapour.engine.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.susongyan.bobapour.config.ConfigLoader;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;

/**
 * 语料回放黄金用例（T4 DoD："语料回放产出落格分布/拒绝清单报告（含 D13 分区定位/回退分布）"）：
 * 混合语料一次回放 → 落格计数、拒绝清单逐条、D13 分布、render() 文本齐备——零执行零成本。
 */
final class ReplayToolTest {

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
            "")));
    }

    @Test
    void 混合语料回放_落格与拒绝清单与D13分布() {
        final List<ReplayCase> cases = Arrays.asList(
                new ReplayCase("q1", "SELECT * FROM t_bench WHERE user_id = 1"),
                new ReplayCase("q2", "SELECT * FROM t_bench"),
                new ReplayCase("q3", "SELECT * FROM t_bench ORDER BY amount LIMIT 5"),
                new ReplayCase("q4", "SELECT COUNT(*) FROM t_bench"),
                new ReplayCase("q5", "SELECT DISTINCT user_id FROM t_bench"),
                new ReplayCase("q6", "SELECT * FROM t_bench LIMIT 5"),
                new ReplayCase("q7", "UPDATE t_bench SET amount = 1"),
                new ReplayCase("q8", "INSERT INTO t_bench (user_id, amount) VALUES (0, 1), (1, 2)"),
                new ReplayCase("q9", "INSERT INTO t_part (created_at, v) VALUES ('2025-06-01', 1)"),
                new ReplayCase("q10", "INSERT INTO t_part (created_at, v) VALUES ('2030-01-01', 1)"),
                new ReplayCase("q11", "SELECT * FROM t_pure"));
        final ReplayReport report = ReplayTool.run(cases, snapshot);
        assertEquals(11, report.total());
        final Map<String, Integer> cells = report.cells();
        assertEquals(Integer.valueOf(3), cells.get("单分片透传"));
        assertEquals(Integer.valueOf(1), cells.get("跨分片拼接"));
        assertEquals(Integer.valueOf(1), cells.get("TopN"));
        assertEquals(Integer.valueOf(1), cells.get("聚合重组装"));
        assertEquals(Integer.valueOf(1), cells.get("写影响行数求和"));
        assertEquals(Integer.valueOf(1), cells.get("INSERT拆行"));
        assertEquals(3, report.rejections().size());
        assertEquals(1, report.locatedPartitions());
        assertEquals(1, report.fallbackPartitions());
        final String text = report.render();
        assertTrue(text.contains("落格分布（11 条）"), text);
        assertTrue(text.contains("[q5] DISTINCT"), text);
        assertTrue(text.contains("[q6] LIMIT"), text);
        assertTrue(text.contains("[q11] 表级开关"), text);
        assertTrue(text.contains("定位 1 次，回退 1 次"), text);
    }
}
