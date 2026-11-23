package io.github.susongyan.bobapour.engine.rewrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.susongyan.bobachop.ast.DeleteStatement;
import io.github.susongyan.bobachop.ast.Expr;
import io.github.susongyan.bobachop.ast.InsertStatement;
import io.github.susongyan.bobachop.ast.Statement;
import io.github.susongyan.bobachop.ast.TableName;
import io.github.susongyan.bobachop.ast.UpdateStatement;
import io.github.susongyan.bobachop.ast.ValuesSource;
import io.github.susongyan.bobachop.dialect.DialectProfile;
import io.github.susongyan.bobapour.config.metadata.DataNode;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.config.metadata.TableSnapshot;
import io.github.susongyan.bobapour.engine.bind.BoundStatement;
import io.github.susongyan.bobapour.engine.merge.MergePlan;
import io.github.susongyan.bobapour.engine.route.RouteResult;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * token 装配式改写器（T6）：消费 BobaChop 偏移契约（内核 D5，闭区间字符偏移）——所有改写 =
 * 按偏移切片原文 + 替换 token 装配，不做字符串全局替换。输入为决策链放行产物
 * （bind → route → MergePlan 全绿后调用），输出 fan-out 目标语句（数据源， 改写后 SQL）。
 *
 * <p>改写面（诚实边界）：</p>
 * <ul>
 * <li>受管分片表名 → 物理表名（库限定前缀消解——物理库由路由选定；别名/AS 原样保留）；</li>
 * <li>INSERT 拆行（D9）：行按目标节点分组重装（行文本原样切片，组内序保持语句序；
 * 单组含全部行时不切片，整句原文装配）；</li>
 * <li>D13 分区定位改写（仅写路径，声明分区的表才有此面）：MySQL 追加 {@code PARTITION (p)}
 * 选择子（落表名后、别名前）；PG 以声明分区名为子表全名替换（表维分片组合时子表名不可静态
 * 推导 → 回退）；Oracle 无模板不改写。INSERT 逐行定位但<b>组级统一裁决</b>：全部行命中同一
 * 分区才改写，跨分区/miss/insert-select/缺列 → 整组回退父表（D13 原文语义）。</li>
 * </ul>
 *
 * <p>语句分组：单表 = 逐节点一语句（路由稳定序）；绑定组多表（JOIN）按绑定前提推导对齐——
 * 分片键等值 ⇒ 同库同表位。已定位表（该库单节点）定表位并传播给未定位表（表维全量者）；
 * 全表未定位则按表位逐一扫描。表位冲突或分组空集 = 绑定前提破坏，执行前显式拒绝。
 * 逻辑表名大小写敏感，与 bind 层一致。广播表与未托管表名不改写（广播物理名即逻辑名）。</p>
 */
public final class Rewriter {

    private Rewriter() {
    }

    /** 改写一条已裁决放行的语句（决策链四产物 + 方言 + 快照）。 */
    public static RewriteResult rewrite(final String sql, final BoundStatement bound, final RouteResult route,
            final MergePlan plan, final DialectProfile profile, final MetadataSnapshot snapshot) {
        final List<TableName> tokens = TableTokenCollector.collect(bound.statement());
        for (final TableName token : tokens) {
            if (snapshot.isSharded(token.name()) && !bound.tables().containsKey(token.name())) {
                throw new UnsupportedQueryException("表", -1,
                        "受管表出现在绑定发现面之外（VALUES 行内子查询等，v0 不改写也不静默透传）",
                        "表: " + token.name());
            }
        }
        final List<RewriteResult.RoutedSql> units = new ArrayList<RewriteResult.RoutedSql>();
        if (MergePlan.Kind.BROADCAST_WRITE == plan.kind()) {
            for (final DataNode node : plan.targets()) {
                units.add(new RewriteResult.RoutedSql(node.datasourceName(), sql));
            }
            return new RewriteResult(units);
        }
        if (bound.tables().isEmpty()) {
            // 直通/广播读：无受管表，原文不改；广播读取样首数据源（任一副本语义，确定性）。
            units.add(new RewriteResult.RoutedSql(firstDatasource(snapshot), sql));
            return new RewriteResult(units);
        }
        final TableName writeTarget = writeTargetOf(bound.statement());
        if (null != route.insertRowTargets()) {
            return new RewriteResult(splitInsert(sql, bound, route, tokens, writeTarget, profile));
        }
        final String partition = route.writePartition();
        for (final Group group : statementGroups(bound, route)) {
            units.add(new RewriteResult.RoutedSql(group.datasource,
                    assemble(sql, tokens, group.physical, writeTarget, partition, profile, bound, 0, sql.length())));
        }
        return new RewriteResult(units);
    }

    // ---------- 语句分组 ----------

    /** 一条物理语句的分组：数据源 + 逻辑表→物理名。 */
    private static final class Group {

        final String datasource;
        final Map<String, String> physical;

        Group(final String datasource, final Map<String, String> physical) {
            this.datasource = datasource;
            this.physical = physical;
        }
    }

    private static List<Group> statementGroups(final BoundStatement bound, final RouteResult route) {
        final List<String> logicals = new ArrayList<String>(bound.tables().keySet());
        if (1 == logicals.size()) {
            final String logical = logicals.get(0);
            final List<Group> groups = new ArrayList<Group>();
            for (final DataNode node : route.tableTargets().get(logical)) {
                final Map<String, String> physical = new LinkedHashMap<String, String>();
                physical.put(logical, node.tableName());
                groups.add(new Group(node.datasourceName(), physical));
            }
            return groups;
        }
        return bindingGroups(bound, route, logicals);
    }

    /**
     * 绑定组多表对齐推导（v0）：同库维布局为快照装配期已校验前提；表位对齐由分片键等值保证——
     * 已定位表（该库单节点）定表位并传播给未定位表（该库多节点=表维全量）；全表未定位则按
     * 表位逐一扫描（每表位一语句）。表位冲突或分组空集 = 绑定前提破坏，显式拒绝。
     */
    private static List<Group> bindingGroups(final BoundStatement bound, final RouteResult route,
            final List<String> logicals) {
        final Map<String, List<String>> layouts = new LinkedHashMap<String, List<String>>();
        final int width = bound.tables().get(logicals.get(0)).table().dataNodes().tableNames().size();
        for (final String logical : logicals) {
            final List<String> layout = bound.tables().get(logical).table().dataNodes().tableNames();
            if (layout.size() != width) {
                throw new IllegalStateException("绑定组表维布局不一致（配置不变量破坏）: " + logical
                        + " 表位数=" + layout.size() + "，组内=" + width);
            }
            layouts.put(logical, layout);
        }
        final Set<String> datasources = new LinkedHashSet<String>();
        for (final List<DataNode> nodes : route.tableTargets().values()) {
            for (final DataNode node : nodes) {
                datasources.add(node.datasourceName());
            }
        }
        final List<Group> groups = new ArrayList<Group>();
        for (final String ds : datasources) {
            Integer resolvedIndex = null;
            boolean presentInAll = true;
            for (final String logical : logicals) {
                final List<DataNode> mine = nodesIn(route.tableTargets().get(logical), ds);
                if (mine.isEmpty()) {
                    presentInAll = false;
                    break;
                }
                if (1 == mine.size()) {
                    final int index = layouts.get(logical).indexOf(mine.get(0).tableName());
                    if (null != resolvedIndex && resolvedIndex.intValue() != index) {
                        throw new UnsupportedQueryException("JOIN", -1,
                                "绑定组分片键值冲突（同库定位到不同表位，等值前提破坏）",
                                "数据源: " + ds + "，表: " + logical);
                    }
                    resolvedIndex = Integer.valueOf(index);
                }
            }
            if (!presentInAll) {
                continue;
            }
            final List<Integer> indexes = new ArrayList<Integer>();
            if (null != resolvedIndex) {
                indexes.add(resolvedIndex);
            } else {
                for (int i = 0; i < width; i++) {
                    indexes.add(Integer.valueOf(i));
                }
            }
            for (final Integer index : indexes) {
                final Map<String, String> physical = new LinkedHashMap<String, String>();
                for (final String logical : logicals) {
                    physical.put(logical, layouts.get(logical).get(index.intValue()));
                }
                groups.add(new Group(ds, physical));
            }
        }
        if (groups.isEmpty()) {
            throw new UnsupportedQueryException("JOIN", -1,
                    "绑定组各表路由数据源不相交（分片键值无交集，等值连接不可满足）", "");
        }
        return groups;
    }

    private static List<DataNode> nodesIn(final List<DataNode> nodes, final String datasource) {
        final List<DataNode> out = new ArrayList<DataNode>(2);
        for (final DataNode node : nodes) {
            if (node.datasourceName().equals(datasource)) {
                out.add(node);
            }
        }
        return out;
    }

    // ---------- INSERT 拆行（D9） ----------

    /** 行按目标节点分组（首现序），行文本原样切片重装；单组含全部行时整句装配不切片。 */
    private static List<RewriteResult.RoutedSql> splitInsert(final String sql, final BoundStatement bound,
            final RouteResult route, final List<TableName> tokens, final TableName writeTarget,
            final DialectProfile profile) {
        final List<List<Expr>> rows = ((ValuesSource) ((InsertStatement) bound.statement()).source()).rows();
        // 行区 = 首行开括号到末行闭括号（VALUES 形态语法不变量；括号外文本随组原样）
        final int rowOpen = openParenBackward(sql, rows.get(0).get(0).start());
        final int rowClose = closeParenForward(sql,
                rows.get(rows.size() - 1).get(rows.get(rows.size() - 1).size() - 1).stop());
        for (final TableName token : tokens) {
            if (null != bound.tables().get(token.name())
                    && token.start() <= rowClose && token.stop() >= rowOpen) {
                throw new UnsupportedQueryException("INSERT", -1,
                        "VALUES 行内引用受管表（v0 不支持拆行改写）", "表: " + token.name());
            }
        }
        final Map<DataNode, List<Integer>> byNode = new LinkedHashMap<DataNode, List<Integer>>();
        for (int i = 0; i < route.insertRowTargets().size(); i++) {
            final DataNode node = route.insertRowTargets().get(i).get(0);
            List<Integer> mine = byNode.get(node);
            if (null == mine) {
                mine = new ArrayList<Integer>();
                byNode.put(node, mine);
            }
            mine.add(Integer.valueOf(i));
        }
        final String logical = bound.tables().keySet().iterator().next();
        final String partition = insertPartitionOf(route);
        final List<RewriteResult.RoutedSql> units = new ArrayList<RewriteResult.RoutedSql>();
        for (final Map.Entry<DataNode, List<Integer>> entry : byNode.entrySet()) {
            final DataNode node = entry.getKey();
            final List<Integer> mine = entry.getValue();
            final Map<String, String> physical = new LinkedHashMap<String, String>();
            physical.put(logical, node.tableName());
            if (mine.size() == rows.size()) {
                units.add(new RewriteResult.RoutedSql(node.datasourceName(),
                        assemble(sql, tokens, physical, writeTarget, partition, profile, bound, 0, sql.length())));
                continue;
            }
            final StringBuilder out = new StringBuilder(sql.length());
            out.append(assemble(sql, tokens, physical, writeTarget, partition, profile, bound, 0, rowOpen));
            for (int i = 0; i < mine.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                final List<Expr> row = rows.get(mine.get(i).intValue());
                out.append('(').append(sql, row.get(0).start(), row.get(row.size() - 1).stop() + 1).append(')');
            }
            out.append(assemble(sql, tokens, physical, writeTarget, partition, profile, bound, rowClose + 1, sql.length()));
            units.add(new RewriteResult.RoutedSql(node.datasourceName(), out.toString()));
        }
        return units;
    }

    /** 首行开括号位（首表达式起点回溯跳空白；无括号 = VALUES 形态不变量破坏，fail-loud）。 */
    private static int openParenBackward(final String sql, final int firstExprStart) {
        int i = firstExprStart - 1;
        while (i >= 0 && Character.isWhitespace(sql.charAt(i))) {
            i--;
        }
        if (i < 0 || '(' != sql.charAt(i)) {
            throw new IllegalStateException("INSERT 行区开括号定位失败（偏移契约被破坏）: " + firstExprStart);
        }
        return i;
    }

    /** 末行闭括号位（末表达式止点前向跳空白）。 */
    private static int closeParenForward(final String sql, final int lastExprStop) {
        int i = lastExprStop + 1;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (i >= sql.length() || ')' != sql.charAt(i)) {
            throw new IllegalStateException("INSERT 行区闭括号定位失败（偏移契约被破坏）: " + lastExprStop);
        }
        return i;
    }

    /** D13 INSERT 组级裁决：全部行命中同一分区才定位（跨分区/miss/不可定位 → 整组回退父表）。 */
    private static String insertPartitionOf(final RouteResult route) {
        final List<String> names = route.insertRowPartitions();
        if (null == names || names.isEmpty()) {
            return null;
        }
        String agreed = null;
        for (final String name : names) {
            if (null == name) {
                return null;
            }
            if (null == agreed) {
                agreed = name;
            } else if (!agreed.equals(name)) {
                return null;
            }
        }
        return agreed;
    }

    // ---------- token 装配 ----------

    /** [from, to) 切片内按偏移替换受管表名（分区选择子只落在写目标表名上）。 */
    private static String assemble(final String sql, final List<TableName> tokens,
            final Map<String, String> physical, final TableName writeTarget, final String partition,
            final DialectProfile profile, final BoundStatement bound, final int from, final int to) {
        final StringBuilder out = new StringBuilder(to - from);
        int cursor = from;
        for (final TableName token : tokens) {
            final String plain = physical.get(token.name());
            if (null == plain || token.start() < from || token.stop() >= to) {
                continue;
            }
            final String replacement = token == writeTarget
                    ? targetReplacement(plain, partition, profile, bound.tables().get(token.name()).table())
                    : plain;
            out.append(sql, cursor, token.start());
            out.append(replacement);
            cursor = nameEndExclusive(sql, token);
        }
        out.append(sql, cursor, to);
        return out.toString();
    }

    /** 写目标表名的方言模板替换：MySQL 选择子 / PG 子表名（表维组合回退）/ Oracle 无模板。 */
    private static String targetReplacement(final String physical, final String partition,
            final DialectProfile profile, final TableSnapshot table) {
        if (null == partition) {
            return physical;
        }
        if (DialectProfile.MYSQL == profile) {
            return physical + " PARTITION (" + partition + ")";
        }
        if (DialectProfile.POSTGRESQL == profile) {
            final List<String> layout = table.dataNodes().tableNames();
            if (1 == layout.size() && layout.get(0).equals(table.logicalName())) {
                return partition;
            }
            return physical;
        }
        return physical;
    }

    /**
     * 表名 token 的名字区右端（开区间）：TableName 节点 span 含别名（内核 SelectParser 契约），
     * 改写区 = [node.start, nameEnd)——覆盖限定名与表名、止于别名/AS 之前。按已知 token 文本
     * 前向扫描（空白与注释跳过，注释为内核词法跳过规则的非嵌套近似——对不齐即抛
     * {@link IllegalStateException}，永不静默错切）。
     */
    static int nameEndExclusive(final String sql, final TableName node) {
        int pos = skipInsignificant(sql, node.start());
        if (null != node.qualifier()) {
            pos = expectWord(sql, pos, node.qualifier(), node);
            pos = expectDot(sql, skipInsignificant(sql, pos), node);
            pos = skipInsignificant(sql, pos);
        }
        return expectWord(sql, pos, node.name(), node);
    }

    private static int expectWord(final String sql, final int pos, final String expected, final TableName node) {
        if (pos >= sql.length()) {
            throw misaligned(sql, node, pos);
        }
        int end = pos;
        final char first = sql.charAt(pos);
        if ('`' == first || '"' == first) {
            end++;
            while (end < sql.length() && sql.charAt(end) != first) {
                end++;
            }
            end++;
        } else {
            while (end < sql.length() && isWordChar(sql.charAt(end))) {
                end++;
            }
        }
        if (end <= pos || end > sql.length() || !sql.substring(pos, end).equals(expected)) {
            throw misaligned(sql, node, pos);
        }
        return end;
    }

    private static int expectDot(final String sql, final int pos, final TableName node) {
        if (pos >= sql.length() || '.' != sql.charAt(pos)) {
            throw misaligned(sql, node, pos);
        }
        return pos + 1;
    }

    /** 空白与注释跳过（行注释 --/#、块注释首闭合；内核嵌套块注释在此近似为首闭合，对不齐必抛）。 */
    private static int skipInsignificant(final String sql, final int from) {
        int i = from;
        while (i < sql.length()) {
            final char c = sql.charAt(i);
            if (' ' == c || '\t' == c || '\n' == c || '\r' == c) {
                i++;
            } else if (('-' == c && i + 1 < sql.length() && '-' == sql.charAt(i + 1)) || '#' == c) {
                while (i < sql.length() && '\n' != sql.charAt(i)) {
                    i++;
                }
            } else if ('/' == c && i + 1 < sql.length() && '*' == sql.charAt(i + 1)) {
                i += 2;
                while (i + 1 < sql.length() && !('*' == sql.charAt(i) && '/' == sql.charAt(i + 1))) {
                    i++;
                }
                i += 2;
            } else {
                return i;
            }
        }
        return i;
    }

    private static boolean isWordChar(final char c) {
        return ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || ('0' <= c && c <= '9')
                || '_' == c || '$' == c;
    }

    private static IllegalStateException misaligned(final String sql, final TableName node, final int pos) {
        return new IllegalStateException("表名偏移对齐失败（内核 D5 偏移契约被破坏）: span=[" + node.start()
                + "," + node.stop() + "], 位置=" + pos + ", sql=" + sql);
    }

    // ---------- 小件 ----------

    private static TableName writeTargetOf(final Statement statement) {
        if (statement instanceof InsertStatement) {
            return ((InsertStatement) statement).table();
        }
        if (statement instanceof UpdateStatement) {
            return ((UpdateStatement) statement).table();
        }
        if (statement instanceof DeleteStatement) {
            return ((DeleteStatement) statement).table();
        }
        return null;
    }

    private static String firstDatasource(final MetadataSnapshot snapshot) {
        if (snapshot.datasourceNames().isEmpty()) {
            throw new IllegalStateException("快照无数据源（配置不变量破坏）");
        }
        return snapshot.datasourceNames().get(0);
    }
}
