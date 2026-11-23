package io.github.susongyan.bobapour.engine.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.susongyan.bobachop.api.BobaChop;
import io.github.susongyan.bobachop.dialect.DialectProfile;
import io.github.susongyan.bobapour.config.ConfigLoader;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.engine.bind.Binder;
import io.github.susongyan.bobapour.engine.bind.BoundStatement;
import io.github.susongyan.bobapour.engine.execute.Executor;
import io.github.susongyan.bobapour.engine.execute.QueryFanout;
import io.github.susongyan.bobapour.engine.rewrite.RewriteResult;
import io.github.susongyan.bobapour.engine.rewrite.Rewriter;
import io.github.susongyan.bobapour.engine.route.RouteResult;
import io.github.susongyan.bobapour.engine.route.Router;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * T7 执行与归并黄金用例——H2 双内嵌库扮演 ds_0/ds_1 分片，全链走真实决策链
 * （bind → route → 裁决 → 改写 → 执行 → 归并），期望值由按路由手工播种的数据推得。
 * DoD：四策略正确性 + 写路径汇总/拆行/广播 + TopN/流式<b>内存上界证明</b>（分片预读 ≤1 行，
 * 中途关闭不整缓冲）+ T7 收紧面的执行前拒绝。
 */
final class MergeExecutionTest {

    private static final String URL_0 = "jdbc:h2:mem:pour0;DB_CLOSE_DELAY=-1";
    private static final String URL_1 = "jdbc:h2:mem:pour1;DB_CLOSE_DELAY=-1";

    private static MetadataSnapshot snapshot;
    private static Executor.ConnectionProvider provider;

    @BeforeAll
    static void 装配快照与分片库() throws Exception {
        snapshot = MetadataSnapshot.of(ConfigLoader.load(String.join("\n",
            "datasources:",
            "  ds_0:",
            "    className: com.example.X",
            "  ds_1:",
            "    className: com.example.X",
            "sharding:",
            "  tables:",
            "    t_order:",
            "      actualDataNodes: ds_${0..1}.t_order",
            "      databaseStrategy:",
            "        shardingColumn: user_id",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 2",
            "      crossShardQuery: WHITELIST",
            "broadcastTables:",
            "  - t_config",
            "guards:",
            "  maxShardsPerQuery: 16",
            "  maxMergeWindowRows: 16",
            "")));
        final Map<String, String> urls = new LinkedHashMap<String, String>();
        urls.put("ds_0", URL_0);
        urls.put("ds_1", URL_1);
        provider = new Executor.ConnectionProvider() {
            @Override
            public Connection connection(final String datasourceName) throws SQLException {
                return DriverManager.getConnection(urls.get(datasourceName));
            }
        };
        for (final String url : Arrays.asList(URL_0, URL_1)) {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS t_order "
                        + "(user_id INT, seq INT, amount INT, tag VARCHAR(20))");
                statement.execute("CREATE TABLE IF NOT EXISTS t_config (k VARCHAR(20), v INT)");
            }
        }
    }

    @BeforeEach
    void 按路由重播种() throws Exception {
        // 播种必须服从路由（user_id MOD 2）：ds_0 全部 user_id=0，ds_1 全部 user_id=1。
        seed(URL_0, new Object[][]{{0, 1, 10, "b"}, {0, 2, 30, "a"}, {0, 3, null, "c"}, {0, 4, 20, null}});
        seed(URL_1, new Object[][]{{1, 5, 25, "d"}, {1, 6, 5, "b"}, {1, 7, null, "a"}, {1, 8, 15, "c"}});
        for (final String url : Arrays.asList(URL_0, URL_1)) {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("TRUNCATE TABLE t_config");
            }
        }
    }

    // ---------- 拼接（CONCAT） ----------

    @Test
    void 拼接_无排序_行集相等() throws Exception {
        final List<Object[]> rows = rows(query("SELECT seq FROM t_order"));
        assertEquals(Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8"), strings(rows, 0));
    }

    @Test
    void 拼接_单分片_经引擎路径() throws Exception {
        final List<Object[]> rows = rows(query("SELECT seq FROM t_order WHERE user_id = 0"));
        assertEquals(Arrays.asList("1", "2", "3", "4"), strings(rows, 0));
    }

    // ---------- 流式（STREAM_MERGE） ----------

    @Test
    void 流式_升序_NULL首_平局按分片序() throws Exception {
        final List<Object[]> rows = rows(query("SELECT seq, amount FROM t_order ORDER BY amount"));
        assertEquals(Arrays.asList("3", "7", "6", "1", "8", "4", "5", "2"), strings(rows, 0));
    }

    @Test
    void 流式_降序_次键破平局() throws Exception {
        final List<Object[]> rows = rows(query(
                "SELECT seq, amount FROM t_order ORDER BY amount DESC, seq ASC"));
        assertEquals(Arrays.asList("2", "5", "4", "8", "1", "6", "3", "7"), strings(rows, 0));
    }

    @Test
    void 流式_Star投影_标签寻列() throws Exception {
        final List<Object[]> rows = rows(query("SELECT * FROM t_order ORDER BY tag"));
        assertEquals(Arrays.asList("4", "2", "7", "1", "6", "3", "8", "5"), strings(rows, 1));
    }

    @Test
    void 流式_限定列排序键() throws Exception {
        final List<Object[]> rows = rows(query(
                "SELECT seq, amount FROM t_order o ORDER BY o.amount, o.seq"));
        assertEquals(Arrays.asList("3", "7", "6", "1", "8", "4", "5", "2"), strings(rows, 0));
    }

    // ---------- TopN ----------

    @Test
    void TopN_窗口跳取_下推限量() throws Exception {
        final RewriteResult units = rewrite("SELECT seq, amount FROM t_order ORDER BY amount LIMIT 3 OFFSET 2");
        for (final RewriteResult.RoutedSql unit : units.units()) {
            assertTrue(unit.sql().endsWith("LIMIT 5"), unit.sql());
        }
        final List<Object[]> rows = rows(query("SELECT seq, amount FROM t_order ORDER BY amount LIMIT 3 OFFSET 2"));
        assertEquals(Arrays.asList("6", "1", "8"), strings(rows, 0));
    }

    @Test
    void TopN_逗号形态_同样下推() throws Exception {
        final List<Object[]> rows = rows(query("SELECT seq, amount FROM t_order ORDER BY amount LIMIT 2, 3"));
        assertEquals(Arrays.asList("6", "1", "8"), strings(rows, 0));
    }

    @Test
    void TopN_分组窗口_改写层补序() throws Exception {
        final RewriteResult units = rewrite("SELECT user_id, COUNT(*) c FROM t_order GROUP BY user_id LIMIT 1");
        for (final RewriteResult.RoutedSql unit : units.units()) {
            assertTrue(unit.sql().endsWith("GROUP BY user_id ORDER BY user_id LIMIT 1"), unit.sql());
        }
        final List<Object[]> rows = rows(query("SELECT user_id, COUNT(*) c FROM t_order GROUP BY user_id LIMIT 1"));
        assertEquals(1, rows.size());
        assertEquals(0, ((Number) rows.get(0)[0]).intValue());
        assertEquals(4, ((Number) rows.get(0)[1]).intValue());
    }

    @Test
    void TopN_聚合别名排序键() throws Exception {
        final List<Object[]> rows = rows(query(
                "SELECT user_id, COUNT(*) c FROM t_order GROUP BY user_id ORDER BY c DESC, user_id ASC LIMIT 1"));
        assertEquals(1, rows.size());
        assertEquals(0, ((Number) rows.get(0)[0]).intValue());
    }

    // ---------- 分组（组不跨分片） ----------

    @Test
    void 分组_含分片键_拼接() throws Exception {
        final List<Object[]> rows = rows(query(
                "SELECT user_id, COUNT(*) c FROM t_order GROUP BY user_id"));
        assertEquals(2, rows.size());
        assertEquals(0, ((Number) rows.get(0)[0]).intValue());
        assertEquals(4, ((Number) rows.get(0)[1]).intValue());
        assertEquals(1, ((Number) rows.get(1)[0]).intValue());
        assertEquals(4, ((Number) rows.get(1)[1]).intValue());
    }

    @Test
    void 分组_带排序_流式保全局序() throws Exception {
        final List<Object[]> rows = rows(query(
                "SELECT user_id, COUNT(*) c FROM t_order GROUP BY user_id ORDER BY user_id DESC"));
        assertEquals(Arrays.asList("1", "0"), strings(rows, 0));
    }

    // ---------- 重组装（REASSEMBLE） ----------

    @Test
    void 重组装_四函数_部分聚合合并() throws Exception {
        try (ResultSet rs = query(
                "SELECT COUNT(*), SUM(amount), MIN(amount), MAX(amount) FROM t_order")) {
            assertTrue(rs.next());
            assertEquals(8, rs.getInt(1));
            assertEquals(105, rs.getInt(2));
            assertEquals(5, rs.getInt(3));
            assertEquals(30, rs.getInt(4));
            assertFalse(rs.next());
        }
    }

    @Test
    void 重组装_空分片_SUM为NULL_wasNull语义() throws Exception {
        try (ResultSet rs = query("SELECT COUNT(*), SUM(amount) FROM t_order WHERE seq > 100")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getLong(1));
            assertEquals(0L, rs.getLong(2));
            assertTrue(rs.wasNull());
            assertFalse(rs.next());
        }
    }

    // ---------- 写路径（D9/D10） ----------

    @Test
    void 写_多分片UPDATE_影响行数求和() throws Exception {
        assertEquals(8, update("UPDATE t_order SET tag = 'x' WHERE user_id IN (0, 1)"));
    }

    @Test
    void 写_拆行INSERT_落位与计数() throws Exception {
        assertEquals(2, update("INSERT INTO t_order (user_id, seq, amount, tag)"
                + " VALUES (0, 11, 1, 'n'), (1, 12, 2, 'n')"));
        assertEquals(1, count(URL_0, "SELECT COUNT(*) FROM t_order WHERE seq = 11"));
        assertEquals(1, count(URL_1, "SELECT COUNT(*) FROM t_order WHERE seq = 12"));
    }

    @Test
    void 写_广播表_全副本执行_首副本计数() throws Exception {
        assertEquals(1, update("INSERT INTO t_config (k, v) VALUES ('a', 1)"));
        assertEquals(1, count(URL_0, "SELECT COUNT(*) FROM t_config"));
        assertEquals(1, count(URL_1, "SELECT COUNT(*) FROM t_config"));
    }

    // ---------- 内存上界证明（D3 黄金用例） ----------

    @Test
    void 上界_流式归并_分片预读不超过一行() throws Exception {
        final Map<String, int[]> nextCounts = new LinkedHashMap<String, int[]>();
        nextCounts.put("ds_0", new int[1]);
        nextCounts.put("ds_1", new int[1]);
        try (ResultSet ignored = countingQuery(
                "SELECT seq, amount FROM t_order ORDER BY amount", nextCounts)) {
            // 只消费 3 行即关闭：流式归并不得整缓冲（整缓冲 = 每分片 8 次 next）。
            for (int i = 0; i < 3; i++) {
                assertTrue(ignored.next());
            }
        }
        for (final Map.Entry<String, int[]> entry : nextCounts.entrySet()) {
            assertTrue(entry.getValue()[0] <= 3 + 1,
                    entry.getKey() + " 预读超界: " + entry.getValue()[0]);
        }
    }

    @Test
    void 上界_TopN_消费行数有界() throws Exception {
        final Map<String, int[]> nextCounts = new LinkedHashMap<String, int[]>();
        nextCounts.put("ds_0", new int[1]);
        nextCounts.put("ds_1", new int[1]);
        try (ResultSet rs = countingQuery(
                "SELECT seq, amount FROM t_order ORDER BY amount LIMIT 3 OFFSET 2", nextCounts)) {
            int delivered = 0;
            while (rs.next()) {
                delivered++;
            }
            assertEquals(3, delivered);
        }
        for (final Map.Entry<String, int[]> entry : nextCounts.entrySet()) {
            // 上界 = offset(2) + count(3) + 预读(1) = 6 < 分片全量 8。
            assertTrue(entry.getValue()[0] <= 2 + 3 + 1,
                    entry.getKey() + " 消费超界: " + entry.getValue()[0]);
        }
    }

    // ---------- T7 收紧面（执行前拒绝） ----------

    @Test
    void 拒绝_排序键不在投影() {
        final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                () -> query("SELECT seq FROM t_order ORDER BY amount"));
        assertTrue(e.getMessage().contains("不在投影内"), e.getMessage());
    }

    @Test
    void 拒绝_表达式排序键() {
        final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                () -> query("SELECT seq FROM t_order ORDER BY UPPER(tag)"));
        assertTrue(e.getMessage().contains("排序键须为裸列引用"), e.getMessage());
    }

    @Test
    void 拒绝_重组装混合投影() {
        final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                () -> query("SELECT seq, COUNT(*) FROM t_order"));
        assertTrue(e.getMessage().contains("投影全聚合"), e.getMessage());
    }

    @Test
    void 拒绝_动态LIMIT窗口() {
        final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                () -> query("SELECT seq FROM t_order ORDER BY amount LIMIT ?"));
        assertTrue(e.getMessage().contains("动态 LIMIT 窗口"), e.getMessage());
    }

    @Test
    void 拒绝_TopN窗口超限() {
        final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                () -> query("SELECT seq FROM t_order ORDER BY amount LIMIT 2 OFFSET 15"));
        assertTrue(e.getMessage().contains("TopN 窗口超限"), e.getMessage());
    }

    // ---------- 链路与工具 ----------

    private static ResultSet query(final String sql) throws SQLException {
        final RewriteResult units = rewrite(sql);
        final QueryFanout fanout = Executor.openQuery(units, provider, snapshot.guards());
        final BoundStatement bound = bound(sql);
        return MergeExecutor.execute(MergePlanner.plan(bound, Router.route(bound, snapshot), snapshot),
                bound, fanout);
    }

    private static int update(final String sql) throws SQLException {
        final BoundStatement bound = bound(sql);
        final RouteResult route = Router.route(bound, snapshot);
        final MergePlan plan = MergePlanner.plan(bound, route, snapshot);
        return Executor.executeUpdate(Rewriter.rewrite(sql, bound, route, plan,
                DialectProfile.MYSQL, snapshot), provider, plan);
    }

    private static RewriteResult rewrite(final String sql) {
        final BoundStatement bound = bound(sql);
        final RouteResult route = Router.route(bound, snapshot);
        return Rewriter.rewrite(sql, bound, route, MergePlanner.plan(bound, route, snapshot),
                DialectProfile.MYSQL, snapshot);
    }

    private static BoundStatement bound(final String sql) {
        return Binder.bind(BobaChop.parse(sql, DialectProfile.MYSQL), snapshot);
    }

    /** 计数版 query：动态代理逐层包 Connection/Statement/ResultSet，按数据源计 next 次数。 */
    private static ResultSet countingQuery(final String sql, final Map<String, int[]> nextCounts)
            throws SQLException {
        final Executor.ConnectionProvider counting = new Executor.ConnectionProvider() {
            @Override
            public Connection connection(final String datasourceName) throws SQLException {
                final Connection raw = provider.connection(datasourceName);
                return (Connection) Proxy.newProxyInstance(
                        MergeExecutionTest.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, new InvocationHandler() {
                            @Override
                            public Object invoke(final Object proxy, final Method method,
                                    final Object[] args) throws Throwable {
                                final Object result = method.invoke(raw, args);
                                if ("createStatement".equals(method.getName())) {
                                    return wrapStatement(datasourceName, (Statement) result, nextCounts);
                                }
                                return result;
                            }
                        });
            }
        };
        final RewriteResult units = rewrite(sql);
        final QueryFanout fanout = Executor.openQuery(units, counting, snapshot.guards());
        final BoundStatement bound = bound(sql);
        return MergeExecutor.execute(MergePlanner.plan(bound, Router.route(bound, snapshot), snapshot),
                bound, fanout);
    }

    private static Statement wrapStatement(final String datasourceName, final Statement raw,
            final Map<String, int[]> nextCounts) {
        return (Statement) Proxy.newProxyInstance(MergeExecutionTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        final Object result = method.invoke(raw, args);
                        if ("executeQuery".equals(method.getName())) {
                            return wrapResultSet(datasourceName, (ResultSet) result, nextCounts);
                        }
                        return result;
                    }
                });
    }

    private static ResultSet wrapResultSet(final String datasourceName, final ResultSet raw,
            final Map<String, int[]> nextCounts) {
        return (ResultSet) Proxy.newProxyInstance(MergeExecutionTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if ("next".equals(method.getName())) {
                            nextCounts.get(datasourceName)[0]++;
                        }
                        return method.invoke(raw, args);
                    }
                });
    }

    private static List<Object[]> rows(final ResultSet rs) throws SQLException {
        final List<Object[]> out = new ArrayList<Object[]>();
        try {
            final int width = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                final Object[] row = new Object[width];
                for (int i = 0; i < width; i++) {
                    row[i] = rs.getObject(i + 1);
                }
                out.add(row);
            }
        } finally {
            rs.close();
        }
        return out;
    }

    private static List<String> strings(final List<Object[]> rows, final int column) {
        final List<String> out = new ArrayList<String>(rows.size());
        for (final Object[] row : rows) {
            out.add(String.valueOf(row[column]));
        }
        return out;
    }

    private static void seed(final String url, final Object[][] rows) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE t_order");
            for (final Object[] row : rows) {
                statement.execute("INSERT INTO t_order VALUES (" + row[0] + ", " + row[1] + ", "
                        + (null == row[2] ? "NULL" : row[2]) + ", "
                        + (null == row[3] ? "NULL" : "'" + row[3] + "'") + ")");
            }
        }
    }

    private static int count(final String url, final String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
