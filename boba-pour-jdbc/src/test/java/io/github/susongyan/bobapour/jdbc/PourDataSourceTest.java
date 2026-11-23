package io.github.susongyan.bobapour.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import io.github.susongyan.bobapour.config.ConfigException;
import io.github.susongyan.bobapour.config.ConfigLoader;
import io.github.susongyan.bobapour.config.model.PourConfig;

/**
 * T5/T7 契约矩阵——入口形态与借出契约（D11/D12）：
 * 注入/反射两种池供给形态可用；T7 起分片/广播配置进引擎模式（Map 注入或反射构造），
 * 加密配置 M3 前构造即拒（D8 fail-fast）；
 * 契约①借出显式设满连接状态——污染状态归还后重借出，逐字段回到首次借出捕获的基线。
 */
class PourDataSourceTest {

    // ---------- 构造形态（D12） ----------

    @Test
    void 注入形态_借出可用_与原生同库同元数据() throws SQLException {
        ContractOracle.bootstrap();
        final PourDataSource pour = new PourDataSource(ContractOracle.passthroughConfig(), ContractOracle.rawPool());
        try (Connection conn = pour.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t_contract WHERE name LIKE 'seed_%'")) {
            rs.next();
            assertEquals(3, rs.getLong(1));
            assertEquals("H2", conn.getMetaData().getDatabaseProductName());
        }
    }

    @Test
    void 反射形态_借出可用() throws SQLException {
        ContractOracle.bootstrap();
        final PourDataSource pour = new PourDataSource(ContractOracle.passthroughConfig());
        try (Connection conn = pour.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t_contract")) {
            rs.next();
            assertTrue(rs.getLong(1) >= 3);
        }
    }

    @Test
    void 反射形态_无匹配setter显式拒绝() {
        final PourConfig config = ConfigLoader.load(String.join("\n",
                "datasources:",
                "  ds_0:",
                "    className: org.h2.jdbcx.JdbcDataSource",
                "    props:",
                "      URL: " + ContractOracle.URL,
                "      noSuchProp: x",
                ""));
        final ConfigException e = assertThrows(ConfigException.class, () -> new PourDataSource(config));
        assertTrue(e.getMessage().contains("props.noSuchProp") && e.getMessage().contains("无匹配 setter"),
                e.getMessage());
    }

    @Test
    void 反射形态_类不存在拒绝() {
        final PourConfig config = ConfigLoader.load(String.join("\n",
                "datasources:",
                "  ds_0:",
                "    className: com.no.such.Pool",
                ""));
        final ConfigException e = assertThrows(ConfigException.class, () -> new PourDataSource(config));
        assertTrue(e.getMessage().contains("dataSourceClassName 反射构造失败"), e.getMessage());
    }

    @Test
    void 反射形态_整型属性按类型收敛() throws SQLException {
        final PourConfig config = ConfigLoader.load(String.join("\n",
                "datasources:",
                "  ds_0:",
                "    className: org.h2.jdbcx.JdbcDataSource",
                "    props:",
                "      URL: " + ContractOracle.URL,
                "      user: sa",
                "      loginTimeout: 30",
                ""));
        final PourDataSource pour = new PourDataSource(config);
        assertEquals(30, pour.getLoginTimeout());
    }

    // ---------- 模式裁决守卫（D8 fail-fast；T7 起分片/广播进引擎模式） ----------

    @Test
    void 拒绝_分片配置_单实例注入为直通专用() {
        final PourConfig config = ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X",
                "sharding:", "  tables:", "    t:",
                "      actualDataNodes: d.t",
                "      databaseStrategy:",
                "        shardingColumn: k",
                "        algorithm:",
                "          type: MOD",
                "          props:",
                "            count: 1",
                ""));
        final ConfigException e = assertThrows(ConfigException.class,
                () -> new PourDataSource(config, ContractOracle.rawPool()));
        assertTrue(e.getMessage().contains("单实例注入为直通专用"), e.getMessage());
    }

    @Test
    void 拒绝_加密配置_M3前不放行() {
        final PourConfig config = ConfigLoader.load(String.join("\n",
                "datasources:", "  ds_0:", "    className: X",
                "encrypt:", "  encryptors:", "    aes:", "      type: AES",
                "  tables:", "    t_order:", "      columns:", "        phone:",
                "          cipherColumn: phone_cipher", "          encryptor: aes",
                ""));
        assertThrows(ConfigException.class, () -> new PourDataSource(config, ContractOracle.rawPool()));
    }

    @Test
    void 拒绝_引擎模式_未供给池实例() {
        final PourConfig config = ConfigLoader.load(String.join("\n",
                "datasources:",
                "  ds_0:", "    className: org.h2.jdbcx.JdbcDataSource",
                "  ds_1:", "    className: org.h2.jdbcx.JdbcDataSource",
                "sharding:", "  tables:", "    t:",
                "      actualDataNodes: ds_${0..1}.t",
                "      databaseStrategy:",
                "        shardingColumn: k",
                "        algorithm:",
                "          type: MOD",
                "          props:",
                "            count: 2",
                ""));
        final ConfigException e = assertThrows(ConfigException.class,
                () -> new PourDataSource(config, java.util.Collections.singletonMap(
                        "ds_0", ContractOracle.rawPool())));
        assertTrue(e.getMessage().contains("ds_1") && e.getMessage().contains("未供给池实例"), e.getMessage());
    }

    @Test
    void 引擎模式_广播配置_Map注入可用() throws SQLException {
        ContractOracle.bootstrap();
        final PourConfig config = ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X",
                "broadcastTables:", "  - t_contract",
                ""));
        final java.util.Map<String, javax.sql.DataSource> pools =
                new java.util.LinkedHashMap<String, javax.sql.DataSource>();
        pools.put("d", ContractOracle.rawPool());
        final PourDataSource pour = new PourDataSource(config, pools);
        assertTrue(pour.engineMode(), "广播表即引擎模式");
        try (Connection conn = pour.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t_contract")) {
            rs.next();
            assertTrue(rs.getLong(1) >= 3, "广播读取样首数据源（对照库播种行可见）");
        }
    }

    @Test
    void 拒绝_多数据源_直通须恰一() {
        final PourConfig config = ConfigLoader.load(String.join("\n",
                "datasources:",
                "  ds_0:", "    className: org.h2.jdbcx.JdbcDataSource",
                "  ds_1:", "    className: org.h2.jdbcx.JdbcDataSource",
                ""));
        final ConfigException e = assertThrows(ConfigException.class, () -> new PourDataSource(config));
        assertTrue(e.getMessage().contains("恰一个物理数据源"), e.getMessage());
    }

    // ---------- 契约①：借出显式设满连接状态（D12） ----------

    @Test
    void 契约一_污染状态归还后重借出_逐字段回到基线() throws SQLException {
        ContractOracle.bootstrap();
        final PourDataSource pour = new PourDataSource(ContractOracle.passthroughConfig(), ContractOracle.rawPool());
        final boolean firstAutoCommit;
        final int firstIsolation;
        final int firstHoldability;
        final String firstSchema;
        try (Connection first = pour.getConnection()) {
            firstAutoCommit = first.getAutoCommit();
            firstIsolation = first.getTransactionIsolation();
            firstHoldability = first.getHoldability();
            firstSchema = first.getSchema();
            // 污染：借出期内全部改写（模拟上一借用者归还前的状态）
            first.setAutoCommit(false);
            first.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            first.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
            first.setSchema("INFORMATION_SCHEMA");
        }
        try (Connection second = pour.getConnection()) {
            assertEquals(firstAutoCommit, second.getAutoCommit(), "autoCommit 应回到基线");
            assertEquals(firstIsolation, second.getTransactionIsolation(), "隔离级应回到基线");
            assertEquals(firstHoldability, second.getHoldability(), "holdability 应回到基线");
            assertEquals(firstSchema, second.getSchema(), "schema 应回到基线");
        }
    }

    /**
     * H2 2.x 的 setReadOnly(true) 不被 isReadOnly() 回读（恒 false，已实测——oracle 能力边界，
     * 记录在案）：readOnly 重置契约在本 oracle 上不可回读验证，本用例退而锚定"污染归还后
     * 借出路径照常设满不抛、基线值恒 false"。
     */
    @Test
    void 契约一_只读污染归还后重借出_基线照发不抛() throws SQLException {
        ContractOracle.bootstrap();
        final PourDataSource pour = new PourDataSource(ContractOracle.passthroughConfig(), ContractOracle.rawPool());
        try (Connection first = pour.getConnection()) {
            assertFalse(first.isReadOnly(), "H2 默认非只读（基线前提）");
            first.setReadOnly(true);
        }
        try (Connection second = pour.getConnection()) {
            assertFalse(second.isReadOnly(), "readOnly 应回到基线（借出显式设满，H2 恒 false）");
        }
    }

    @Test
    void 契约一_两次借出状态一致() throws SQLException {
        ContractOracle.bootstrap();
        final PourDataSource pour = new PourDataSource(ContractOracle.passthroughConfig(), ContractOracle.rawPool());
        try (Connection a = pour.getConnection(); Connection b = pour.getConnection()) {
            assertEquals(a.getAutoCommit(), b.getAutoCommit());
            assertEquals(a.getTransactionIsolation(), b.getTransactionIsolation());
            assertEquals(a.isReadOnly(), b.isReadOnly());
            assertEquals(a.getCatalog(), b.getCatalog());
            assertEquals(a.getSchema(), b.getSchema());
            assertEquals(a.getHoldability(), b.getHoldability());
        }
    }

    // ---------- Wrapper 与生命周期 ----------

    @Test
    void Wrapper契约_自身与底层() throws SQLException {
        final JdbcDataSource pool = ContractOracle.rawPool();
        final PourDataSource pour = new PourDataSource(ContractOracle.passthroughConfig(), pool);
        assertTrue(pour.isWrapperFor(PourDataSource.class));
        assertTrue(pour.isWrapperFor(javax.sql.DataSource.class));
        assertEquals(pour, pour.unwrap(PourDataSource.class));
        assertEquals(pool, pour.unwrap(JdbcDataSource.class));
    }

    @Test
    void close_两种形态均幂等不抛() {
        final PourDataSource injected = new PourDataSource(ContractOracle.passthroughConfig(), ContractOracle.rawPool());
        final PourDataSource reflected = new PourDataSource(ContractOracle.passthroughConfig());
        assertDoesNotThrow(() -> {
            injected.close();
            injected.close();
            reflected.close();
            reflected.close();
        });
    }
}
