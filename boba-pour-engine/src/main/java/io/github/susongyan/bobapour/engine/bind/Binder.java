package io.github.susongyan.bobapour.engine.bind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.susongyan.bobachop.ast.Assignment;
import io.github.susongyan.bobachop.ast.BetweenExpr;
import io.github.susongyan.bobachop.ast.BinaryExpr;
import io.github.susongyan.bobachop.ast.CaseExpr;
import io.github.susongyan.bobachop.ast.ColumnRef;
import io.github.susongyan.bobachop.ast.CompoundSelect;
import io.github.susongyan.bobachop.ast.DeleteStatement;
import io.github.susongyan.bobachop.ast.DerivedTable;
import io.github.susongyan.bobachop.ast.ExistsExpr;
import io.github.susongyan.bobachop.ast.Expr;
import io.github.susongyan.bobachop.ast.FunctionCall;
import io.github.susongyan.bobachop.ast.InExpr;
import io.github.susongyan.bobachop.ast.InsertStatement;
import io.github.susongyan.bobachop.ast.IsNullExpr;
import io.github.susongyan.bobachop.ast.Join;
import io.github.susongyan.bobachop.ast.LikeExpr;
import io.github.susongyan.bobachop.ast.Limit;
import io.github.susongyan.bobachop.ast.Literal;
import io.github.susongyan.bobachop.ast.OrderByItem;
import io.github.susongyan.bobachop.ast.SelectItem;
import io.github.susongyan.bobachop.ast.SubqueryExpr;
import io.github.susongyan.bobachop.ast.SelectQuery;
import io.github.susongyan.bobachop.ast.SelectSource;
import io.github.susongyan.bobachop.ast.SelectStatement;
import io.github.susongyan.bobachop.ast.Statement;
import io.github.susongyan.bobachop.ast.TableName;
import io.github.susongyan.bobachop.ast.TableRef;
import io.github.susongyan.bobachop.ast.UnaryExpr;
import io.github.susongyan.bobachop.ast.UpdateStatement;
import io.github.susongyan.bobachop.ast.ValuesSource;
import io.github.susongyan.bobachop.ast.WhenClause;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.config.metadata.TableSnapshot;

/**
 * 绑定器（T4 骨架）：AST × 元数据快照 → {@link BoundStatement}。纯内存零 IO。
 * 受管表发现——SELECT 的 FROM 引用树（Join 递归；派生表仅记形态不内绑，v0 诚实边界）、
 * 写语句目标表；未托管表不产生绑定（整句无受管表 = 直通）。键值提取见 {@link TableBinding}；
 * 形态扫描只投影语法事实，不做裁决（裁决点唯一在 MergePlan）。
 * v0 边界：子查询体内的表不参与绑定与路由（形态标志交由裁决层处置）；WHERE 合取式之外
 * （OR/JOIN ON）不提取键值——缺键按该维全路由，由矩阵/guards 显式裁决。
 */
public final class Binder {

    private static final Set<String> AGGREGATES = new HashSet<String>(
            Arrays.asList("COUNT", "SUM", "MIN", "MAX", "AVG"));

    private Binder() {
    }

    /** 绑定单条已解析语句；无受管表也产出（tables/broadcasts 为空，裁决层判直通）。 */
    public static BoundStatement bind(final Statement statement, final MetadataSnapshot snapshot) {
        final Map<String, TableSnapshot> managed = new LinkedHashMap<String, TableSnapshot>();
        final Set<String> broadcasts = new LinkedHashSet<String>();
        final Set<String> unmanaged = new LinkedHashSet<String>();
        final List<Expr> conditions = new ArrayList<Expr>();
        List<Map<String, Object>> insertRows = null;
        boolean insertSelect = false;
        if (statement instanceof SelectStatement || statement instanceof CompoundSelect) {
            boolean derivedManaged = false;
            for (final SelectQuery query : branches(statement)) {
                collectFrom(((SelectStatement) query).from(), managed, broadcasts, unmanaged, snapshot);
                derivedManaged |= managedInsideDerived(((SelectStatement) query).from(), snapshot);
                if (null != ((SelectStatement) query).where()) {
                    conditions.add(((SelectStatement) query).where());
                }
            }
            if (derivedManaged) {
                return new BoundStatement(statement,
                        new LinkedHashMap<String, TableBinding>(), broadcasts, unmanaged,
                        shapeOf(statement, false, false), true);
            }
        } else if (statement instanceof InsertStatement) {
            final InsertStatement insert = (InsertStatement) statement;
            addManaged(insert.table().name(), managed, broadcasts, unmanaged, snapshot);
            if (insert.source() instanceof SelectSource) {
                insertSelect = true;
            } else if (insert.source() instanceof ValuesSource && null != insert.columns()) {
                insertRows = extractRows(insert, (ValuesSource) insert.source());
            }
        } else if (statement instanceof UpdateStatement) {
            addManaged(((UpdateStatement) statement).table().name(), managed, broadcasts, unmanaged, snapshot);
            if (null != ((UpdateStatement) statement).where()) {
                conditions.add(((UpdateStatement) statement).where());
            }
        } else if (statement instanceof DeleteStatement) {
            addManaged(((DeleteStatement) statement).table().name(), managed, broadcasts, unmanaged, snapshot);
            if (null != ((DeleteStatement) statement).where()) {
                conditions.add(((DeleteStatement) statement).where());
            }
        }
        final Map<String, TableBinding> tables = new LinkedHashMap<String, TableBinding>();
        for (final TableSnapshot table : managed.values()) {
            tables.put(table.logicalName(), bindTable(table, conditions, insertRows));
        }
        final boolean write = statement instanceof InsertStatement || statement instanceof UpdateStatement
                || statement instanceof DeleteStatement;
        return new BoundStatement(statement, tables, broadcasts, unmanaged,
                shapeOf(statement, insertSelect, write), false);
    }

    private static TableBinding bindTable(final TableSnapshot table, final List<Expr> conditions,
            final List<Map<String, Object>> insertRows) {
        final String dbColumn = null == table.rule().databaseStrategy()
                ? null : table.rule().databaseStrategy().shardingColumn();
        final String tableColumn = null == table.rule().tableStrategy()
                ? null : table.rule().tableStrategy().shardingColumn();
        List<Object> dbValues = new ArrayList<Object>();
        List<Object> tableValues = new ArrayList<Object>();
        final List<Object> partitionValues = new ArrayList<Object>();
        if (null != insertRows) {
            for (final Map<String, Object> row : insertRows) {
                if (null != dbColumn && null != row.get(dbColumn.toLowerCase())) {
                    dbValues.add(row.get(dbColumn.toLowerCase()));
                }
                if (null != tableColumn && null != row.get(tableColumn.toLowerCase())) {
                    tableValues.add(row.get(tableColumn.toLowerCase()));
                }
                if (null != table.partition() && null != row.get(table.partition().key().toLowerCase())) {
                    partitionValues.add(row.get(table.partition().key().toLowerCase()));
                }
            }
        } else {
            if (null != dbColumn) {
                collectValues(conditions, dbColumn, dbValues);
            }
            if (null != tableColumn) {
                collectValues(conditions, tableColumn, tableValues);
            }
            if (null != table.partition()) {
                collectValues(conditions, table.partition().key(), partitionValues);
            }
        }
        return new TableBinding(table, dbValues, tableValues, partitionValues, insertRows);
    }

    /** 合取式提取：col = 字面量 / col IN (字面量...)。 */
    private static void collectValues(final List<Expr> conditions, final String column,
            final List<Object> out) {
        for (final Expr condition : conditions) {
            final List<Expr> conjuncts = new ArrayList<Expr>();
            splitConjuncts(condition, conjuncts);
            for (final Expr conjunct : conjuncts) {
                if (conjunct instanceof BinaryExpr) {
                    final BinaryExpr binary = (BinaryExpr) conjunct;
                    if ("=".equals(binary.operator().toUpperCase())
                            && binary.left() instanceof ColumnRef && binary.right() instanceof Literal) {
                        addIfColumnMatches((ColumnRef) binary.left(), (Literal) binary.right(), column, out);
                    }
                } else if (conjunct instanceof InExpr) {
                    final InExpr in = (InExpr) conjunct;
                    if (!in.not() && in.target() instanceof ColumnRef && null == in.query()
                            && null != in.values() && !in.values().isEmpty()) {
                        boolean allLiterals = true;
                        for (final Expr value : in.values()) {
                            if (!(value instanceof Literal)) {
                                allLiterals = false;
                                break;
                            }
                        }
                        if (allLiterals) {
                            for (final Expr value : in.values()) {
                                addIfColumnMatches((ColumnRef) in.target(), (Literal) value, column, out);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void addIfColumnMatches(final ColumnRef column, final Literal literal, final String target,
            final List<Object> out) {
        if (column.name().equalsIgnoreCase(target)) {
            final Object value = valueOf(literal);
            if (null != value) {
                out.add(value);
            }
        }
    }

    private static void splitConjuncts(final Expr expr, final List<Expr> out) {
        if (expr instanceof BinaryExpr && "AND".equals(((BinaryExpr) expr).operator().toUpperCase())) {
            splitConjuncts(((BinaryExpr) expr).left(), out);
            splitConjuncts(((BinaryExpr) expr).right(), out);
        } else {
            out.add(expr);
        }
    }

    private static List<Map<String, Object>> extractRows(final InsertStatement insert,
            final ValuesSource source) {
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(source.rows().size());
        for (final List<Expr> row : source.rows()) {
            final Map<String, Object> values = new HashMap<String, Object>();
            for (int i = 0; i < insert.columns().size() && i < row.size(); i++) {
                final Expr value = row.get(i);
                values.put(insert.columns().get(i).toLowerCase(),
                        value instanceof Literal ? valueOf((Literal) value) : null);
            }
            rows.add(values);
        }
        return rows;
    }

    private static List<SelectQuery> branches(final Statement statement) {
        final List<SelectQuery> queries = new ArrayList<SelectQuery>();
        if (statement instanceof CompoundSelect) {
            queries.addAll(((CompoundSelect) statement).queries());
        } else {
            queries.add((SelectQuery) statement);
        }
        return queries;
    }

    private static void collectFrom(final TableRef ref, final Map<String, TableSnapshot> managed,
            final Set<String> broadcasts, final Set<String> unmanaged, final MetadataSnapshot snapshot) {
        if (ref instanceof TableName) {
            addManaged(((TableName) ref).name(), managed, broadcasts, unmanaged, snapshot);
        } else if (ref instanceof Join) {
            collectFrom(((Join) ref).left(), managed, broadcasts, unmanaged, snapshot);
            collectFrom(((Join) ref).right(), managed, broadcasts, unmanaged, snapshot);
        }
    }

    /** 派生表内是否引用受管表——是则本句不可直通（受管表藏在派生表里，v0 不内绑）。 */
    private static boolean managedInsideDerived(final TableRef ref, final MetadataSnapshot snapshot) {
        if (ref instanceof DerivedTable) {
            return referencesManaged(((DerivedTable) ref).query(), snapshot);
        }
        if (ref instanceof Join) {
            return managedInsideDerived(((Join) ref).left(), snapshot)
                    || managedInsideDerived(((Join) ref).right(), snapshot);
        }
        return false;
    }

    private static boolean referencesManaged(final SelectQuery query, final MetadataSnapshot snapshot) {
        if (!(query instanceof SelectStatement)) {
            return false;
        }
        return referencesManaged(((SelectStatement) query).from(), snapshot);
    }

    private static boolean referencesManaged(final TableRef ref, final MetadataSnapshot snapshot) {
        if (ref instanceof TableName) {
            final String name = ((TableName) ref).name();
            return snapshot.isSharded(name) || snapshot.isBroadcast(name);
        }
        if (ref instanceof Join) {
            return referencesManaged(((Join) ref).left(), snapshot)
                    || referencesManaged(((Join) ref).right(), snapshot);
        }
        if (ref instanceof DerivedTable) {
            return referencesManaged(((DerivedTable) ref).query(), snapshot);
        }
        return false;
    }

    private static void addManaged(final String logical, final Map<String, TableSnapshot> managed,
            final Set<String> broadcasts, final Set<String> unmanaged, final MetadataSnapshot snapshot) {
        if (managed.containsKey(logical) || broadcasts.contains(logical) || unmanaged.contains(logical)) {
            return;
        }
        if (snapshot.isSharded(logical)) {
            managed.put(logical, snapshot.table(logical));
        } else if (snapshot.isBroadcast(logical)) {
            broadcasts.add(logical);
        } else {
            unmanaged.add(logical);
        }
    }

    private static Object valueOf(final Literal literal) {
        switch (literal.kind()) {
            case NUMBER:
                try {
                    return Long.valueOf(literal.text());
                } catch (final NumberFormatException e) {
                    return null;
                }
            case STRING:
                return unquote(literal.text());
            default:
                return null;
        }
    }

    /** 字符串字面量去引号：{@code 'a''b'} → {@code a'b}。 */
    static String unquote(final String text) {
        if (text.length() >= 2 && '\'' == text.charAt(0) && '\'' == text.charAt(text.length() - 1)) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        return text;
    }

    // ---------- 形态扫描（语法事实投影，不裁决） ----------

    private static BoundStatement.Shape shapeOf(final Statement statement, final boolean insertSelect,
            final boolean write) {
        boolean distinct = false;
        boolean orderBy = false;
        boolean limit = false;
        boolean limitLiteral = false;
        long limitWindow = -1L;
        boolean aggregate = false;
        boolean aggregateAvg = false;
        boolean groupBy = false;
        boolean groupByPlainColumns = false;
        final List<String> groupByColumns = new ArrayList<String>();
        final boolean union = statement instanceof CompoundSelect;
        boolean subquery = false;
        boolean derivedTable = false;
        boolean join = false;
        for (final SelectQuery query : readBranches(statement)) {
            final SelectStatement select = (SelectStatement) query;
            distinct |= select.distinct();
            orderBy |= null != select.orderBy();
            if (null != select.limit()) {
                limit = true;
                final long[] window = windowOf(select.limit());
                if (0L <= window[0]) {
                    limitLiteral = true;
                    limitWindow = window[0];
                }
            }
            if (null != select.groupBy()) {
                groupBy = true;
                groupByPlainColumns = true;
                for (final Expr expr : select.groupBy()) {
                    if (expr instanceof ColumnRef) {
                        groupByColumns.add(((ColumnRef) expr).name());
                    } else {
                        groupByPlainColumns = false;
                    }
                }
            }
            if (select.from() instanceof Join) {
                join = true;
                derivedTable |= hasDerived(select.from());
            } else if (select.from() instanceof DerivedTable) {
                derivedTable = true;
            }
            final ExprScanner scanner = new ExprScanner();
            for (final SelectItem item : select.items()) {
                scanner.scan(item.expr());
            }
            scanner.scan(select.where());
            scanner.scan(select.having());
            if (null != select.groupBy()) {
                for (final Expr expr : select.groupBy()) {
                    scanner.scan(expr);
                }
            }
            if (null != select.orderBy()) {
                for (final OrderByItem item : select.orderBy()) {
                    scanner.scan(item.expr());
                }
            }
            aggregate |= scanner.aggregate;
            aggregateAvg |= scanner.aggregateAvg;
            subquery |= scanner.subquery;
        }
        if (statement instanceof UpdateStatement) {
            final ExprScanner scanner = new ExprScanner();
            for (final Assignment assignment : ((UpdateStatement) statement).assignments()) {
                scanner.scan(assignment.value());
            }
            scanner.scan(((UpdateStatement) statement).where());
            subquery |= scanner.subquery;
        } else if (statement instanceof DeleteStatement) {
            final ExprScanner scanner = new ExprScanner();
            scanner.scan(((DeleteStatement) statement).where());
            subquery |= scanner.subquery;
        } else if (statement instanceof InsertStatement) {
            final InsertStatement insert = (InsertStatement) statement;
            final ExprScanner scanner = new ExprScanner();
            if (insert.source() instanceof SelectSource) {
                final SelectQuery query = ((SelectSource) insert.source()).query();
                if (query instanceof SelectStatement) {
                    final SelectStatement inner = (SelectStatement) query;
                    for (final SelectItem item : inner.items()) {
                        scanner.scan(item.expr());
                    }
                    scanner.scan(inner.where());
                }
            }
            if (null != insert.onDuplicateKey()) {
                for (final Assignment assignment : insert.onDuplicateKey()) {
                    scanner.scan(assignment.value());
                }
            }
            subquery |= scanner.subquery;
        }
        return new BoundStatement.Shape(distinct, orderBy, limit, limitLiteral, limitWindow, aggregate,
                aggregateAvg, groupBy, groupByPlainColumns, groupByColumns, union, subquery, derivedTable,
                join, insertSelect, write);
    }

    /** 只读"可当 SELECT 扫形态"的分支：SELECT 主干 / UNION 各支 / INSERT-SELECT 源。 */
    private static List<SelectQuery> readBranches(final Statement statement) {
        final List<SelectQuery> queries = new ArrayList<SelectQuery>();
        if (statement instanceof CompoundSelect) {
            queries.addAll(((CompoundSelect) statement).queries());
        } else if (statement instanceof SelectStatement) {
            queries.add((SelectStatement) statement);
        } else if (statement instanceof InsertStatement
                && ((InsertStatement) statement).source() instanceof SelectSource) {
            queries.add(((SelectSource) ((InsertStatement) statement).source()).query());
        }
        return queries;
    }

    private static boolean hasDerived(final TableRef ref) {
        if (ref instanceof DerivedTable) {
            return true;
        }
        if (ref instanceof Join) {
            return hasDerived(((Join) ref).left()) || hasDerived(((Join) ref).right());
        }
        return false;
    }

    /**
     * LIMIT 窗口求和：offset+rowCount；任一部分为参数标记/非数值 → 返回 [0] 位为 -1
     * （动态窗口，运行期校验）。
     */
    private static long[] windowOf(final Limit limit) {
        long window = 0L;
        if (null != limit.rowCount()) {
            if (!(limit.rowCount() instanceof Literal)) {
                return new long[]{-1L};
            }
            window += longOf((Literal) limit.rowCount());
        }
        if (null != limit.offset()) {
            if (!(limit.offset() instanceof Literal)) {
                return new long[]{-1L};
            }
            window += longOf((Literal) limit.offset());
        }
        return new long[]{window};
    }

    private static long longOf(final Literal literal) {
        try {
            return Long.parseLong(literal.text());
        } catch (final NumberFormatException e) {
            return 0L;
        }
    }

    /** 表达式扫描器：聚合/DISTINCT 聚合/子查询存在性（形态标志）。 */
    private static final class ExprScanner {

        private boolean aggregate;
        private boolean aggregateAvg;
        private boolean subquery;

        void scan(final Expr expr) {
            if (null == expr) {
                return;
            }
            if (expr instanceof BinaryExpr) {
                scan(((BinaryExpr) expr).left());
                scan(((BinaryExpr) expr).right());
            } else if (expr instanceof UnaryExpr) {
                scan(((UnaryExpr) expr).operand());
            } else if (expr instanceof FunctionCall) {
                final FunctionCall call = (FunctionCall) expr;
                final String name = call.name().toUpperCase();
                if (AGGREGATES.contains(name)) {
                    aggregate = true;
                    aggregateAvg |= "AVG".equals(name);
                }
                for (final Expr argument : call.arguments()) {
                    scan(argument);
                }
            } else if (expr instanceof CaseExpr) {
                final CaseExpr caseExpr = (CaseExpr) expr;
                scan(caseExpr.operand());
                scan(caseExpr.elseResult());
                for (final WhenClause when : caseExpr.whens()) {
                    scan(when.condition());
                    scan(when.result());
                }
            } else if (expr instanceof InExpr) {
                final InExpr in = (InExpr) expr;
                scan(in.target());
                if (null != in.values()) {
                    for (final Expr value : in.values()) {
                        scan(value);
                    }
                }
                if (null != in.query()) {
                    subquery = true;
                    scanQuery(in.query());
                }
            } else if (expr instanceof BetweenExpr) {
                scan(((BetweenExpr) expr).target());
                scan(((BetweenExpr) expr).low());
                scan(((BetweenExpr) expr).high());
            } else if (expr instanceof LikeExpr) {
                scan(((LikeExpr) expr).target());
                scan(((LikeExpr) expr).pattern());
            } else if (expr instanceof IsNullExpr) {
                scan(((IsNullExpr) expr).target());
            } else if (expr instanceof SubqueryExpr) {
                subquery = true;
                scanQuery(((SubqueryExpr) expr).query());
            } else if (expr instanceof ExistsExpr) {
                subquery = true;
                scanQuery(((ExistsExpr) expr).query());
            }
        }

        private void scanQuery(final SelectQuery query) {
            if (!(query instanceof SelectStatement)) {
                return;
            }
            final SelectStatement select = (SelectStatement) query;
            for (final SelectItem item : select.items()) {
                scan(item.expr());
            }
            scan(select.where());
        }
    }
}
