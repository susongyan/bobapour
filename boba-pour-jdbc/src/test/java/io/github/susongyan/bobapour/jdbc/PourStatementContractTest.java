package io.github.susongyan.bobapour.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * T5 契约矩阵——语句族对拍（D8）：Statement/PreparedStatement/CallableStatement 三族在原生路径
 * 与 pour 路径上行为一致。覆盖：execute 三态、批量、生成键、参数绑定多类型、null 与 setObject、
 * 流式控制面（D12 契约② M1 部分=透传）、滚动结果集、语句关闭语义、存储过程调用与 OUT 参数。
 */
class PourStatementContractTest {

    @Test
    void 对拍_Statement查询_结果与更新计数() throws SQLException {
        ContractOracle.assertParity((conn, tag) -> {
            final List<Object> out = new ArrayList<Object>();
            try (Statement stmt = conn.createStatement()) {
                out.add(stmt.execute("INSERT INTO t_contract(name, amount) VALUES ('" + tag + "_x', 3.00)"));
                out.add(stmt.getUpdateCount());
                out.add(stmt.getMoreResults());
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT name, amount FROM t_contract WHERE name LIKE 'seed_%' ORDER BY name")) {
                    while (rs.next()) {
                        out.add(rs.getString(1) + "#" + rs.getString(2));
                    }
                }
                out.add(stmt.executeUpdate("DELETE FROM t_contract WHERE name = '" + tag + "_x'"));
            }
            return out;
        });
    }

    @Test
    void 对拍_批量执行() throws SQLException {
        ContractOracle.assertParity((conn, tag) -> {
            final List<Object> out = new ArrayList<Object>();
            try (Statement stmt = conn.createStatement()) {
                for (int i = 0; i < 3; i++) {
                    stmt.addBatch("INSERT INTO t_contract(name, amount) VALUES ('" + tag + "_b" + i + "', 1.00)");
                }
                out.add(Arrays.toString(stmt.executeBatch()));
                stmt.clearBatch();
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM t_contract WHERE name LIKE '" + tag + "_b%'")) {
                    rs.next();
                    out.add(rs.getLong(1));
                }
            }
            return out;
        });
    }

    @Test
    void 对拍_生成键() throws SQLException {
        ContractOracle.assertParity((conn, tag) -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_contract(name, amount) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, tag + "_gk");
                ps.setBigDecimal(2, new BigDecimal("5.50"));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() && keys.getLong(1) > 0;
                }
            }
        });
    }

    @Test
    void 对拍_预编译参数_标量与数值类型() throws SQLException {
        ContractOracle.assertParity(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, amount FROM t_contract WHERE id = ? OR amount > ? ORDER BY id")) {
                ps.setLong(1, 1L);
                ps.setBigDecimal(2, new BigDecimal("10.00"));
                try (ResultSet rs = ps.executeQuery()) {
                    final List<String> rows = new ArrayList<String>();
                    while (rs.next()) {
                        rows.add(rs.getString(1) + "#" + rs.getBigDecimal(2));
                    }
                    return rows;
                }
            }
        });
    }

    @Test
    void 对拍_预编译参数_字符串null与setObject() throws SQLException {
        ContractOracle.assertParity(conn -> Arrays.asList(
                scalarCount(conn, "SELECT COUNT(*) FROM t_contract WHERE name = ?", ps -> {
                    ps.setString(1, null);
                }),
                scalarCount(conn, "SELECT COUNT(*) FROM t_contract WHERE amount > ?", ps -> {
                    ps.setObject(1, Integer.valueOf(90));
                }),
                scalarCount(conn, "SELECT COUNT(*) FROM t_contract WHERE created > ?", ps -> {
                    ps.setObject(1, java.sql.Timestamp.valueOf("2026-03-01 00:00:00"));
                })));
    }

    @Test
    void 对拍_预编译批量与clearParameters() throws SQLException {
        ContractOracle.assertParity((conn, tag) -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_contract(name, amount) VALUES (?, ?)")) {
                ps.setString(1, tag + "_p0");
                ps.setBigDecimal(2, new BigDecimal("1.00"));
                ps.addBatch();
                ps.clearParameters();
                ps.setString(1, tag + "_p1");
                ps.setBigDecimal(2, new BigDecimal("2.00"));
                ps.addBatch();
                final int[] counts = ps.executeBatch();
                return Arrays.toString(counts);
            }
        });
    }

    @Test
    void 对拍_参数元数据() throws SQLException {
        ContractOracle.assertParity(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM t_contract WHERE name = ? AND amount > ?")) {
                return ps.getParameterMetaData().getParameterCount();
            }
        });
    }

    @Test
    void 对拍_流式控制面透传_D12契约二M1部分() throws SQLException {
        ContractOracle.assertParity(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.setFetchSize(7);
                stmt.setMaxRows(5);
                stmt.setQueryTimeout(9);
                stmt.setMaxFieldSize(0);
                return Arrays.asList(stmt.getFetchSize(), stmt.getMaxRows(),
                        stmt.getQueryTimeout(), stmt.getFetchDirection(),
                        stmt.getResultSetType(), stmt.getResultSetConcurrency());
            }
        });
    }

    @Test
    void 对拍_滚动只读结果集() throws SQLException {
        ContractOracle.assertParity(conn -> {
            try (Statement stmt = conn.createStatement(
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                 ResultSet rs = stmt.executeQuery("SELECT name FROM t_contract ORDER BY id")) {
                rs.last();
                final long last = rs.getRow();
                rs.first();
                final String first = rs.getString(1);
                rs.next();
                final String second = rs.getString(1);
                return Arrays.asList(last, first, second);
            }
        });
    }

    @Test
    void 对拍_语句关闭后操作异常类一致() throws SQLException {
        ContractOracle.assertParity(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.close();
                return stmt.executeQuery("SELECT 1");
            }
        });
        ContractOracle.assertParity(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
                ps.close();
                return ps.executeQuery();
            }
        });
    }

    @Test
    void 对拍_存储过程调用与OUT参数() throws SQLException {
        ContractOracle.assertParity(conn -> {
            try (java.sql.CallableStatement cs = conn.prepareCall("{? = CALL ABS(?)}")) {
                cs.registerOutParameter(1, Types.INTEGER);
                cs.setInt(2, -7);
                cs.execute();
                return Arrays.asList(cs.getInt(1), cs.wasNull());
            }
        });
    }

    @Test
    void 对拍_maxRows截断生效() throws SQLException {
        ContractOracle.assertParity(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.setMaxRows(2);
                try (ResultSet rs = stmt.executeQuery("SELECT name FROM t_contract ORDER BY id")) {
                    long seen = 0;
                    while (rs.next()) {
                        seen++;
                    }
                    return seen;
                }
            }
        });
    }

    @Test
    void 结构契约_语句族包装类型() throws SQLException {
        ContractOracle.bootstrap();
        try (java.sql.Connection conn = ContractOracle.pourPool().getConnection()) {
            assertTrue(conn.createStatement() instanceof PourStatement);
            assertTrue(conn.prepareStatement("SELECT 1") instanceof PourPreparedStatement);
            assertTrue(conn.prepareCall("SELECT 1") instanceof PourCallableStatement);
            assertTrue(conn.createStatement().getConnection() instanceof PourConnection);
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static Object scalarCount(final java.sql.Connection conn, final String sql, final Binder binder)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
