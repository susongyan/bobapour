package io.github.susongyan.bobapour.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import io.github.susongyan.bobapour.config.ConfigException;
import io.github.susongyan.bobapour.config.ConfigLoader;
import io.github.susongyan.bobapour.config.model.DataSourceConfig;
import io.github.susongyan.bobapour.config.model.PourConfig;

/**
 * BobaPour 唯一对外入口（D11）——实现 javax.sql.DataSource；v1 不实现 java.sql.Driver、不注册 SPI。
 *
 * <p><b>M1 = 直通模式基线（D8）</b>：无分片无加密全透传，先把 JDBC 契约行为对拍原生驱动锚死；
 * 分片/加密执行层于 M2 启用。配置含分片表/加密表/广播表时构造即拒绝（fail-fast，拒绝可读）——
 * M2 到达前不放行"半支持"状态。
 *
 * <p><b>池由部署方供给（D12），二选一</b>：
 * ① DataSource 实例注入（{@link #PourDataSource(PourConfig, DataSource)}）；或
 * ② 配置 datasources 节 dataSourceClassName+props 反射构造（无参构造 + setter 注入，
 * M1 直通须恰一个物理数据源）。池 jar 与原生驱动同为部署方 provided，不进运行时依赖。
 *
 * <p><b>pour 契约①：借出显式设满连接状态</b>。首次借出捕获底层基线状态（autoCommit/隔离级/
 * readOnly/catalog/schema/holdability），此后每次借出显式重置为该基线——应用看到的连接状态
 * 与池的归还重置策略解耦。契约②流式参数模板随执行层（M2/T7）落地；契约③fan-out 容量换算
 * 见 DESIGN D12。
 *
 * <p><b>包装透明性</b>：包装层零自有语义（关闭语义/异常类型/警告等一律由底层驱动裁定），
 * 仅两类显式介入——借出状态设满（契约①）与语句包装回指（statement.getConnection() 返回
 * 借出时的 pour 连接，身份一致）。ResultSet 与 DatabaseMetaData 不包装：直通无归并无改写，
 * 原生对象即语义；归并包装随 M2/T7 到达。
 */
public final class PourDataSource implements DataSource, AutoCloseable {

    private final PourConfig config;
    private final DataSource delegate;
    /** true = 池由反射构造（本类所有），close 代为关闭；注入形态池生命周期归部署方。 */
    private final boolean ownsPool;
    /** 首次借出捕获的状态基线（契约①），synchronized 惰性捕获一次。 */
    private ConnectionState baseline;

    /** 程序注入形态（D12）：池实例由部署方直接供给。 */
    public PourDataSource(final PourConfig config, final DataSource delegate) {
        requirePassthroughCapable(config);
        if (null == delegate) {
            throw new ConfigException("配置错误： jdbc: 注入形态要求非空 DataSource 实例（D12）");
        }
        this.config = config;
        this.delegate = delegate;
        this.ownsPool = false;
    }

    /** 反射构造形态（D12）：datasources 节 dataSourceClassName+props 构造池，M1 直通须恰一个数据源。 */
    public PourDataSource(final PourConfig config) {
        requirePassthroughCapable(config);
        if (1 != config.datasources().size()) {
            throw new ConfigException("配置错误： datasources: M1 直通模式须恰一个物理数据源（当前="
                    + config.datasources().size() + "）");
        }
        this.config = config;
        this.delegate = reflect(config.datasources().values().iterator().next());
        this.ownsPool = true;
    }

    /** 便利形态：YAML 配置文件加载（ConfigLoader）后走反射构造形态。 */
    public PourDataSource(final Path configFile) {
        this(ConfigLoader.load(configFile));
    }

    /** M1 直通基线（D8）：分片/加密/广播任一存在即构造拒绝。 */
    private static void requirePassthroughCapable(final PourConfig config) {
        if (!config.shardingTables().isEmpty()
                || !config.encrypt().tables().isEmpty()
                || !config.broadcastTables().isEmpty()) {
            throw new ConfigException("配置错误： jdbc: M1 仅直通模式（D8），分片/加密/广播配置于 M2 启用");
        }
    }

    /** dataSourceClassName 无参构造 + props setter 逐项注入（标量类型收敛，错误路径带配置键）。 */
    private static DataSource reflect(final DataSourceConfig source) {
        final Object instance;
        try {
            final Class<?> type = Class.forName(source.className());
            instance = type.getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException | IllegalArgumentException | SecurityException e) {
            throw new ConfigException("配置错误： datasources." + source.name()
                    + ": dataSourceClassName 反射构造失败——" + e.getMessage());
        }
        for (final Map.Entry<String, String> prop : source.props().entrySet()) {
            applyProp(source.name(), instance, prop.getKey(), prop.getValue());
        }
        try {
            return (DataSource) instance;
        } catch (final ClassCastException e) {
            throw new ConfigException("配置错误： datasources." + source.name()
                    + ": " + source.className() + " 未实现 javax.sql.DataSource");
        }
    }

    private static void applyProp(final String sourceName, final Object instance,
            final String name, final String value) {
        final String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method target = null;
        for (final Method method : instance.getClass().getMethods()) {
            if (method.getName().equals(setter) && 1 == method.getParameterCount()) {
                target = method;
                break;
            }
        }
        if (null == target) {
            throw new ConfigException("配置错误： datasources." + sourceName + ".props." + name + ": 无匹配 setter");
        }
        try {
            target.invoke(instance, convert(target.getParameterTypes()[0], value));
        } catch (final ReflectiveOperationException | IllegalArgumentException e) {
            throw new ConfigException("配置错误： datasources." + sourceName + ".props." + name
                    + ": 注入失败——" + e.getMessage());
        }
    }

    /** props 值恒为字符串（DataSourceConfig 契约），按 setter 形参类型收敛；未识别类型按原文传入、反射失败显式报错。 */
    private static Object convert(final Class<?> type, final String value) {
        if (String.class == type || Object.class == type) {
            return value;
        }
        if (Integer.TYPE == type || Integer.class == type) {
            return Integer.valueOf(value.trim());
        }
        if (Long.TYPE == type || Long.class == type) {
            return Long.valueOf(value.trim());
        }
        if (Boolean.TYPE == type || Boolean.class == type) {
            return Boolean.valueOf(value.trim());
        }
        if (Double.TYPE == type || Double.class == type) {
            return Double.valueOf(value.trim());
        }
        if (Float.TYPE == type || Float.class == type) {
            return Float.valueOf(value.trim());
        }
        return value;
    }

    /** 借出连接：状态设满（契约①）后以 pour 包装返回；包装透明，行为由底层驱动裁定。 */
    @Override
    public Connection getConnection() throws SQLException {
        final Connection raw = delegate.getConnection();
        stateBaseline(raw).apply(raw);
        return new PourConnection(this, raw);
    }

    @Override
    public Connection getConnection(final String username, final String password) throws SQLException {
        final Connection raw = delegate.getConnection(username, password);
        stateBaseline(raw).apply(raw);
        return new PourConnection(this, raw);
    }

    /** 首次借出捕获基线并锁定，此后沿用同一基线（单次 synchronized 捕获，热路径无锁）。 */
    private ConnectionState stateBaseline(final Connection raw) throws SQLException {
        synchronized (this) {
            if (null == baseline) {
                baseline = ConnectionState.capture(raw);
            }
            return baseline;
        }
    }

    /** 借出时的配置视图（只读）。 */
    public PourConfig config() {
        return config;
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(final PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(final int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    /** 关闭本类反射构造的池实例；注入形态不代关（部署方职责），幂等。 */
    @Override
    public void close() throws Exception {
        if (ownsPool && delegate instanceof AutoCloseable) {
            ((AutoCloseable) delegate).close();
        }
    }
}
