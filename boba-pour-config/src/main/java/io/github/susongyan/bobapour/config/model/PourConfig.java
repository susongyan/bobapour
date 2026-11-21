package io.github.susongyan.bobapour.config.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 配置根模型（不可变）。加载自 YAML 子集（见 {@code io.github.susongyan.bobapour.config.ConfigLoader}），
 * 顶层六节，未知键一律拒绝（配置面的白名单治理——拼错键即启动失败，不静默忽略）：
 *
 * <pre>
 * datasources:      name → {className, props}          D12 池由部署方供给
 * sharding.tables:  逻辑表 → TableRuleConfig           分片规则
 * encrypt:          EncryptRuleConfig                  加密规则
 * broadcastTables:  表名序列                            D10 广播表
 * bindingTables:    表名组的序列（每组≥2，成员皆分片表）  绑定表
 * guards:           GuardsConfig（缺省 8/10000/true）
 * </pre>
 *
 * <p>未出现在任何规则中的表 = 直通表（D8：分片配置为空的表天然走直通）。</p>
 */
public final class PourConfig {

    private final Map<String, DataSourceConfig> datasources;
    private final Map<String, TableRuleConfig> shardingTables;
    private final EncryptRuleConfig encrypt;
    private final List<String> broadcastTables;
    private final List<List<String>> bindingGroups;
    private final GuardsConfig guards;

    public PourConfig(final Map<String, DataSourceConfig> datasources,
            final Map<String, TableRuleConfig> shardingTables, final EncryptRuleConfig encrypt,
            final List<String> broadcastTables, final List<List<String>> bindingGroups,
            final GuardsConfig guards) {
        this.datasources = Collections.unmodifiableMap(new LinkedHashMap<String, DataSourceConfig>(datasources));
        this.shardingTables = Collections.unmodifiableMap(new LinkedHashMap<String, TableRuleConfig>(shardingTables));
        this.encrypt = encrypt;
        this.broadcastTables = Collections.unmodifiableList(new java.util.ArrayList<String>(broadcastTables));
        this.bindingGroups = Collections.unmodifiableList(new java.util.ArrayList<List<String>>(bindingGroups));
        this.guards = guards;
    }

    public Map<String, DataSourceConfig> datasources() {
        return datasources;
    }

    public Map<String, TableRuleConfig> shardingTables() {
        return shardingTables;
    }

    public EncryptRuleConfig encrypt() {
        return encrypt;
    }

    public List<String> broadcastTables() {
        return broadcastTables;
    }

    public List<List<String>> bindingGroups() {
        return bindingGroups;
    }

    public GuardsConfig guards() {
        return guards;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PourConfig)) {
            return false;
        }
        final PourConfig other = (PourConfig) obj;
        return Objects.equals(datasources, other.datasources)
                && Objects.equals(shardingTables, other.shardingTables)
                && Objects.equals(encrypt, other.encrypt)
                && Objects.equals(broadcastTables, other.broadcastTables)
                && Objects.equals(bindingGroups, other.bindingGroups)
                && Objects.equals(guards, other.guards);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datasources, shardingTables, encrypt, broadcastTables, bindingGroups, guards);
    }

    @Override
    public String toString() {
        return "PourConfig{" + datasources.keySet() + "," + shardingTables.keySet() + ","
                + encrypt + ",broadcast:" + broadcastTables + ",binding:" + bindingGroups + "," + guards + "}";
    }
}
