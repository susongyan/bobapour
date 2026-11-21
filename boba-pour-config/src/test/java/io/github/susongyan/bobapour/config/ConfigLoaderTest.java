package io.github.susongyan.bobapour.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.susongyan.bobapour.config.model.AlgorithmConfig;
import io.github.susongyan.bobapour.config.model.CrossShardQuery;
import io.github.susongyan.bobapour.config.model.DataSourceConfig;
import io.github.susongyan.bobapour.config.model.EncryptColumnConfig;
import io.github.susongyan.bobapour.config.model.EncryptRuleConfig;
import io.github.susongyan.bobapour.config.model.GuardsConfig;
import io.github.susongyan.bobapour.config.model.PartitionBound;
import io.github.susongyan.bobapour.config.model.PartitionConfig;
import io.github.susongyan.bobapour.config.model.PartitionType;
import io.github.susongyan.bobapour.config.model.PourConfig;
import io.github.susongyan.bobapour.config.model.StrategyConfig;
import io.github.susongyan.bobapour.config.model.TableRuleConfig;

/**
 * 配置加载器黄金用例（T2-②）：全 schema 黄金 YAML 逐字段等价（equals 全等）；
 * 最小配置走全默认（直通部署）；校验面逐条负例（未知键/缺必填/模式语法/枚举/
 * 引用断裂/分区边界非法/交叉冲突）——拒绝面 = ConfigLoader javadoc 声明面的回归。
 */
final class ConfigLoaderTest {

    /** 全 schema 黄金（两数据源、一分片表带双策略+RANGE 分区、一字面分片表、加密两列、广播/绑定）。 */
    private static final String GOLDEN = String.join("\n",
        "datasources:",
        "  ds_0:",
        "    className: com.example.pool.DataSource",
        "    props:",
        "      jdbcUrl: jdbc:mysql://h0:3306/demo",
        "      maximumPoolSize: 20",
        "  ds_1:",
        "    className: com.example.pool.DataSource",
        "    props:",
        "      jdbcUrl: jdbc:mysql://h1:3306/demo",
        "sharding:",
        "  tables:",
        "    t_order:",
        "      actualDataNodes: ds_${0..1}.t_order_${0..7}",
        "      databaseStrategy:",
        "        shardingColumn: user_id",
        "        algorithm:",
        "          type: MOD",
        "          props:",
        "            count: 2",
        "      tableStrategy:",
        "        shardingColumn: order_id",
        "        algorithm:",
        "          type: MOD",
        "          props:",
        "            count: 8",
        "      crossShardQuery: WHITELIST",
        "      partition:",
        "        key: created_at",
        "        type: RANGE",
        "        partitions:",
        "          - name: p2025",
        "            upper: '2026-01-01'",
        "          - name: p2026",
        "            upper: '2027-01-01'",
        "    t_order_item:",
        "      actualDataNodes: ds_0.t_order_item",
        "encrypt:",
        "  encryptors:",
        "    aes_det:",
        "      type: AES",
        "      props:",
        "        mode: deterministic",
        "  tables:",
        "    t_order:",
        "      columns:",
        "        phone:",
        "          cipherColumn: phone_cipher",
        "          assistedQueryColumn: phone_assisted",
        "          encryptor: aes_det",
        "          deterministic: true",
        "        id_card:",
        "          cipherColumn: id_card_cipher",
        "          encryptor: aes_det",
        "broadcastTables:",
        "  - t_config",
        "bindingTables:",
        "  - - t_order",
        "    - t_order_item",
        "guards:",
        "  maxShardsPerQuery: 4",
        "  maxMergeWindowRows: 5000",
        "  streamingFetch: false",
        "");

    @Test
    void 全schema黄金逐字段等价() {
        final PourConfig config = ConfigLoader.load(GOLDEN);
        final Map<String, String> props0 = new LinkedHashMap<String, String>();
        props0.put("jdbcUrl", "jdbc:mysql://h0:3306/demo");
        props0.put("maximumPoolSize", "20");
        final Map<String, String> props1 = new LinkedHashMap<String, String>();
        props1.put("jdbcUrl", "jdbc:mysql://h1:3306/demo");
        final Map<String, DataSourceConfig> datasources = new LinkedHashMap<String, DataSourceConfig>();
        datasources.put("ds_0", new DataSourceConfig("ds_0", "com.example.pool.DataSource", props0));
        datasources.put("ds_1", new DataSourceConfig("ds_1", "com.example.pool.DataSource", props1));
        final Map<String, String> mod2 = new LinkedHashMap<String, String>();
        mod2.put("count", "2");
        final Map<String, String> mod8 = new LinkedHashMap<String, String>();
        mod8.put("count", "8");
        final Map<String, String> aesProps = new LinkedHashMap<String, String>();
        aesProps.put("mode", "deterministic");
        final PartitionConfig partition = new PartitionConfig("created_at", PartitionType.RANGE,
                Arrays.asList(PartitionBound.range("p2025", "2026-01-01"),
                        PartitionBound.range("p2026", "2027-01-01")));
        final Map<String, TableRuleConfig> tables = new LinkedHashMap<String, TableRuleConfig>();
        tables.put("t_order", new TableRuleConfig("ds_${0..1}.t_order_${0..7}",
                new StrategyConfig("user_id", new AlgorithmConfig("MOD", mod2)),
                new StrategyConfig("order_id", new AlgorithmConfig("MOD", mod8)),
                CrossShardQuery.WHITELIST, partition));
        tables.put("t_order_item", new TableRuleConfig("ds_0.t_order_item", null, null,
                CrossShardQuery.REJECT, null));
        final Map<String, AlgorithmConfig> encryptors = new LinkedHashMap<String, AlgorithmConfig>();
        encryptors.put("aes_det", new AlgorithmConfig("AES", aesProps));
        final Map<String, EncryptColumnConfig> orderColumns = new LinkedHashMap<String, EncryptColumnConfig>();
        orderColumns.put("phone", new EncryptColumnConfig("phone_cipher", null, "phone_assisted",
                "aes_det", true));
        orderColumns.put("id_card", new EncryptColumnConfig("id_card_cipher", null, null, "aes_det", false));
        final Map<String, Map<String, EncryptColumnConfig>> encryptTables =
                new LinkedHashMap<String, Map<String, EncryptColumnConfig>>();
        encryptTables.put("t_order", orderColumns);
        assertEquals(new PourConfig(datasources, tables,
                new EncryptRuleConfig(encryptors, encryptTables),
                Arrays.asList("t_config"),
                Arrays.asList(Arrays.asList("t_order", "t_order_item")),
                new GuardsConfig(4, 5000, false)), config);
    }

    @Test
    void 最小配置全默认() {
        final PourConfig config = ConfigLoader.load(
                "datasources:\n  ds_0:\n    className: com.example.X\n");
        assertEquals(1, config.datasources().size());
        assertTrue(config.shardingTables().isEmpty());
        assertTrue(config.encrypt().tables().isEmpty());
        assertTrue(config.broadcastTables().isEmpty());
        assertEquals(new GuardsConfig(), config.guards());
    }

    @Test
    void list分区黄金() {
        final PourConfig config = ConfigLoader.load(String.join("\n",
            "datasources:",
            "  ds_0:",
            "    className: com.example.X",
            "sharding:",
            "  tables:",
            "    t_hot:",
            "      actualDataNodes: ds_${0..0}.t_hot",
            "      databaseStrategy:",
            "        shardingColumn: region",
            "        algorithm:",
            "          type: MOD",
            "          props:",
            "            count: 1",
            "      partition:",
            "        key: region",
            "        type: LIST",
            "        partitions:",
            "          - name: p_north",
            "            values:",
            "              - bj",
            "              - tj",
            "          - name: p_south",
            "            values:",
            "              - sz",
            ""));
        final PartitionConfig partition = config.shardingTables().get("t_hot").partition();
        assertEquals(PartitionType.LIST, partition.type());
        assertEquals(Arrays.asList("bj", "tj"), partition.partitions().get(0).values());
        assertEquals(Arrays.asList("sz"), partition.partitions().get(1).values());
    }

    // ---------- 校验面负例 ----------

    @Test
    void 拒绝_未知顶层键() {
        final ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load("datasources:\n  d:\n    className: X\nfoo: 1\n"));
        assertTrue(e.getMessage().contains("foo") && e.getMessage().contains("未知配置键"), e.getMessage());
    }

    @Test
    void 拒绝_未知表级键() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d.t", "      whatever: 1", "")));
    }

    @Test
    void 拒绝_无数据源() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load("guards:\n  maxShardsPerQuery: 2\n"));
    }

    @Test
    void 拒绝_缺actualDataNodes() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      crossShardQuery: WHITELIST", "")));
    }

    @Test
    void 拒绝_单段模式() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: t_order", "")));
    }

    @Test
    void 拒绝_区间倒序() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d_${1..0}.t", "")));
    }

    @Test
    void 拒绝_区间段缺策略() {
        final ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d_${0..1}.t_${0..3}", "")));
        assertTrue(e.getMessage().contains("库维策略"), e.getMessage());
    }

    @Test
    void 拒绝_非法枚举() {
        final ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d.t", "      crossShardQuery: MAYBE", "")));
        assertTrue(e.getMessage().contains("非法枚举值"), e.getMessage());
    }

    @Test
    void 拒绝_分区类型非法() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d.t", "      partition:",
                "        key: k", "        type: HASH", "        partitions:",
                "          - name: p", "            upper: 1", "")));
    }

    @Test
    void 拒绝_range上界非递增() {
        final ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d.t", "      partition:",
                "        key: k", "        type: RANGE", "        partitions:",
                "          - name: p1", "            upper: 100",
                "          - name: p2", "            upper: 50", "")));
        assertTrue(e.getMessage().contains("严格递增"), e.getMessage());
    }

    @Test
    void 拒绝_range上界类型混用() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d.t", "      partition:",
                "        key: k", "        type: RANGE", "        partitions:",
                "          - name: p1", "            upper: 100",
                "          - name: p2", "            upper: 'zzz'", "")));
    }

    @Test
    void 拒绝_list值跨分区重复() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d.t", "      partition:",
                "        key: k", "        type: LIST", "        partitions:",
                "          - name: p1", "            values:", "              - a",
                "          - name: p2", "            values:", "              - a", "")));
    }

    @Test
    void 拒绝_加密引用未声明算法() {
        final ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "encrypt:", "  tables:",
                "    t:", "      columns:", "        c:", "          cipherColumn: c_cipher",
                "          encryptor: nope", "")));
        assertTrue(e.getMessage().contains("未声明"), e.getMessage());
    }

    @Test
    void 拒绝_guard非正数() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "guards:",
                "  maxShardsPerQuery: 0", "")));
    }

    @Test
    void 拒绝_广播与分片同名() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d.t", "broadcastTables:", "  - t", "")));
    }

    @Test
    void 拒绝_绑定成员非分片表() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d.t", "bindingTables:", "  - - t", "    - missing", "")));
    }

    @Test
    void 拒绝_绑定组单表() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(String.join("\n",
                "datasources:", "  d:", "    className: X", "sharding:", "  tables:",
                "    t:", "      actualDataNodes: d.t", "bindingTables:", "  - - t", "")));
    }

    @Test
    void 拒绝_yaml语法错误包装() {
        final ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load("datasources: [x]\n"));
        assertTrue(e.getMessage().contains("YAML 解析失败"), e.getMessage());
    }
}
