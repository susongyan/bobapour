package io.github.susongyan.bobapour.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.susongyan.bobapour.config.ConfigLoader;
import io.github.susongyan.bobapour.config.model.PourConfig;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * T7 端到端对拍——H2 双内嵌库扮演 ds_0/ds_1 分片 + 第三个<b>不分片对照库</b>：同一条
 * 逻辑 SQL 经 PourDataSource（分片引擎模式全链）与对照库直连执行，行序与聚合值逐串相等
 * （分片透明性的本地位阶锚定；真实生产库影子对拍为 T9）。另覆盖：预编译参数回放
 * （读/写/setNull）、分片键参数化拆行拒绝（D9）、广播写全副本、execute 双形态、
 * 引擎模式拒绝面（事务/生成键/批量/滚动游标/流参数）。
 */
class PourShardingEndToEndTest {

    private static final String URL_0 = "jdbc:h2:mem:pour_e2e_0;DB_CLOSE_DELAY=-1";
    private static final String URL_1 = "jdbc:h2:mem:pour_e2e_1;DB_CLOSE_DELAY=-1";
    private static final String PLAIN = "jdbc:h2:mem:pour_e2e_plain;DB_CLOSE_DELAY=-1";

    private static PourDataSource pour;

    @BeforeAll
    static void 装配分片入口与三库() throws Exception {
        final PourConfig config = ConfigLoader.load(String.join("\n",
            "datasources:",
            "  ds_0:",
            "    className: org.h2.jdbcx.JdbcDataSource",
            "  ds_1:",
            "    className: org.h2.jdbcx.JdbcDataSource",
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
            ""));
        final Map<String, javax.sql.DataSource> pools = new LinkedHashMap<String, javax.sql.DataSource>();
        pools.put("ds_0", pool(URL_0));
        pools.put("ds_1", pool(URL_1));
        pour = new PourDataSource(config, pools);
        for (final String url : Arrays.asList(URL_0, URL_1, PLAIN)) {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS t_order "
                        + "(user_id INT, seq INT, amount INT, tag VARCHAR(20))");
                statement.execute("CREATE TABLE IF NOT EXISTS t_config (k VARCHAR(20), v INT)");
            }
        }
    }

    /** 播种服从路由（user_id MOD 2）；对照库 = 两分片行集之并。 */
    @BeforeEach
    void 重播种() throws Exception {
        final Object[][] shard0 = {{0, 1, 10, "b"}, {0, 2, 30, "a"}, {0, 3, null, "c"}, {0, 4, 20, null}};
        final Object[][] shard1 = {{1, 5, 25, "d"}, {1, 6, 5, "b"}, {1, 7, null, "a"}, {1, 8, 15, "c"}};
        reseed(URL_0, shard0);
        reseed(URL_1, shard1);
        reseed(PLAIN, concat(shard0, shard1));
    }

    // ---------- 对拍（分片引擎 vs 对照库直连） ----------

    @Test
    void 对拍_流式归并_行序相等() throws Exception {
        final String sql = "SELECT seq, amount FROM t_order ORDER BY amount, seq";
        assertEquals(plain(sql), sharded(sql));
    }

    @Test
    void 对拍_TopN窗口_行序相等() throws Exception {
        final String sql = "SELECT seq, amount FROM t_order ORDER BY amount DESC LIMIT 3";
        assertEquals(plain(sql), sharded(sql));
    }

    @Test
    void 对拍_聚合重组装_值相等() throws Exception {
        final String sql = "SELECT COUNT(*), SUM(amount), MIN(amount), MAX(amount) FROM t_order";
        assertEquals(plain(sql), sharded(sql));
    }

    @Test
    void 对拍_分组流式_行序相等() throws Exception {
        final String sql = "SELECT user_id, COUNT(*) FROM t_order GROUP BY user_id ORDER BY user_id";
        assertEquals(plain(sql), sharded(sql));
    }

    @Test
    void 对拍_单分片路由_行集相等() throws Exception {
        final String sql = "SELECT seq FROM t_order WHERE user_id = 1 ORDER BY seq";
        assertEquals(plain(sql), sharded(sql));
    }

    @Test
    void 对拍_拼接_行集相等() throws Exception {
        final String sql = "SELECT seq FROM t_order WHERE tag IS NOT NULL ORDER BY seq";
        assertEquals(plain(sql), sharded(sql));
    }

    // ---------- 预编译参数回放 ----------

    @Test
    void 预编译_读参数回放_全分片扇出() throws Exception {
        try (Connection conn = pour.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT seq FROM t_order WHERE user_id = ? ORDER BY seq")) {
            ps.setInt(1, 0);
            assertEquals(Arrays.asList("1", "2", "3", "4"), strings(ps.executeQuery()));
        }
    }

    @Test
    void 预编译_写参数回放_跨分片影响行数() throws Exception {
        try (Connection conn = pour.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE t_order SET tag = ? WHERE user_id = ?")) {
            ps.setString(1, "zz");
            ps.setInt(2, 1);
            assertEquals(4, ps.executeUpdate());
        }
        assertEquals(Arrays.asList("5", "6", "7", "8"), sharded(
                "SELECT seq FROM t_order WHERE tag = 'zz' ORDER BY seq"));
    }

    @Test
    void 预编译_setNull回放_广播写() throws Exception {
        try (Connection conn = pour.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO t_config (k, v) VALUES (?, ?)")) {
            ps.setString(1, "n");
            ps.setNull(2, Types.INTEGER);
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(Arrays.asList("n|null"), sharded("SELECT k, v FROM t_config"));
        assertEquals(1, count(URL_1, "SELECT COUNT(*) FROM t_config"), "广播写应落到副本 ds_1");
    }

    @Test
    void 预编译_分片键参数化_拆行显式拒绝() throws Exception {
        try (Connection conn = pour.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO t_order (user_id, seq, amount, tag) VALUES (?, 99, 1, 'x')")) {
            ps.setInt(1, 0);
            final UnsupportedQueryException e = assertThrows(UnsupportedQueryException.class,
                    ps::executeUpdate);
            assertTrue(e.getMessage().contains("行缺分片键"), e.getMessage());
        }
    }

    @Test
    void 预编译_流参数_不可回放显式拒绝() throws Exception {
        try (Connection conn = pour.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT seq FROM t_order WHERE tag = ?")) {
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> ps.setAsciiStream(1, System.in));
        }
    }

    // ---------- 静态写路径 ----------

    @Test
    void 静态_多行INSERT拆行_影响行数求和() throws Exception {
        try (Connection conn = pour.getConnection(); Statement stmt = conn.createStatement()) {
            assertEquals(2, stmt.executeUpdate("INSERT INTO t_order (user_id, seq, amount, tag) "
                    + "VALUES (0, 11, 1, 'x'), (1, 12, 2, 'y')"));
        }
        assertEquals(1, count(URL_0, "SELECT COUNT(*) FROM t_order WHERE seq = 11"));
        assertEquals(1, count(URL_1, "SELECT COUNT(*) FROM t_order WHERE seq = 12"));
    }

    @Test
    void 静态_广播INSERT_全副本_取首副本计数() throws Exception {
        try (Connection conn = pour.getConnection(); Statement stmt = conn.createStatement()) {
            assertEquals(1, stmt.executeUpdate("INSERT INTO t_config VALUES ('g', 7)"));
        }
        assertEquals(1, count(URL_0, "SELECT COUNT(*) FROM t_config WHERE k = 'g'"));
        assertEquals(1, count(URL_1, "SELECT COUNT(*) FROM t_config WHERE k = 'g'"));
    }

    @Test
    void 静态_跨分片UPDATE_影响行数与聚合值() throws Exception {
        try (Connection conn = pour.getConnection(); Statement stmt = conn.createStatement()) {
            assertEquals(8, stmt.executeUpdate("UPDATE t_order SET amount = amount + 1"));
        }
        // 105 = 播种非空 amount 和；+1 后 8 行 COUNT、6 个非空值 SUM=111、MIN=6、MAX=31。
        assertEquals(Arrays.asList("8|111|6|31"),
                sharded("SELECT COUNT(*), SUM(amount), MIN(amount), MAX(amount) FROM t_order"));
    }

    // ---------- execute 双形态与结果面 ----------

    @Test
    void execute_读形态_false_游标经getResultSet() throws Exception {
        try (Connection conn = pour.getConnection(); Statement stmt = conn.createStatement()) {
            assertFalse(stmt.execute("SELECT seq FROM t_order WHERE user_id = 0 ORDER BY seq"));
            assertEquals(-1, stmt.getUpdateCount());
            try (ResultSet rs = stmt.getResultSet()) {
                assertEquals(Arrays.asList("1", "2", "3", "4"), strings(rs));
            }
            assertFalse(stmt.getMoreResults());
        }
    }

    @Test
    void execute_写形态_true_计数经getUpdateCount() throws Exception {
        try (Connection conn = pour.getConnection(); Statement stmt = conn.createStatement()) {
            assertTrue(stmt.execute("DELETE FROM t_order WHERE user_id = 1"));
            assertEquals(4, stmt.getUpdateCount());
            assertNullResult(stmt);
        }
    }

    // ---------- 引擎模式拒绝面 ----------

    @Test
    void 拒绝_手动事务() throws Exception {
        try (Connection conn = pour.getConnection()) {
            assertTrue(conn.getAutoCommit(), "v0 每单元自动提交");
            assertThrows(SQLFeatureNotSupportedException.class, () -> conn.setAutoCommit(false));
            assertThrows(SQLFeatureNotSupportedException.class, conn::commit);
        }
    }

    @Test
    void 拒绝_生成键与存储过程与批量() throws Exception {
        try (Connection conn = pour.getConnection(); Statement stmt = conn.createStatement()) {
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> conn.prepareStatement("SELECT 1", Statement.RETURN_GENERATED_KEYS));
            assertThrows(SQLFeatureNotSupportedException.class, () -> conn.prepareCall("{call x}"));
            assertThrows(SQLFeatureNotSupportedException.class, () -> stmt.addBatch("SELECT 1"));
            assertThrows(SQLFeatureNotSupportedException.class, stmt::executeBatch);
            assertThrows(SQLFeatureNotSupportedException.class, stmt::getGeneratedKeys);
        }
    }

    @Test
    void 拒绝_滚动游标与上限投影() throws Exception {
        try (Connection conn = pour.getConnection(); Statement stmt = conn.createStatement()) {
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
            assertThrows(SQLFeatureNotSupportedException.class, () -> stmt.setMaxRows(10));
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, stmt.getResultSetType());
            assertEquals(ResultSet.CONCUR_READ_ONLY, stmt.getResultSetConcurrency());
        }
    }

    // ---------- 元数据与生命周期 ----------

    @Test
    void 元数据_首数据源代表() throws Exception {
        try (Connection conn = pour.getConnection()) {
            assertEquals("H2", conn.getMetaData().getDatabaseProductName());
            assertNullSafe(conn.getCatalog());
        }
    }

    @Test
    void 关闭_级联语句与游标_幂等() throws Exception {
        final Connection conn = pour.getConnection();
        final Statement stmt = conn.createStatement();
        final ResultSet rs = stmt.executeQuery("SELECT seq FROM t_order WHERE user_id = 0");
        conn.close();
        assertTrue(conn.isClosed() && stmt.isClosed(), "连接关闭应级联语句");
        assertThrows(SQLException.class, rs::next, "游标随连接关闭失效");
        assertDoesNotCloseAgain(conn);
    }

    // ---------- 助手 ----------

    private static JdbcDataSource pool(final String url) {
        final JdbcDataSource pool = new JdbcDataSource();
        pool.setURL(url);
        return pool;
    }

    private static void reseed(final String url, final Object[][] rows) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE t_order");
            statement.execute("TRUNCATE TABLE t_config");
            for (final Object[] row : rows) {
                statement.execute("INSERT INTO t_order VALUES (" + row[0] + ", " + row[1] + ", "
                        + (null == row[2] ? "NULL" : row[2]) + ", "
                        + (null == row[3] ? "NULL" : "'" + row[3] + "'") + ")");
            }
        }
    }

    private static Object[][] concat(final Object[][] a, final Object[][] b) {
        final Object[][] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 对照库直连执行（行串化，null → "null"）。 */
    private static List<String> plain(final String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(PLAIN);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return strings(rs);
        }
    }

    /** 分片引擎模式执行同一逻辑 SQL（行串化）。 */
    private static List<String> sharded(final String sql) throws Exception {
        try (Connection conn = pour.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return strings(rs);
        }
    }

    private static List<String> strings(final ResultSet rs) throws SQLException {
        final List<String> out = new ArrayList<String>();
        final int columns = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            final StringBuilder row = new StringBuilder();
            for (int i = 1; i <= columns; i++) {
                if (1 < i) {
                    row.append('|');
                }
                row.append(rs.getString(i));
            }
            out.add(row.toString());
        }
        return out;
    }

    private static int count(final String url, final String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void assertNullResult(final Statement stmt) throws SQLException {
        assertFalse(stmt.getMoreResults());
        assertTrue(null == stmt.getResultSet(), "写形态无结果集");
    }

    private static void assertNullSafe(final String value) {
        assertTrue(null == value, "catalog 恒 null");
    }

    private static void assertDoesNotCloseAgain(final Connection conn) {
        assertDoesNotThrow(conn::close);
    }
}
