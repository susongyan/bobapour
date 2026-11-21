package io.github.susongyan.bobapour.config.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 加密规则：算法声明表（name → {@link AlgorithmConfig}，type 如 AES/SM4/CHAR_DIGEST…）
 * + 表·列两级规则（表名 → 列名 → {@link EncryptColumnConfig}）。加密表不必是分片表
 * （可独立于 sharding 存在）；列规则引用的 encryptor 由加载校验保证存在。
 */
public final class EncryptRuleConfig {

    private final Map<String, AlgorithmConfig> encryptors;
    private final Map<String, Map<String, EncryptColumnConfig>> tables;

    public EncryptRuleConfig(final Map<String, AlgorithmConfig> encryptors,
            final Map<String, Map<String, EncryptColumnConfig>> tables) {
        this.encryptors = Collections.unmodifiableMap(new LinkedHashMap<String, AlgorithmConfig>(encryptors));
        final Map<String, Map<String, EncryptColumnConfig>> copy = new LinkedHashMap<String, Map<String, EncryptColumnConfig>>();
        for (final Map.Entry<String, Map<String, EncryptColumnConfig>> entry : tables.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<String, EncryptColumnConfig>(entry.getValue())));
        }
        this.tables = Collections.unmodifiableMap(copy);
    }

    public Map<String, AlgorithmConfig> encryptors() {
        return encryptors;
    }

    public Map<String, Map<String, EncryptColumnConfig>> tables() {
        return tables;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof EncryptRuleConfig)) {
            return false;
        }
        final EncryptRuleConfig other = (EncryptRuleConfig) obj;
        return Objects.equals(encryptors, other.encryptors) && Objects.equals(tables, other.tables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(encryptors, tables);
    }

    @Override
    public String toString() {
        return "encryptors" + encryptors.keySet() + " tables" + tables.keySet();
    }
}
