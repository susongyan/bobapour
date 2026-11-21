package io.github.susongyan.bobapour.config.model;

import java.util.Objects;

/**
 * 加密列规则（D5 语义形态）。逻辑列在 SQL 中书写，物理存储列由 cipher/assisted 指定：
 * cipherColumn 必填（密文列）；plainColumn 可选（明文列——迁移期同存，查询可走明文）；
 * assistedQueryColumn 可选（辅助查询列——非确定性加密等值查询用）。encryptor 引用
 * {@link EncryptRuleConfig} 声明的算法名；deterministic 声明确定性加密（默认 false）——
 * ORDER BY/GROUP BY 落加密列仅支持确定性加密或辅助列（DESIGN D5）。
 */
public final class EncryptColumnConfig {

    private final String cipherColumn;
    private final String plainColumn;
    private final String assistedQueryColumn;
    private final String encryptor;
    private final boolean deterministic;

    public EncryptColumnConfig(final String cipherColumn, final String plainColumn,
            final String assistedQueryColumn, final String encryptor, final boolean deterministic) {
        this.cipherColumn = cipherColumn;
        this.plainColumn = plainColumn;
        this.assistedQueryColumn = assistedQueryColumn;
        this.encryptor = encryptor;
        this.deterministic = deterministic;
    }

    public String cipherColumn() {
        return cipherColumn;
    }

    /** 明文列；null = 无。 */
    public String plainColumn() {
        return plainColumn;
    }

    /** 辅助查询列；null = 无。 */
    public String assistedQueryColumn() {
        return assistedQueryColumn;
    }

    public String encryptor() {
        return encryptor;
    }

    public boolean deterministic() {
        return deterministic;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof EncryptColumnConfig)) {
            return false;
        }
        final EncryptColumnConfig other = (EncryptColumnConfig) obj;
        return Objects.equals(cipherColumn, other.cipherColumn) && Objects.equals(plainColumn, other.plainColumn)
                && Objects.equals(assistedQueryColumn, other.assistedQueryColumn)
                && Objects.equals(encryptor, other.encryptor) && deterministic == other.deterministic;
    }

    @Override
    public int hashCode() {
        return Objects.hash(cipherColumn, plainColumn, assistedQueryColumn, encryptor, deterministic);
    }

    @Override
    public String toString() {
        return "->" + cipherColumn + (null == plainColumn ? "" : "/plain:" + plainColumn)
                + (null == assistedQueryColumn ? "" : "/assisted:" + assistedQueryColumn)
                + " by " + encryptor + (deterministic ? " deterministic" : "");
    }
}
