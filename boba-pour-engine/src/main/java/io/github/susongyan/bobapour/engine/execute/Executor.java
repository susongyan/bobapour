package io.github.susongyan.bobapour.engine.execute;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import io.github.susongyan.bobapour.config.model.GuardsConfig;
import io.github.susongyan.bobapour.engine.merge.MergePlan;
import io.github.susongyan.bobapour.engine.rewrite.RewriteResult;

/**
 * 执行器（T7，DESIGN §5.1 execute 层）：消费 {@link RewriteResult} 的 fan-out 单元——
 * 写路径逐单元执行并汇总影响行数；读路径打开各分片游标交归并层。**v0 为串行编排**
 * （逐单元顺序执行，无连接级并行——并行编排挂后续任务，边界如实记录）；每单元独立借
 * 连接（{@link ConnectionProvider} 由 jdbc 层供给，D12 池域归部署方），单元完成后即还。
 *
 * <p>写路径影响行数语义（D9/D10）：UPDATE/DELETE 多分片与 INSERT 拆行 = 逐单元求和；
 * 广播写 = 全副本执行、取首副本计数（副本同构写，首副本为代表值）。广播写**无跨副本
 * 原子性**：某副本失败时先执行的副本已提交，异常原样上抛（kill switch + 幂等广播写为
 * 运维前提，与存量实现语义一致）。</p>
 */
public final class Executor {

    private Executor() {
    }

    /** 数据源名 → 物理连接（jdbc 层供给：池 DataSource 借出或注入映射）。 */
    public interface ConnectionProvider {

        /** 按数据源名借出连接（调用方负责关闭=归还池）。 */
        Connection connection(String datasourceName) throws SQLException;
    }

    /** 读路径单元执行器：默认 Statement 直执行；参数回放形态（jdbc 预编译语句）由此注入。 */
    public interface UnitQuery {

        /** 在连接上执行单元 SQL 返回已打开游标（语句经 {@code getStatement()} 随游标关闭）。 */
        ResultSet execute(Connection connection, String sql) throws SQLException;
    }

    /** 写路径单元执行器（参数回放形态同 {@link UnitQuery}）。 */
    public interface UnitUpdate {

        /** 在连接上执行单元写 SQL，返回影响行数（语句与连接由实现负责关闭）。 */
        int execute(Connection connection, String sql) throws SQLException;
    }

    /** 写路径：逐单元执行，返回合并影响行数（广播写取首副本计数，见类注）。 */
    public static int executeUpdate(final RewriteResult units, final ConnectionProvider provider,
            final MergePlan plan) throws SQLException {
        return executeUpdate(units, provider, plan, new UnitUpdate() {
            @Override
            public int execute(final Connection connection, final String sql) throws SQLException {
                return executeOne(connection, sql);
            }
        });
    }

    /** 写路径（自定义单元执行）：参数回放等形态经 {@link UnitUpdate} 注入。 */
    public static int executeUpdate(final RewriteResult units, final ConnectionProvider provider,
            final MergePlan plan, final UnitUpdate unit) throws SQLException {
        if (units.isEmpty()) {
            throw new IllegalStateException("写路径 fan-out 单元为空（裁决不变量破坏）");
        }
        boolean first = true;
        int total = 0;
        int representative = 0;
        for (final RewriteResult.RoutedSql routed : units.units()) {
            final Connection connection = provider.connection(routed.datasourceName());
            final int count = unit.execute(connection, routed.sql());
            if (first) {
                representative = count;
                first = false;
            }
            total += count;
        }
        return MergePlan.Kind.BROADCAST_WRITE == plan.kind() ? representative : total;
    }

    /** 读路径：打开全部分片游标（返回值持有连接，游标关闭级联归还——见 {@link QueryFanout}）。 */
    public static QueryFanout openQuery(final RewriteResult units, final ConnectionProvider provider,
            final GuardsConfig guards) throws SQLException {
        return openQuery(units, provider, guards, new UnitQuery() {
            @Override
            public ResultSet execute(final Connection connection, final String sql) throws SQLException {
                final Statement statement = connection.createStatement();
                // D3 第三防线（提示级）：游标流式提示；方言强模板参数（MySQL streaming/cursorFetch）
                // 随方言适配任务落驱动参数模板，此处不预置方言魔法值。
                if (guards.streamingFetch()) {
                    statement.setFetchSize(1);
                }
                return statement.executeQuery(sql);
            }
        });
    }

    /** 读路径（自定义单元执行）：参数回放等形态经 {@link UnitQuery} 注入。 */
    public static QueryFanout openQuery(final RewriteResult units, final ConnectionProvider provider,
            final GuardsConfig guards, final UnitQuery unit) throws SQLException {
        if (units.isEmpty()) {
            throw new IllegalStateException("读路径 fan-out 单元为空（裁决不变量破坏）");
        }
        final List<QueryFanout.Shard> shards = new ArrayList<QueryFanout.Shard>(units.units().size());
        try {
            for (final RewriteResult.RoutedSql routed : units.units()) {
                final Connection connection = provider.connection(routed.datasourceName());
                final ResultSet resultSet = unit.execute(connection, routed.sql());
                shards.add(new QueryFanout.Shard(routed.datasourceName(), connection, resultSet));
            }
        } catch (final SQLException e) {
            closeShards(shards, e);
            throw e;
        }
        return new QueryFanout(shards);
    }

    private static int executeOne(final Connection connection, final String sql) throws SQLException {
        try {
            final Statement statement = connection.createStatement();
            try {
                return statement.executeUpdate(sql);
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private static void closeShards(final List<QueryFanout.Shard> shards, final SQLException cause)
            throws SQLException {
        SQLException failure = null;
        for (int i = shards.size() - 1; i >= 0; i--) {
            try {
                shards.get(i).close();
            } catch (final SQLException e) {
                if (null == failure) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (null != failure) {
            cause.addSuppressed(failure);
        }
    }
}
