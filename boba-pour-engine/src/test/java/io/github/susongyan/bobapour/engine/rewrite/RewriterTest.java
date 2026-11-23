package io.github.susongyan.bobapour.engine.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.susongyan.bobachop.api.BobaChop;
import io.github.susongyan.bobachop.dialect.DialectProfile;
import io.github.susongyan.bobapour.config.ConfigLoader;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.engine.bind.Binder;
import io.github.susongyan.bobapour.engine.bind.BoundStatement;
import io.github.susongyan.bobapour.engine.merge.MergePlan;
import io.github.susongyan.bobapour.engine.merge.MergePlanner;
import io.github.susongyan.bobapour.engine.route.RouteResult;
import io.github.susongyan.bobapour.engine.route.Router;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * T6 黄金用例——改写后 SQL 逐字断言（DoD：SELECT/INSERT/UPDATE/DELETE 主干 + D13 分区改写与
 * 回退各有黄金用例）。全链走真实决策链（bind → route → MergePlan 裁决放行后才改写），
 * 拒绝路径断言执行前显式异常。D13 开启表（t_part/t_gp）不走存量对拍（设计如此），以黄金锁死。
 */
final class RewriterTest {

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
            "    t_gp:",
            "      actualDataNodes: ds_0.t_gp_${0..1}",
            "      tableStrategy:",
            "        shardingColumn: grid_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      partition:",
            "        key: created_at",
            "        type: RANGE",
            "        partitions:",
            "          - name: p2025",
            "            upper: '2026-01-01'",
            "          - name: p2026",
            "            upper: '2027-01-01'",
            "    t_bind_a:",
            "      actualDataNodes: ds_${0..1}.t_bind_${0..1}",
            "      databaseStrategy:",
            "        shardingColumn: user_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      tableStrategy:",
            "        shardingColumn: bind_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      crossShardQuery: WHITELIST",
            "    t_bind_b:",
            "      actualDataNodes: ds_${0..1}.t_bind_${0..1}",
            "      databaseStrategy:",
            "        shardingColumn: user_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      tableStrategy:",
            "        shardingColumn: other_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      crossShardQuery: WHITELIST",
            "broadcastTables:",
            "  - t_config",
            "bindingTables:",
            "  - - t_bind_a",
            "    - t_bind_b",
            "guards:",
            "  maxShardsPerQuery: 16",
            "")));
    }

    // ---------- SELECT 主干 ----------

    @Test
    void SELECT_单分片_物理表名替换() {
        assertEquals(Arrays.asList("ds_0 <= SELECT * FROM t_grid_1 WHERE user_id = 0 AND grid_id = 1"),
                units(rw("SELECT * FROM t_grid WHERE user_id = 0 AND grid_id = 1")));
    }

    @Test
    void SELECT_别名保留() {
        assertEquals(Arrays.asList("ds_0 <= SELECT a.id FROM t_grid_2 AS a WHERE a.user_id = 0 AND a.grid_id = 2"),
                units(rw("SELECT a.id FROM t_grid AS a WHERE a.user_id = 0 AND a.grid_id = 2")));
    }

    @Test
    void SELECT_库限定前缀消解() {
        assertEquals(Arrays.asList("ds_1 <= SELECT * FROM t_grid_3 WHERE user_id = 1 AND grid_id = 3"),
                units(rw("SELECT * FROM db0.t_grid WHERE user_id = 1 AND grid_id = 3")));
    }

    @Test
    void SELECT_限定名内块注释_偏移对齐不破() {
        assertEquals(Arrays.asList("ds_0 <= SELECT * FROM t_grid_3 WHERE user_id = 0 AND grid_id = 3"),
                units(rw("SELECT * FROM db0 /* 注释 */ . t_grid WHERE user_id = 0 AND grid_id = 3")));
    }

    @Test
    void SELECT_跨分片_逐节点一语句() {
        assertEquals(Arrays.asList(
                "ds_0 <= SELECT id FROM t_grid_2 WHERE user_id IN (0, 1) AND grid_id = 2",
                "ds_1 <= SELECT id FROM t_grid_2 WHERE user_id IN (0, 1) AND grid_id = 2"),
                units(rw("SELECT id FROM t_grid WHERE user_id IN (0, 1) AND grid_id = 2")));
    }

    @Test
    void SELECT_表维全量_四语句同库() {
        assertEquals(Arrays.asList(
                "ds_0 <= SELECT * FROM t_grid_0 WHERE user_id = 0",
                "ds_0 <= SELECT * FROM t_grid_1 WHERE user_id = 0",
                "ds_0 <= SELECT * FROM t_grid_2 WHERE user_id = 0",
                "ds_0 <= SELECT * FROM t_grid_3 WHERE user_id = 0"),
                units(rw("SELECT * FROM t_grid WHERE user_id = 0")));
    }

    @Test
    void SELECT_子查询单分片_整体改写() {
        assertEquals(Arrays.asList("ds_0 <= SELECT * FROM t_grid_1 WHERE user_id = 0 AND grid_id = 1"
                + " AND id IN (SELECT id FROM t_grid_1 WHERE user_id = 0 AND grid_id = 1)"),
                units(rw("SELECT * FROM t_grid WHERE user_id = 0 AND grid_id = 1"
                        + " AND id IN (SELECT id FROM t_grid WHERE user_id = 0 AND grid_id = 1)")));
    }

    @Test
    void SELECT_insert_select_单分片_目标与源同改() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_grid_1 (user_id, grid_id, v) SELECT user_id, grid_id, v FROM t_grid_1 WHERE user_id = 0 AND grid_id = 1"),
                units(rw("INSERT INTO t_grid (user_id, grid_id, v) SELECT user_id, grid_id, v FROM t_grid"
                        + " WHERE user_id = 0 AND grid_id = 1")));
    }

    // ---------- 写路径（D9） ----------

    @Test
    void UPDATE_单分片_别名保留() {
        assertEquals(Arrays.asList("ds_1 <= UPDATE t_grid_3 g SET v = 1 WHERE g.user_id = 1 AND g.grid_id = 3"),
                units(rw("UPDATE t_grid g SET v = 1 WHERE g.user_id = 1 AND g.grid_id = 3")));
    }

    @Test
    void UPDATE_多分片_影响行数求和面_逐节点一语句() {
        assertEquals(Arrays.asList(
                "ds_0 <= UPDATE t_grid_0 SET v = 1 WHERE user_id = 0",
                "ds_0 <= UPDATE t_grid_1 SET v = 1 WHERE user_id = 0",
                "ds_0 <= UPDATE t_grid_2 SET v = 1 WHERE user_id = 0",
                "ds_0 <= UPDATE t_grid_3 SET v = 1 WHERE user_id = 0"),
                units(rw("UPDATE t_grid SET v = 1 WHERE user_id = 0")));
    }

    @Test
    void DELETE_单分片() {
        assertEquals(Arrays.asList("ds_1 <= DELETE FROM t_grid_0 WHERE user_id = 1 AND grid_id = 0"),
                units(rw("DELETE FROM t_grid WHERE user_id = 1 AND grid_id = 0")));
    }

    @Test
    void INSERT_拆行_跨节点按首现序分组() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_grid_0 (user_id, grid_id, v) VALUES (0, 0, 'a')",
                "ds_1 <= INSERT INTO t_grid_1 (user_id, grid_id, v) VALUES (1, 1, 'b')",
                "ds_0 <= INSERT INTO t_grid_3 (user_id, grid_id, v) VALUES (0, 3, 'c')"),
                units(rw("INSERT INTO t_grid (user_id, grid_id, v) VALUES (0, 0, 'a'), (1, 1, 'b'), (0, 3, 'c')")));
    }

    @Test
    void INSERT_同节点多行_整句原文装配不切片() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_grid_1 (user_id, grid_id, v) VALUES (0, 1, 'a'), (0, 1, 'b')"),
                units(rw("INSERT INTO t_grid (user_id, grid_id, v) VALUES (0, 1, 'a'), (0, 1, 'b')")));
    }

    @Test
    void INSERT_拆行_尾随子句原样随行组() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_bench (user_id, v) VALUES (0, 'a') ON DUPLICATE KEY UPDATE v = 'z'",
                "ds_1 <= INSERT INTO t_bench (user_id, v) VALUES (1, 'b') ON DUPLICATE KEY UPDATE v = 'z'"),
                units(rw("INSERT INTO t_bench (user_id, v) VALUES (0, 'a'), (1, 'b')"
                        + " ON DUPLICATE KEY UPDATE v = 'z'")));
    }

    // ---------- 绑定组对齐推导 ----------

    @Test
    void 绑定组JOIN_单侧定位_表位传播() {
        assertEquals(Arrays.asList(
                "ds_1 <= SELECT a.id FROM t_bind_1 a JOIN t_bind_1 b ON a.bind_id = b.bind_id WHERE a.user_id = 1 AND a.bind_id = 1"),
                units(rw("SELECT a.id FROM t_bind_a a JOIN t_bind_b b ON a.bind_id = b.bind_id"
                        + " WHERE a.user_id = 1 AND a.bind_id = 1")));
    }

    @Test
    void 绑定组JOIN_全量_按表位扫描() {
        assertEquals(Arrays.asList(
                "ds_0 <= SELECT a.id FROM t_bind_0 a JOIN t_bind_0 b ON a.bind_id = b.bind_id",
                "ds_0 <= SELECT a.id FROM t_bind_1 a JOIN t_bind_1 b ON a.bind_id = b.bind_id",
                "ds_1 <= SELECT a.id FROM t_bind_0 a JOIN t_bind_0 b ON a.bind_id = b.bind_id",
                "ds_1 <= SELECT a.id FROM t_bind_1 a JOIN t_bind_1 b ON a.bind_id = b.bind_id"),
                units(rw("SELECT a.id FROM t_bind_a a JOIN t_bind_b b ON a.bind_id = b.bind_id")));
    }

    @Test
    void 绑定组JOIN_同库表位冲突_执行前拒绝() {
        final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                () -> rw("SELECT a.id FROM t_bind_a a JOIN t_bind_b b ON a.bind_id = b.bind_id"
                        + " WHERE a.user_id = 0 AND a.bind_id = 0 AND b.user_id = 0 AND b.other_id = 1"));
        assertTrue(e.getMessage().contains("绑定组分片键值冲突"), e.getMessage());
    }

    // ---------- 广播与直通 ----------

    @Test
    void 广播写_全副本_原文不改() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_config (k, v) VALUES ('a', 1)",
                "ds_1 <= INSERT INTO t_config (k, v) VALUES ('a', 1)"),
                units(rw("INSERT INTO t_config (k, v) VALUES ('a', 1)")));
    }

    @Test
    void 广播读_取样首数据源_原文不改() {
        assertEquals(Arrays.asList("ds_0 <= SELECT * FROM t_config"),
                units(rw("SELECT * FROM t_config")));
    }

    @Test
    void 未托管表_直通首数据源_原文不改() {
        assertEquals(Arrays.asList("ds_0 <= SELECT * FROM t_unknown"),
                units(rw("SELECT * FROM t_unknown")));
    }

    @Test
    void 大小写敏感_与bind层一致_未识别直通() {
        assertEquals(Arrays.asList("ds_0 <= SELECT * FROM T_GRID WHERE user_id = 0"),
                units(rw("SELECT * FROM T_GRID WHERE user_id = 0")));
    }

    // ---------- D13 分区定位改写（默认关的表不声明分区即无此面） ----------

    @Test
    void D13_MySQL_INSERT_定位_选择子落在表名后() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_part PARTITION (p2025) (created_at, v) VALUES ('2025-06-01', 1)"),
                units(rw("INSERT INTO t_part (created_at, v) VALUES ('2025-06-01', 1)")));
    }

    @Test
    void D13_MySQL_INSERT_边界miss_回退父表() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_part (created_at, v) VALUES ('2030-01-01', 1)"),
                units(rw("INSERT INTO t_part (created_at, v) VALUES ('2030-01-01', 1)")));
    }

    @Test
    void D13_MySQL_INSERT_多行跨分区_整组回退() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_part (created_at, v) VALUES ('2025-06-01', 1), ('2026-06-01', 2)"),
                units(rw("INSERT INTO t_part (created_at, v) VALUES ('2025-06-01', 1), ('2026-06-01', 2)")));
    }

    @Test
    void D13_MySQL_UPDATE_定位() {
        assertEquals(Arrays.asList(
                "ds_0 <= UPDATE t_part PARTITION (p2025) SET v = 1 WHERE created_at = '2025-06-01'"),
                units(rw("UPDATE t_part SET v = 1 WHERE created_at = '2025-06-01'")));
    }

    @Test
    void D13_MySQL_UPDATE_SET含分区键_回退() {
        final RewriteResult result = rw("UPDATE t_part SET created_at = '2026-06-01' WHERE created_at = '2025-06-01'");
        assertEquals(1, result.units().size());
        assertTrue(0 > result.units().get(0).sql().indexOf("PARTITION"), result.units().get(0).sql());
    }

    @Test
    void D13_MySQL_DELETE_定位() {
        assertEquals(Arrays.asList(
                "ds_0 <= DELETE FROM t_part PARTITION (p2026) WHERE created_at = '2026-06-01'"),
                units(rw("DELETE FROM t_part WHERE created_at = '2026-06-01'")));
    }

    @Test
    void D13_PG_子表名替换_无表维分片() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO p2025 (created_at, v) VALUES ('2025-06-01', 1)"),
                units(rw("INSERT INTO t_part (created_at, v) VALUES ('2025-06-01', 1)",
                        DialectProfile.POSTGRESQL)));
    }

    @Test
    void D13_PG_表维分片组合_子表名不可推导_回退() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_gp_1 (grid_id, created_at, v) VALUES (1, '2025-06-01', 'x')"),
                units(rw("INSERT INTO t_gp (grid_id, created_at, v) VALUES (1, '2025-06-01', 'x')",
                        DialectProfile.POSTGRESQL)));
    }

    @Test
    void D13_MySQL_表维分片组合_选择子照常追加() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_gp_1 PARTITION (p2025) (grid_id, created_at, v) VALUES (1, '2025-06-01', 'x')"),
                units(rw("INSERT INTO t_gp (grid_id, created_at, v) VALUES (1, '2025-06-01', 'x')")));
    }

    @Test
    void D13_Oracle_无模板_不改写() {
        assertEquals(Arrays.asList(
                "ds_0 <= INSERT INTO t_part (created_at, v) VALUES ('2025-06-01', 1)"),
                units(rw("INSERT INTO t_part (created_at, v) VALUES ('2025-06-01', 1)",
                        DialectProfile.ORACLE)));
    }

    // ---------- 拒绝路径（执行前显式） ----------

    @Test
    void 拒绝_缺列清单INSERT_多目标不可拆行() {
        final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                () -> rw("INSERT INTO t_grid VALUES (0, 1, 'a')"));
        assertTrue(e.getMessage().contains("拆行需列清单"), e.getMessage());
    }

    @Test
    void 拒绝_VALUES行内引用受管表_自身表() {
        final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                () -> rw("INSERT INTO t_bench (user_id, v) VALUES (0, (SELECT MAX(v) FROM t_bench WHERE user_id = 0))"));
        assertTrue(e.getMessage().contains("VALUES 行内引用受管表"), e.getMessage());
    }

    @Test
    void 拒绝_绑定发现面之外的受管表() {
        final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                () -> rw("INSERT INTO t_bench (user_id, v) VALUES (0, (SELECT MAX(v) FROM t_watch WHERE user_id = 0))"));
        assertTrue(e.getMessage().contains("绑定发现面之外"), e.getMessage());
    }

    // ---------- 链路入口 ----------

    private static RewriteResult rw(final String sql) {
        return rw(sql, DialectProfile.MYSQL);
    }

    private static RewriteResult rw(final String sql, final DialectProfile profile) {
        final BoundStatement bound = Binder.bind(BobaChop.parse(sql, profile), snapshot);
        final RouteResult route = Router.route(bound, snapshot);
        final MergePlan plan = MergePlanner.plan(bound, route, snapshot);
        return Rewriter.rewrite(sql, bound, route, plan, profile, snapshot);
    }

    private static List<String> units(final RewriteResult result) {
        final List<String> out = new ArrayList<String>(result.units().size());
        for (final RewriteResult.RoutedSql unit : result.units()) {
            out.add(unit.datasourceName() + " <= " + unit.sql());
        }
        return out;
    }
}
