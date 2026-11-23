package io.github.susongyan.bobapour.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;

/**
 * T5 契约矩阵——连接级对拍（D8）：同一探针分别打到原生连接与 pour 连接，断言结果相等或
 * 异常类一致。覆盖：查询结果与元数据、事务边界（commit/rollback/savepoint）、隔离级、
 * 关闭语义（关闭后操作、双重 close、abort）、nativeSQL/警告/ClientInfo、Wrapper 契约。
 */
class PourConnectionContractTest {

    @Test
    void 对拍_查询结果与结果集元数据() throws SQLException {
        ContractOracle.assertParity(conn -> {
            final List<String> rows = new ArrayList<String>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT id, name, amount, created FROM t_contract ORDER BY id")) {
                while (rs.next()) {
                    rows.add(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3)
                            + "|" + rs.getTimestamp(4));
                }
                rows.add("columns=" + rs.getMetaData().getColumnCount());
                rows.add("label=" + rs.getMetaData().getColumnLabel(2));
            }
            return rows;
        });
    }

    @Test
    void 对拍_事务_提交后可见() throws SQLException {
        ContractOracle.assertParity((conn, tag) -> {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO t_contract(name, amount) VALUES ('" + tag + "_commit', 1.00)");
            }
            conn.commit();
            return count(conn, tag + "_commit");
        });
    }

    @Test
    void 对拍_事务_回滚后不可见() throws SQLException {
        ContractOracle.assertParity((conn, tag) -> {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO t_contract(name, amount) VALUES ('" + tag + "_rb', 1.00)");
            }
            conn.rollback();
            return count(conn, tag + "_rb");
        });
    }

    @Test
    void 对拍_保存点部分回滚() throws SQLException {
        ContractOracle.assertParity((conn, tag) -> {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO t_contract(name, amount) VALUES ('" + tag + "_keep', 1.00)");
                final java.sql.Savepoint sp = conn.setSavepoint();
                stmt.executeUpdate("INSERT INTO t_contract(name, amount) VALUES ('" + tag + "_drop', 2.00)");
                conn.rollback(sp);
                conn.releaseSavepoint(sp);
            }
            conn.commit();
            return Arrays.asList(count(conn, tag + "_keep"), count(conn, tag + "_drop"));
        });
    }

    @Test
    void 对拍_隔离级设置与读取() throws SQLException {
        ContractOracle.assertParity(conn -> {
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return conn.getTransactionIsolation();
        });
    }

    @Test
    void 对拍_关闭后操作异常类一致() throws SQLException {
        ContractOracle.assertParity(conn -> {
            conn.close();
            return conn.getAutoCommit();
        });
        ContractOracle.assertParity(conn -> {
            conn.close();
            return conn.createStatement();
        });
    }

    @Test
    void 对拍_双重close幂等() throws SQLException {
        ContractOracle.assertParity(conn -> {
            conn.close();
            conn.close();
            return conn.isClosed();
        });
    }

    @Test
    void 对拍_abort后连接失效() throws SQLException {
        ContractOracle.assertParity(conn -> {
            conn.abort(Runnable::run);
            return conn.isClosed();
        });
    }

    @Test
    void 对拍_nativeSQL与连接元数据() throws SQLException {
        ContractOracle.assertParity(conn -> Arrays.asList(
                conn.nativeSQL("SELECT 1"),
                conn.getMetaData().getDatabaseProductName(),
                conn.getMetaData().getDatabaseMajorVersion(),
                conn.getMetaData().supportsSavepoints(),
                conn.getTypeMap()));
    }

    @Test
    void 对拍_状态读取与警告面() throws SQLException {
        ContractOracle.assertParity(conn -> {
            final Object warnings = conn.getWarnings();
            conn.clearWarnings();
            final Properties clientInfo = conn.getClientInfo();
            return Arrays.asList(conn.isReadOnly(), conn.getCatalog(), conn.getSchema(),
                    conn.getHoldability(), conn.getNetworkTimeout(), warnings, clientInfo);
        });
    }

    @Test
    void 对拍_自动提交边界_关闭前有事务自动回滚() throws SQLException {
        ContractOracle.assertParity((conn, tag) -> {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO t_contract(name, amount) VALUES ('" + tag + "_dc', 1.00)");
            }
            conn.close();
            try (Connection fresh = ContractOracle.rawPool().getConnection()) {
                return count(fresh, tag + "_dc");
            }
        });
    }

    // ---------- 包装层结构契约（非对拍，pour 自身语义） ----------

    @Test
    void Wrapper契约_连接自身与底层驱动() throws SQLException {
        ContractOracle.bootstrap();
        try (Connection conn = ContractOracle.pourPool().getConnection()) {
            assertTrue(conn.isWrapperFor(Connection.class));
            assertTrue(conn.isWrapperFor(PourConnection.class));
            assertEquals(conn, conn.unwrap(PourConnection.class));
            assertTrue(conn.unwrap(JdbcConnection.class) instanceof JdbcConnection,
                    "应可解包到底层 H2 连接（透明性锚点）");
        }
    }

    @Test
    void 语句族包装回指_连接身份一致() throws SQLException {
        ContractOracle.bootstrap();
        try (Connection conn = ContractOracle.pourPool().getConnection();
             Statement plain = conn.createStatement();
             java.sql.PreparedStatement prepared = conn.prepareStatement("SELECT COUNT(*) FROM t_contract");
             java.sql.CallableStatement callable = conn.prepareCall("SELECT COUNT(*) FROM t_contract")) {
            assertEquals(conn, plain.getConnection());
            assertEquals(conn, prepared.getConnection());
            assertEquals(conn, callable.getConnection());
        }
    }

    private static long count(final Connection conn, final String name) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t_contract WHERE name = '" + name + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
