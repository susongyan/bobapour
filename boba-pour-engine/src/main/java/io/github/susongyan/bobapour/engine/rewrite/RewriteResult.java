package io.github.susongyan.bobapour.engine.rewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 改写产物（T6）：fan-out 目标语句清单——每单元 =（数据源名， 改写后 SQL）。
 * 序与路由稳定序一致（库维在外表维在内；INSERT 拆行按组首现序），执行层按序派发即可。
 * 直通语句（无受管表）产出单一单元且 SQL 与原文逐字相等。
 */
public final class RewriteResult {

    /** 单条目标语句（值语义）。 */
    public static final class RoutedSql {

        private final String datasourceName;
        private final String sql;

        RoutedSql(final String datasourceName, final String sql) {
            this.datasourceName = Objects.requireNonNull(datasourceName, "datasourceName");
            this.sql = Objects.requireNonNull(sql, "sql");
        }

        public String datasourceName() {
            return datasourceName;
        }

        /** 改写后 SQL（token 装配产物；直通单元与原文逐字相等）。 */
        public String sql() {
            return sql;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RoutedSql)) {
                return false;
            }
            final RoutedSql other = (RoutedSql) obj;
            return datasourceName.equals(other.datasourceName) && sql.equals(other.sql);
        }

        @Override
        public int hashCode() {
            return Objects.hash(datasourceName, sql);
        }

        @Override
        public String toString() {
            return datasourceName + " <= " + sql;
        }
    }

    private final List<RoutedSql> units;

    RewriteResult(final List<RoutedSql> units) {
        this.units = Collections.unmodifiableList(new ArrayList<RoutedSql>(units));
    }

    /** fan-out 目标语句（稳定序）。 */
    public List<RoutedSql> units() {
        return units;
    }

    public boolean isEmpty() {
        return units.isEmpty();
    }

    @Override
    public String toString() {
        return units.toString();
    }
}
