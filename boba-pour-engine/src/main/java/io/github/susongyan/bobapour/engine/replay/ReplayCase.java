package io.github.susongyan.bobapour.engine.replay;

import java.util.Objects;

/**
 * 回放用例：标识 + SQL 文本（生产实例语料永不出内网——本类只承载，不区分来源；
 * 仓内通用语料见 corpus/，DESIGN §8 语料切割线）。
 */
public final class ReplayCase {

    private final String id;
    private final String sql;

    public ReplayCase(final String id, final String sql) {
        this.id = Objects.requireNonNull(id, "id");
        this.sql = Objects.requireNonNull(sql, "sql");
    }

    public String id() {
        return id;
    }

    public String sql() {
        return sql;
    }
}
