package io.github.susongyan.bobapour.engine.bind;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.susongyan.bobachop.ast.Statement;

/**
 * 绑定产物（T4 骨架）：语句 + 分片表绑定 + 广播表名单 + 形态事实（Shape）。形态只存语法事实的
 * 投影（有无 ORDER BY/LIMIT/聚合/DISTINCT/JOIN/UNION/子查询/派生表），不预判裁决——
 * 矩阵裁决唯一发生在 MergePlan（DESIGN §5.2"裁决点唯一"）。
 */
public final class BoundStatement {

    /** 形态事实（裁决输入，非裁决结论）。 */
    public static final class Shape {

        /** SELECT DISTINCT（含聚合内 DISTINCT）。 */
        public final boolean distinct;
        /** 带 ORDER BY。 */
        public final boolean orderBy;
        /** 带 LIMIT/OFFSET。 */
        public final boolean limit;
        /** LIMIT 窗口为字面量（可静态校验 maxMergeWindowRows；参数标记为 false → 运行期校验）。 */
        public final boolean limitLiteral;
        /** LIMIT offset+rowCount 总量（limitLiteral 时有效，否则 -1）。 */
        public final long limitWindow;
        /** 投影含聚合函数（COUNT/SUM/MIN/MAX/AVG，无 GROUP BY 的重组装候选）。 */
        public final boolean aggregate;
        /** 含 AVG（v1.1 拆 SUM+COUNT，此前拒绝）。 */
        public final boolean aggregateAvg;
        /** 带 GROUP BY。 */
        public final boolean groupBy;
        /** GROUP BY 全部为裸列名（可判分片键归属；含表达式则 false）。 */
        public final boolean groupByPlainColumns;
        /** GROUP BY 裸列名清单（groupByPlainColumns 时有效）。 */
        public final List<String> groupByColumns;
        /** UNION 复合。 */
        public final boolean union;
        /** 表达式层子查询（标量子查询/EXISTS/IN(SELECT)）。 */
        public final boolean subquery;
        /** FROM 含派生表。 */
        public final boolean derivedTable;
        /** JOIN（≥2 表引用）。 */
        public final boolean join;
        /** INSERT ... SELECT。 */
        public final boolean insertSelect;
        /** 写语句（INSERT/UPDATE/DELETE）。 */
        public final boolean write;

        Shape(final boolean distinct, final boolean orderBy, final boolean limit, final boolean limitLiteral,
                final long limitWindow, final boolean aggregate, final boolean aggregateAvg, final boolean groupBy,
                final boolean groupByPlainColumns, final List<String> groupByColumns, final boolean union,
                final boolean subquery, final boolean derivedTable, final boolean join,
                final boolean insertSelect, final boolean write) {
            this.distinct = distinct;
            this.orderBy = orderBy;
            this.limit = limit;
            this.limitLiteral = limitLiteral;
            this.limitWindow = limitWindow;
            this.aggregate = aggregate;
            this.aggregateAvg = aggregateAvg;
            this.groupBy = groupBy;
            this.groupByPlainColumns = groupByPlainColumns;
            this.groupByColumns = groupByColumns;
            this.union = union;
            this.subquery = subquery;
            this.derivedTable = derivedTable;
            this.join = join;
            this.insertSelect = insertSelect;
            this.write = write;
        }
    }

    private final Statement statement;
    private final Map<String, TableBinding> tables;
    private final Set<String> broadcastTables;
    private final Set<String> unmanagedTables;
    private final Shape shape;
    private final boolean derivedManaged;

    BoundStatement(final Statement statement, final Map<String, TableBinding> tables,
            final Set<String> broadcastTables, final Set<String> unmanagedTables, final Shape shape,
            final boolean derivedManaged) {
        this.statement = statement;
        this.tables = Collections.unmodifiableMap(tables);
        this.broadcastTables = Collections.unmodifiableSet(broadcastTables);
        this.unmanagedTables = Collections.unmodifiableSet(unmanagedTables);
        this.shape = shape;
        this.derivedManaged = derivedManaged;
    }

    public Statement statement() {
        return statement;
    }

    /** 分片表绑定（未托管表不出现——整句无分片无广播 = 直通）。 */
    public Map<String, TableBinding> tables() {
        return tables;
    }

    /** 广播表名单（全副本表，无策略无绑定，路由 = 全数据源）。 */
    public Set<String> broadcastTables() {
        return broadcastTables;
    }

    /** 未托管表名单（不在 sharding/broadcast 任何规则节）——与受管表混用即配置缺口，回放诊断用。 */
    public Set<String> unmanagedTables() {
        return unmanagedTables;
    }

    public Shape shape() {
        return shape;
    }

    /** 派生表内引用了受管表（本句不可直通，v0 不内绑派生表——显式拒绝）。 */
    public boolean derivedManaged() {
        return derivedManaged;
    }
}
