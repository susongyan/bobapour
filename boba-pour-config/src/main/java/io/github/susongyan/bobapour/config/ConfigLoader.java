package io.github.susongyan.bobapour.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import io.github.susongyan.bobapour.config.yaml.MapNode;
import io.github.susongyan.bobapour.config.yaml.ScalarNode;
import io.github.susongyan.bobapour.config.yaml.SeqNode;
import io.github.susongyan.bobapour.config.yaml.Yaml;
import io.github.susongyan.bobapour.config.yaml.YamlNode;
import io.github.susongyan.bobapour.config.yaml.YamlParseException;

/**
 * 配置加载器（T2-②）：YAML 子集树 → {@link PourConfig} 不可变模型。
 *
 * <p><b>校验面（全部 fail-fast，路径精确）</b>：未知键拒绝（顶层六节与各节点键白名单）；
 * 必填缺失与类型不符；actualDataNodes 两段模式语法（每段至多一个 ${a..b}、a≤b，
 * 含区间的段必须有对应策略）；guards 正数；encryptor 引用存在；D13 分区边界
 * （RANGE 上界同型且严格递增=连续不重叠的结构性保证、LIST 值跨分区不重叠、分区名唯一）；
 * 广播表不与分片表同名；绑定组 ≥2 且成员皆为分片表且一表至多一组。</p>
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static PourConfig load(final Path file) {
        try {
            return bind(Yaml.parse(file));
        } catch (final YamlParseException e) {
            throw new ConfigException("YAML 解析失败: " + e.getMessage());
        }
    }

    public static PourConfig load(final String yamlText) {
        try {
            return bind(Yaml.parse(yamlText));
        } catch (final YamlParseException e) {
            throw new ConfigException("YAML 解析失败: " + e.getMessage());
        }
    }

    private static PourConfig bind(final YamlNode root) {
        if (!(root instanceof MapNode)) {
            throw err("根节点", "须为映射（键值结构），实际: " + kind(root));
        }
        final MapNode top = (MapNode) root;
        checkKeys(top, "", "datasources", "sharding", "encrypt", "broadcastTables", "bindingTables", "guards");
        final Map<String, DataSourceConfig> datasources = datasources(optMap(top, "datasources", "datasources"));
        if (datasources.isEmpty()) {
            throw err("datasources", "至少声明一个数据源（直通部署也需要目标库）");
        }
        final Map<String, TableRuleConfig> tables = shardingTables(optMap(optMap(top, "sharding", "sharding"),
                "tables", "sharding.tables"));
        final EncryptRuleConfig encrypt = encrypt(optMap(top, "encrypt", "encrypt"));
        final List<String> broadcast = stringSeq(optSeq(top, "broadcastTables", "broadcastTables"),
                "broadcastTables");
        final List<List<String>> bindings = bindings(optSeq(top, "bindingTables", "bindingTables"));
        final GuardsConfig guards = guards(optMap(top, "guards", "guards"));
        crossCheck(tables, broadcast, bindings);
        return new PourConfig(datasources, tables, encrypt, broadcast, bindings, guards);
    }

    // ---------- datasources ----------

    private static Map<String, DataSourceConfig> datasources(final MapNode node) {
        final Map<String, DataSourceConfig> out = new LinkedHashMap<String, DataSourceConfig>();
        for (final String name : node.names()) {
            final String path = "datasources." + name;
            final MapNode body = asMap(node.get(name), path);
            checkKeys(body, path, "className", "props");
            out.put(name, new DataSourceConfig(name, requireString(body.get("className"), path + ".className"),
                    stringProps(optMap(body, "props", path + ".props"), path + ".props")));
        }
        return out;
    }

    // ---------- sharding ----------

    private static Map<String, TableRuleConfig> shardingTables(final MapNode tables) {
        final Map<String, TableRuleConfig> out = new LinkedHashMap<String, TableRuleConfig>();
        for (final String name : tables.names()) {
            final String path = "sharding.tables." + name;
            final MapNode body = asMap(tables.get(name), path);
            checkKeys(body, path, "actualDataNodes", "databaseStrategy", "tableStrategy", "crossShardQuery",
                    "partition");
            final String nodes = requireString(body.get("actualDataNodes"), path + ".actualDataNodes");
            final boolean[] hasRange = checkDataNodes(nodes, path + ".actualDataNodes");
            final StrategyConfig database = strategy(optMap(body, "databaseStrategy", path + ".databaseStrategy"),
                    path + ".databaseStrategy");
            if (hasRange[0] && null == database) {
                throw err(path + ".databaseStrategy", "actualDataNodes 库段含区间但未配置库维策略");
            }
            final StrategyConfig table = strategy(optMap(body, "tableStrategy", path + ".tableStrategy"),
                    path + ".tableStrategy");
            if (hasRange[1] && null == table) {
                throw err(path + ".tableStrategy", "actualDataNodes 表段含区间但未配置表维策略");
            }
            final CrossShardQuery crossShard = null == body.get("crossShardQuery") ? CrossShardQuery.REJECT
                    : enumValue(body.get("crossShardQuery"), path + ".crossShardQuery", CrossShardQuery.values());
            final PartitionConfig partition = partition(optMap(body, "partition", path + ".partition"),
                    path + ".partition");
            out.put(name, new TableRuleConfig(nodes, database, table, crossShard, partition));
        }
        return out;
    }

    private static StrategyConfig strategy(final MapNode node, final String path) {
        if (node.isEmpty()) {
            return null;
        }
        checkKeys(node, path, "shardingColumn", "algorithm");
        final MapNode algorithm = asMap(node.get("algorithm"), path + ".algorithm");
        checkKeys(algorithm, path + ".algorithm", "type", "props");
        return new StrategyConfig(requireString(node.get("shardingColumn"), path + ".shardingColumn"),
                new AlgorithmConfig(requireString(algorithm.get("type"), path + ".algorithm.type"),
                        stringProps(optMap(algorithm, "props", path + ".algorithm.props"),
                                path + ".algorithm.props")));
    }

    /**
     * actualDataNodes 语法校验：两段（库.表，以 ${} 记号外的 '.' 分段）；每段至多一个
     * ${a..b}（a≤b 十进制），记号外为字面前缀/后缀；裸 '$'/'}' 非法。
     * 返回 [库段含区间, 表段含区间]。
     */
    static boolean[] checkDataNodes(final String pattern, final String path) {
        final List<String> segments = splitSegments(pattern);
        if (2 != segments.size() || segments.get(0).isEmpty() || segments.get(1).isEmpty()) {
            throw err(path, "须为两段 '库.表' 形态，实际: " + pattern);
        }
        return new boolean[]{checkSegment(segments.get(0), path + "（库段）"), checkSegment(segments.get(1), path + "（表段）")};
    }

    /** 段切分：只认 ${..} 记号外的 '.'（记号内的 ".." 与数字不被切）。 */
    private static List<String> splitSegments(final String pattern) {
        final List<String> out = new ArrayList<String>();
        final StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (!inToken && '$' == c && i + 1 < pattern.length() && '{' == pattern.charAt(i + 1)) {
                inToken = true;
                cur.append("${");
                i++;
            } else if (inToken && '}' == c) {
                inToken = false;
                cur.append(c);
            } else if (!inToken && '.' == c) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static boolean checkSegment(final String segment, final String path) {
        final int open = segment.indexOf("${");
        if (0 > open) {
            if (segment.indexOf('$') >= 0 || segment.indexOf('}') >= 0) {
                throw err(path, "裸 '$'/'}' 非法（区间记号须为 ${a..b} 形态）: " + segment);
            }
            return false;
        }
        final int close = segment.indexOf('}', open);
        if (0 > close || 0 <= segment.indexOf("${", open + 2) || 0 <= segment.indexOf('}', close + 1)) {
            throw err(path, "每段至多一个 ${a..b} 区间记号: " + segment);
        }
        final String inner = segment.substring(open + 2, close);
        final String[] bounds = inner.split("\\.\\.", -1);
        if (2 != bounds.length || !bounds[0].matches("\\d+") || !bounds[1].matches("\\d+")) {
            throw err(path, "区间记号须为 ${a..b}（十进制整数）: " + segment);
        }
        if (Long.parseLong(bounds[0]) > Long.parseLong(bounds[1])) {
            throw err(path, "区间下界大于上界: " + segment);
        }
        return true;
    }

    // ---------- partition（D13） ----------

    private static PartitionConfig partition(final MapNode node, final String path) {
        if (node.isEmpty()) {
            return null;
        }
        checkKeys(node, path, "key", "type", "partitions");
        final PartitionType type = enumValue(requireNode(node, "type", path), path + ".type",
                PartitionType.values());
        final SeqNode partitions = asSeq(requireNode(node, "partitions", path), path + ".partitions");
        if (partitions.isEmpty()) {
            throw err(path + ".partitions", "至少声明一个分区边界");
        }
        final List<PartitionBound> bounds = new ArrayList<PartitionBound>();
        for (int i = 0; i < partitions.size(); i++) {
            final String itemPath = path + ".partitions[" + i + "]";
            final MapNode item = asMap(partitions.items().get(i), itemPath);
            final String name = requireString(item.get("name"), itemPath + ".name");
            for (final PartitionBound seen : bounds) {
                if (seen.name().equals(name)) {
                    throw err(itemPath + ".name", "分区名重复: " + name);
                }
            }
            if (PartitionType.RANGE == type) {
                checkKeys(item, itemPath, "name", "upper");
                final Object upper = requireScalar(item.get("upper"), itemPath + ".upper");
                if (!(upper instanceof Long) && !(upper instanceof String)) {
                    throw err(itemPath + ".upper", "上界须为整数或字符串，实际: " + upper.getClass().getSimpleName());
                }
                bounds.add(PartitionBound.range(name, upper));
            } else {
                checkKeys(item, itemPath, "name", "values");
                final List<String> values = stringSeq(asSeq(requireNode(item, "values", itemPath),
                        itemPath + ".values"), itemPath + ".values");
                if (values.isEmpty()) {
                    throw err(itemPath + ".values", "值清单不能为空");
                }
                bounds.add(PartitionBound.list(name, values));
            }
        }
        if (PartitionType.RANGE == type) {
            checkRangeAscending(bounds, path);
        } else {
            checkListDisjoint(bounds, path);
        }
        return new PartitionConfig(requireString(node.get("key"), path + ".key"), type, bounds);
    }

    /** RANGE 上界：全表同型（Long 或 String）且严格递增——连续与不重叠的结构性保证。 */
    private static void checkRangeAscending(final List<PartitionBound> bounds, final String path) {
        Class<?> type = null;
        Comparable<Object> prev = null;
        for (int i = 0; i < bounds.size(); i++) {
            final Object upper = bounds.get(i).upper();
            if (null == type) {
                type = upper.getClass();
            } else if (!type.equals(upper.getClass())) {
                throw err(path + ".partitions[" + i + "].upper",
                        "上界类型须一致（" + type.getSimpleName() + " vs " + upper.getClass().getSimpleName() + "）");
            }
            @SuppressWarnings("unchecked")
            final Comparable<Object> cur = (Comparable<Object>) upper;
            if (null != prev && 0 <= prev.compareTo(upper)) {
                throw err(path + ".partitions[" + i + "].upper", "上界须严格递增（连续不重叠）");
            }
            prev = cur;
        }
    }

    private static void checkListDisjoint(final List<PartitionBound> bounds, final String path) {
        final Map<String, String> owner = new LinkedHashMap<String, String>();
        for (int i = 0; i < bounds.size(); i++) {
            for (final String value : bounds.get(i).values()) {
                final String seen = owner.put(value, bounds.get(i).name());
                if (null != seen) {
                    throw err(path + ".partitions[" + i + "].values",
                            "值 '" + value + "' 跨分区重复（" + seen + " 与 " + bounds.get(i).name() + "）");
                }
            }
        }
    }

    // ---------- encrypt ----------

    private static EncryptRuleConfig encrypt(final MapNode node) {
        checkKeys(node, "encrypt", "encryptors", "tables");
        final Map<String, AlgorithmConfig> encryptors = new LinkedHashMap<String, AlgorithmConfig>();
        final MapNode encryptorNode = optMap(node, "encryptors", "encrypt.encryptors");
        for (final String name : encryptorNode.names()) {
            final String path = "encrypt.encryptors." + name;
            final MapNode body = asMap(encryptorNode.get(name), path);
            checkKeys(body, path, "type", "props");
            encryptors.put(name, new AlgorithmConfig(requireString(body.get("type"), path + ".type"),
                    stringProps(optMap(body, "props", path + ".props"), path + ".props")));
        }
        final Map<String, Map<String, EncryptColumnConfig>> tables =
                new LinkedHashMap<String, Map<String, EncryptColumnConfig>>();
        final MapNode tableNode = optMap(node, "tables", "encrypt.tables");
        for (final String table : tableNode.names()) {
            final String tablePath = "encrypt.tables." + table;
            final MapNode columns = optMap(asMap(tableNode.get(table), tablePath), "columns",
                    tablePath + ".columns");
            final Map<String, EncryptColumnConfig> columnMap = new LinkedHashMap<String, EncryptColumnConfig>();
            for (final String column : columns.names()) {
                final String path = tablePath + ".columns." + column;
                final MapNode body = asMap(columns.get(column), path);
                checkKeys(body, path, "cipherColumn", "plainColumn", "assistedQueryColumn", "encryptor",
                        "deterministic");
                final String encryptor = requireString(body.get("encryptor"), path + ".encryptor");
                if (!encryptors.containsKey(encryptor)) {
                    throw err(path + ".encryptor", "引用未声明的 encryptor: " + encryptor);
                }
                columnMap.put(column, new EncryptColumnConfig(
                        requireString(body.get("cipherColumn"), path + ".cipherColumn"),
                        optString(body.get("plainColumn"), path + ".plainColumn"),
                        optString(body.get("assistedQueryColumn"), path + ".assistedQueryColumn"),
                        encryptor, optBoolean(body.get("deterministic"), path + ".deterministic", false)));
            }
            tables.put(table, columnMap);
        }
        return new EncryptRuleConfig(encryptors, tables);
    }

    // ---------- broadcast / binding / guards ----------

    private static List<List<String>> bindings(final SeqNode node) {
        final List<List<String>> out = new ArrayList<List<String>>();
        for (int i = 0; i < node.size(); i++) {
            out.add(stringSeq(asSeq(node.items().get(i), "bindingTables[" + i + "]"), "bindingTables[" + i + "]"));
        }
        return out;
    }

    private static GuardsConfig guards(final MapNode node) {
        if (node.isEmpty()) {
            return new GuardsConfig();
        }
        checkKeys(node, "guards", "maxShardsPerQuery", "maxMergeWindowRows", "streamingFetch");
        final Long shards = optPositive(node.get("maxShardsPerQuery"), "guards.maxShardsPerQuery");
        final Long window = optPositive(node.get("maxMergeWindowRows"), "guards.maxMergeWindowRows");
        final Boolean streaming = optBoolean(node.get("streamingFetch"), "guards.streamingFetch", true);
        return new GuardsConfig(null == shards ? 8 : shards.intValue(),
                null == window ? 10000 : window.intValue(), streaming);
    }

    private static void crossCheck(final Map<String, TableRuleConfig> tables, final List<String> broadcast,
            final List<List<String>> bindings) {
        for (final String table : broadcast) {
            if (tables.containsKey(table)) {
                throw err("broadcastTables", table + " 同时声明为分片表与广播表");
            }
        }
        final Map<String, Integer> grouped = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < bindings.size(); i++) {
            final List<String> group = bindings.get(i);
            if (2 > group.size()) {
                throw err("bindingTables[" + i + "]", "绑定组至少两表，实际 " + group.size());
            }
            for (final String table : group) {
                if (!tables.containsKey(table)) {
                    throw err("bindingTables[" + i + "]", table + " 不是分片表（绑定组成员须皆为分片表）");
                }
                final Integer seen = grouped.put(table, Integer.valueOf(i));
                if (null != seen) {
                    throw err("bindingTables[" + i + "]", table + " 已在组 " + seen + " 中（一表至多一组）");
                }
            }
        }
    }

    // ---------- 节点访问与标量助手 ----------

    private static void checkKeys(final MapNode node, final String path, final String... allowed) {
        for (final String name : node.names()) {
            boolean known = false;
            for (final String key : allowed) {
                if (key.equals(name)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw err(path.isEmpty() ? name : path + "." + name, "未知配置键（允许: "
                        + String.join(", ", allowed) + "）");
            }
        }
    }

    private static MapNode optMap(final MapNode parent, final String key, final String path) {
        final YamlNode node = parent.get(key);
        return null == node ? MapNode.empty() : asMap(node, path);
    }

    private static SeqNode optSeq(final MapNode parent, final String key, final String path) {
        final YamlNode node = parent.get(key);
        return null == node ? SeqNode.empty() : asSeq(node, path);
    }

    private static YamlNode requireNode(final MapNode parent, final String key, final String path) {
        final YamlNode node = parent.get(key);
        if (null == node) {
            throw err(path, "缺少必填项");
        }
        return node;
    }

    private static MapNode asMap(final YamlNode node, final String path) {
        if (!(node instanceof MapNode)) {
            throw err(path, "须为映射，实际: " + kind(node));
        }
        return (MapNode) node;
    }

    private static SeqNode asSeq(final YamlNode node, final String path) {
        if (!(node instanceof SeqNode)) {
            throw err(path, "须为序列，实际: " + kind(node));
        }
        return (SeqNode) node;
    }

    private static Object requireScalar(final YamlNode node, final String path) {
        if (null == node) {
            throw err(path, "缺少必填项");
        }
        if (!(node instanceof ScalarNode) || null == ((ScalarNode) node).value()) {
            throw err(path, "须为非空标量");
        }
        return ((ScalarNode) node).value();
    }

    private static String requireString(final YamlNode node, final String path) {
        final Object value = requireScalar(node, path);
        return String.valueOf(value);
    }

    private static String optString(final YamlNode node, final String path) {
        if (null == node) {
            return null;
        }
        final Object value = requireScalar(node, path);
        return String.valueOf(value);
    }

    private static <T extends Enum<T>> T enumValue(final YamlNode node, final String path, final T[] values) {
        final String text = requireString(node, path);
        for (final T value : values) {
            if (value.name().equals(text)) {
                return value;
            }
        }
        throw err(path, "非法枚举值 '" + text + "'（允许: " + java.util.Arrays.toString(values) + "）");
    }

    private static Long optPositive(final YamlNode node, final String path) {
        if (null == node) {
            return null;
        }
        if (!(node instanceof ScalarNode) || !(((ScalarNode) node).value() instanceof Long)) {
            throw err(path, "须为整数");
        }
        final long value = ((Long) ((ScalarNode) node).value()).longValue();
        if (0 >= value) {
            throw err(path, "须为正整数，实际 " + value);
        }
        return Long.valueOf(value);
    }

    private static Boolean optBoolean(final YamlNode node, final String path, final boolean defaultValue) {
        if (null == node) {
            return Boolean.valueOf(defaultValue);
        }
        if (!(node instanceof ScalarNode) || !(((ScalarNode) node).value() instanceof Boolean)) {
            throw err(path, "须为布尔");
        }
        return (Boolean) ((ScalarNode) node).value();
    }

    private static List<String> stringSeq(final SeqNode node, final String path) {
        final List<String> out = new ArrayList<String>();
        for (int i = 0; i < node.size(); i++) {
            out.add(requireString(node.items().get(i), path + "[" + i + "]"));
        }
        return out;
    }

    private static Map<String, String> stringProps(final MapNode node, final String path) {
        final Map<String, String> out = new LinkedHashMap<String, String>();
        for (final String key : node.names()) {
            out.put(key, requireString(node.get(key), path + "." + key));
        }
        return out;
    }

    private static String kind(final YamlNode node) {
        if (null == node) {
            return "空";
        }
        if (node instanceof MapNode) {
            return "映射";
        }
        return node instanceof SeqNode ? "序列" : "标量";
    }

    private static ConfigException err(final String path, final String reason) {
        return new ConfigException("配置错误: " + path + ": " + reason);
    }
}
