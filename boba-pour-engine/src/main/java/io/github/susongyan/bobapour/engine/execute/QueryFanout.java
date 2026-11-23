package io.github.susongyan.bobapour.engine.execute;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

/**
 * 读路径 fan-out 持有体（T7）：一个分片单元的三件资源（连接/语句/游标）。
 * 归并结果集关闭时级联 {@link #close()}——游标、语句、连接全还（连接即池归还），
 * 消费方（归并策略）只持有本对象，不逐件管理资源。单元序 = 路由稳定序。
 */
public final class QueryFanout implements AutoCloseable {

    /** 单分片资源三件套（语句经游标 {@code getStatement()} 随之关闭——兼容直执行与参数回放）。 */
    public static final class Shard {

        private final String datasourceName;
        private final Connection connection;
        private final ResultSet resultSet;

        Shard(final String datasourceName, final Connection connection, final ResultSet resultSet) {
            this.datasourceName = datasourceName;
            this.connection = connection;
            this.resultSet = resultSet;
        }

        public String datasourceName() {
            return datasourceName;
        }

        public ResultSet resultSet() {
            return resultSet;
        }

        void close() throws SQLException {
            SQLException failure = null;
            // 语句须在游标关闭<b>前</b>取得——H2 等驱动游标关闭后 getStatement() 抛错
            // （JDBC 仅约定可返回 null）；取不到时跳过，语句随连接关闭级联。
            Statement statement = null;
            try {
                statement = resultSet.getStatement();
            } catch (final SQLException e) {
                statement = null;
            }
            try {
                resultSet.close();
            } catch (final SQLException e) {
                failure = e;
            }
            if (null != statement) {
                try {
                    statement.close();
                } catch (final SQLException e) {
                    if (null == failure) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            try {
                connection.close();
            } catch (final SQLException e) {
                if (null == failure) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (null != failure) {
                throw failure;
            }
        }
    }

    private final List<Shard> shards;

    QueryFanout(final List<Shard> shards) {
        this.shards = shards;
    }

    /** 分片游标清单（路由稳定序，不可变）。 */
    public List<Shard> shards() {
        return Collections.unmodifiableList(shards);
    }

    /** 级联关闭全部资源（JDBC close 幂等，重复调用安全）。 */
    @Override
    public void close() throws SQLException {
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
            throw failure;
        }
    }
}
