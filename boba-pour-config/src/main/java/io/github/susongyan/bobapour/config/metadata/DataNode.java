package io.github.susongyan.bobapour.config.metadata;

import java.util.Objects;

/**
 * 物理数据节点：数据源名 + 物理表名。路由与执行的最小目标单元，值语义（equals/hashCode）。
 */
public final class DataNode {

    private final String datasourceName;
    private final String tableName;

    public DataNode(final String datasourceName, final String tableName) {
        this.datasourceName = Objects.requireNonNull(datasourceName, "datasourceName");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    public String datasourceName() {
        return datasourceName;
    }

    public String tableName() {
        return tableName;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DataNode)) {
            return false;
        }
        final DataNode other = (DataNode) obj;
        return datasourceName.equals(other.datasourceName) && tableName.equals(other.tableName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datasourceName, tableName);
    }

    @Override
    public String toString() {
        return datasourceName + "." + tableName;
    }
}
