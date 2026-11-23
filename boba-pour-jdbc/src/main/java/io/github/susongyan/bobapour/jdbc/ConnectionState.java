package io.github.susongyan.bobapour.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 借出状态基线（D12 契约①）：首次借出时从底层物理连接捕获的"满状态"快照，此后每次借出
 * 显式重置为该基线——应用看到的连接状态与池的归还重置策略（autocommit/isolation/catalog 等）
 * 解耦，逐次借出确定一致。捕获点 = 池首次借出时交付的状态（池有配置默认则即池默认，无池则即驱动默认）。
 * networkTimeout 不入契约：setNetworkTimeout 需 Executor 且语义属连接池/部署方域，由池管理。
 */
final class ConnectionState {

    private final boolean autoCommit;
    private final int transactionIsolation;
    private final boolean readOnly;
    private final String catalog;
    private final String schema;
    private final int holdability;

    private ConnectionState(final boolean autoCommit, final int transactionIsolation,
            final boolean readOnly, final String catalog, final String schema, final int holdability) {
        this.autoCommit = autoCommit;
        this.transactionIsolation = transactionIsolation;
        this.readOnly = readOnly;
        this.catalog = catalog;
        this.schema = schema;
        this.holdability = holdability;
    }

    /** 从底层连接捕获当前状态为基线（仅首次借出调用一次）。 */
    static ConnectionState capture(final Connection raw) throws SQLException {
        return new ConnectionState(raw.getAutoCommit(), raw.getTransactionIsolation(),
                raw.isReadOnly(), raw.getCatalog(), raw.getSchema(), raw.getHoldability());
    }

    /** 借出设满：逐字段显式重置为基线（catalog/schema 为 null 的驱动按其语义跳过）。 */
    void apply(final Connection raw) throws SQLException {
        raw.setAutoCommit(autoCommit);
        raw.setTransactionIsolation(transactionIsolation);
        raw.setReadOnly(readOnly);
        if (null != catalog) {
            raw.setCatalog(catalog);
        }
        if (null != schema) {
            raw.setSchema(schema);
        }
        raw.setHoldability(holdability);
    }

    boolean autoCommit() {
        return autoCommit;
    }

    int transactionIsolation() {
        return transactionIsolation;
    }

    boolean readOnly() {
        return readOnly;
    }

    String catalog() {
        return catalog;
    }

    String schema() {
        return schema;
    }

    int holdability() {
        return holdability;
    }
}
