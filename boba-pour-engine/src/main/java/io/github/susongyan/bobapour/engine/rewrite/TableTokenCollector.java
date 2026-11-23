package io.github.susongyan.bobapour.engine.rewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.github.susongyan.bobachop.ast.Assignment;
import io.github.susongyan.bobachop.ast.BetweenExpr;
import io.github.susongyan.bobachop.ast.BinaryExpr;
import io.github.susongyan.bobachop.ast.CaseExpr;
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
import io.github.susongyan.bobachop.ast.OrderByItem;
import io.github.susongyan.bobachop.ast.SelectItem;
import io.github.susongyan.bobachop.ast.SelectQuery;
import io.github.susongyan.bobachop.ast.SelectSource;
import io.github.susongyan.bobachop.ast.SelectStatement;
import io.github.susongyan.bobachop.ast.Statement;
import io.github.susongyan.bobachop.ast.SubqueryExpr;
import io.github.susongyan.bobachop.ast.TableName;
import io.github.susongyan.bobachop.ast.TableRef;
import io.github.susongyan.bobachop.ast.UnaryExpr;
import io.github.susongyan.bobachop.ast.UpdateStatement;
import io.github.susongyan.bobachop.ast.ValuesSource;
import io.github.susongyan.bobachop.ast.WhenClause;

/**
 * 表名 token 采集器（T6）：整句收集全部 {@link TableName} 出现（含表达式层子查询的 FROM 树），
 * 按文档序（start 升序）输出——改写层据此做偏移切片。与 bind 层发现面互补：bind 只发现
 * 顶层 FROM 的受管表（裁决用），本采集器是<b>文本改写面</b>——派生表内部不进（v0 派生表内
 * 受管表已被裁决层拒绝、未托管表不改写，内文一律原样）。单分片子查询语句因此能整体改写。
 */
final class TableTokenCollector {

    private TableTokenCollector() {
    }

    /** 按文档序返回语句内全部表名出现（不含派生表内部）。 */
    static List<TableName> collect(final Statement statement) {
        final List<TableName> out = new ArrayList<TableName>(4);
        if (statement instanceof SelectStatement || statement instanceof CompoundSelect) {
            for (final SelectQuery query : branches(statement)) {
                collectQuery(query, out);
            }
        } else if (statement instanceof InsertStatement) {
            final InsertStatement insert = (InsertStatement) statement;
            out.add(insert.table());
            if (insert.source() instanceof SelectSource) {
                collectQuery(((SelectSource) insert.source()).query(), out);
            } else if (insert.source() instanceof ValuesSource) {
                for (final List<Expr> row : ((ValuesSource) insert.source()).rows()) {
                    for (final Expr value : row) {
                        collectExpr(value, out);
                    }
                }
            }
            collectAssignments(insert.onDuplicateKey(), out);
            collectItems(insert.returning(), out);
        } else if (statement instanceof UpdateStatement) {
            final UpdateStatement update = (UpdateStatement) statement;
            out.add(update.table());
            collectAssignments(update.assignments(), out);
            collectExpr(update.where(), out);
            collectItems(update.returning(), out);
        } else if (statement instanceof DeleteStatement) {
            final DeleteStatement delete = (DeleteStatement) statement;
            out.add(delete.table());
            collectExpr(delete.where(), out);
            collectItems(delete.returning(), out);
        }
        Collections.sort(out, new Comparator<TableName>() {
            @Override
            public int compare(final TableName a, final TableName b) {
                return Integer.compare(a.start(), b.start());
            }
        });
        return out;
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

    private static void collectQuery(final SelectQuery query, final List<TableName> out) {
        if (!(query instanceof SelectStatement)) {
            return;
        }
        final SelectStatement select = (SelectStatement) query;
        collectFrom(select.from(), out);
        collectItems(select.items(), out);
        collectExpr(select.where(), out);
        if (null != select.groupBy()) {
            for (final Expr expr : select.groupBy()) {
                collectExpr(expr, out);
            }
        }
        collectExpr(select.having(), out);
        if (null != select.orderBy()) {
            for (final OrderByItem item : select.orderBy()) {
                collectExpr(item.expr(), out);
            }
        }
    }

    /** FROM 树：TableName 采集；Join 递归 + ON 条件内子查询；派生表内部不进（见类注）。 */
    private static void collectFrom(final TableRef ref, final List<TableName> out) {
        if (ref instanceof TableName) {
            out.add((TableName) ref);
        } else if (ref instanceof Join) {
            final Join join = (Join) ref;
            collectFrom(join.left(), out);
            collectFrom(join.right(), out);
            collectExpr(join.condition(), out);
        } else if (ref instanceof DerivedTable) {
            // 派生表内部不采集（v0 边界）：受管表在派生表内已被裁决层拒绝，未托管表不改写。
        }
    }

    private static void collectAssignments(final List<Assignment> assignments, final List<TableName> out) {
        if (null == assignments) {
            return;
        }
        for (final Assignment assignment : assignments) {
            collectExpr(assignment.value(), out);
        }
    }

    private static void collectItems(final List<SelectItem> items, final List<TableName> out) {
        if (null == items) {
            return;
        }
        for (final SelectItem item : items) {
            collectExpr(item.expr(), out);
        }
    }

    /** 表达式树：只追子查询（子查询 FROM 内的表名同样要改写），其余节点不含表名。 */
    private static void collectExpr(final Expr expr, final List<TableName> out) {
        if (null == expr) {
            return;
        }
        if (expr instanceof BinaryExpr) {
            collectExpr(((BinaryExpr) expr).left(), out);
            collectExpr(((BinaryExpr) expr).right(), out);
        } else if (expr instanceof UnaryExpr) {
            collectExpr(((UnaryExpr) expr).operand(), out);
        } else if (expr instanceof InExpr) {
            final InExpr in = (InExpr) expr;
            collectExpr(in.target(), out);
            if (null != in.values()) {
                for (final Expr value : in.values()) {
                    collectExpr(value, out);
                }
            }
            if (null != in.query()) {
                collectQuery(in.query(), out);
            }
        } else if (expr instanceof SubqueryExpr) {
            collectQuery(((SubqueryExpr) expr).query(), out);
        } else if (expr instanceof ExistsExpr) {
            collectQuery(((ExistsExpr) expr).query(), out);
        } else if (expr instanceof BetweenExpr) {
            final BetweenExpr between = (BetweenExpr) expr;
            collectExpr(between.target(), out);
            collectExpr(between.low(), out);
            collectExpr(between.high(), out);
        } else if (expr instanceof LikeExpr) {
            final LikeExpr like = (LikeExpr) expr;
            collectExpr(like.target(), out);
            collectExpr(like.pattern(), out);
            collectExpr(like.escape(), out);
        } else if (expr instanceof IsNullExpr) {
            collectExpr(((IsNullExpr) expr).target(), out);
        } else if (expr instanceof FunctionCall) {
            for (final Expr argument : ((FunctionCall) expr).arguments()) {
                collectExpr(argument, out);
            }
        } else if (expr instanceof CaseExpr) {
            final CaseExpr caseExpr = (CaseExpr) expr;
            collectExpr(caseExpr.operand(), out);
            for (final WhenClause when : caseExpr.whens()) {
                collectExpr(when.condition(), out);
                collectExpr(when.result(), out);
            }
            collectExpr(caseExpr.elseResult(), out);
        }
    }
}
