package io.github.susongyan.bobapour.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import io.github.susongyan.bobapour.config.ConfigException;
import io.github.susongyan.bobapour.config.ConfigLoader;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.config.model.DataSourceConfig;
import io.github.susongyan.bobapour.config.model.PourConfig;
import io.github.susongyan.bobapour.engine.execute.Executor;

/**
 * BobaPour 唯一对外入口（D11）——实现 javax.sql.DataSource；v1 不实现 java.sql.Driver、不注册 SPI。
 *
 * <p><b>双模式（T7 起）</b>：配置含分片表/广播表 = <b>分片引擎模式</b>——借出
 * {@link ShardedConnection}（语句走决策链：解析→绑定→路由→裁决→改写→执行→归并，
 * 每单元独立借还连接，v0 串行编排）；无分片无广播 = <b>直通模式</b>（D8/M1 基线，
 * {@link PourConnection} 零自有语义全委托，JDBC 契约行为已对拍原生驱动锚死）。
 * 加密配置于 M3/T8 启用前构造即拒绝（fail-fast，不放行半支持状态）。</p>
 *
 * <p><b>池由部署方供给（D12）</b>：引擎模式按数据源名一一对应——① {@code Map<String,DataSource>}
 * 实例注入（键 = 配置数据源名，须精确覆盖）；② 配置 datasources 节 dataSourceClassName+props
 * 反射构造（每数据源一个实例，本类所有 close 代为关闭）。直通模式沿用 M1 的单实例注入/
 * 反射形态。池 jar 与原生驱动同为部署方 provided，不进运行时依赖。</p>
 *
 * <p><b>直通契约①：借出显式设满连接状态</b>（首次借出捕获基线，此后每次借出重置）——
 * 只属直通模式；引擎模式连接状态面见 {@link ShardedConnection}（事务跟随挂 T9）。
 * 契约②流式提示已随引擎执行层落地（openQuery 按快照 guards）；契约③fan-out 容量换算见 DESIGN D12。</p>
 */
public final class PourDataSource implements DataSource, AutoCloseable {

    private final PourConfig config;
    /** 直通模式单物理数据源（引擎模式为 null）。 */
    private final DataSource delegate;
    /** 引擎模式数据源名 → 池实例（直通模式为空映射）。 */
    private final Map<String, DataSource> pools;
    /** 引擎模式只读快照与连接供给（直通模式为 null）。 */
    private final MetadataSnapshot snapshot;
    private final Executor.ConnectionProvider provider;
    /** true = 池由反射构造（本类所有），close 代为关闭；注入形态池生命周期归部署方。 */
    private final boolean ownsPool;
    /** 直通模式首次借出捕获的状态基线（契约①），synchronized 惰性捕获一次。 */
    private ConnectionState baseline;

    /** 直通模式：池实例由部署方直接供给（配置不得含分片/广播/加密）。 */
    public PourDataSource(final PourConfig config, final DataSource delegate) {
        this(config, delegate, null, false);
    }

    /** 引擎模式注入形态：数据源名 → 池实例（须与配置数据源名精确一致）。 */
    public PourDataSource(final PourConfig config, final Map<String, DataSource> pools) {
        this(config, null, pools, false);
    }

    /** 反射构造形态（D12）：datasources 节逐数据源 dataSourceClassName+props 构造池（本类所有）。 */
    public PourDataSource(final PourConfig config) {
        this(config, null, reflectAll(config), true);
    }

    /** 便利形态：YAML 配置文件加载（ConfigLoader）。 */
    public PourDataSource(final Path configFile) {
        this(ConfigLoader.load(configFile));
    }

    private PourDataSource(final PourConfig config, final DataSource single,
            final Map<String, DataSource> injected, final boolean ownsPools) {
        if (!config.encrypt().tables().isEmpty()) {
            throw new ConfigException("配置错误： jdbc: 加密于 M3/T8 启用（当前版本不放行半支持状态）");
        }
        this.config = config;
        final boolean engine = !config.shardingTables().isEmpty() || !config.broadcastTables().isEmpty();
        if (engine) {
            if (null != single) {
                throw new ConfigException("配置错误： jdbc: 分片/广播配置须用 Map<String,DataSource> 注入"
                        + "或反射构造形态（单实例注入为直通专用）");
            }
            final Map<String, DataSource> byName = new LinkedHashMap<String, DataSource>();
            for (final DataSourceConfig source : config.datasources().values()) {
                final DataSource pool = injected.get(source.name());
                if (null == pool) {
                    throw new ConfigException("配置错误： jdbc: 数据源 " + source.name() + " 未供给池实例");
                }
                byName.put(source.name(), pool);
            }
            this.pools = byName;
            this.delegate = null;
            this.snapshot = MetadataSnapshot.of(config);
            this.provider = new Executor.ConnectionProvider() {
                @Override
                public Connection connection(final String datasourceName) throws SQLException {
                    return byName.get(datasourceName).getConnection();
                }
            };
            this.ownsPool = ownsPools;
            return;
        }
        if (null != single) {
            if (1 != config.datasources().size()) {
                throw new ConfigException("配置错误： datasources: 直通模式须恰一个物理数据源（当前="
                        + config.datasources().size() + "）");
            }
            this.delegate = single;
            this.pools = new LinkedHashMap<String, DataSource>();
            this.snapshot = null;
            this.provider = null;
            this.ownsPool = false;
            return;
        }
        if (1 != injected.size() || 1 != config.datasources().size()) {
            throw new ConfigException("配置错误： jdbc: 直通模式（无分片配置）须恰一个物理数据源");
        }
        this.delegate = injected.values().iterator().next();
        this.pools = new LinkedHashMap<String, DataSource>();
        this.snapshot = null;
        this.provider = null;
        this.ownsPool = ownsPools;
    }

    /** 引擎模式判定（分片表或广播表存在）。 */
    public boolean engineMode() {
        return null != snapshot;
    }

    /** 引擎模式连接供给（直通模式为 null）。 */
    Executor.ConnectionProvider provider() {
        return provider;
    }

    /** 引擎模式只读快照（直通模式为 null）。 */
    MetadataSnapshot snapshot() {
        return snapshot;
    }

    /** 借出连接：引擎模式 = ShardedConnection（逐单元借还）；直通模式 = 状态设满（契约①）后包装。 */
    @Override
    public Connection getConnection() throws SQLException {
        if (engineMode()) {
            return new ShardedConnection(this);
        }
        final Connection raw = delegate.getConnection();
        stateBaseline(raw).apply(raw);
        return new PourConnection(this, raw);
    }

    @Override
    public Connection getConnection(final String username, final String password) throws SQLException {
        if (engineMode()) {
            throw new SQLFeatureNotSupportedException("分片模式借出不支持指定账密（池账密在数据源配置）");
        }
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

    // ---------- 反射构造（D12） ----------

    private static Map<String, DataSource> reflectAll(final PourConfig config) {
        final Map<String, DataSource> out = new LinkedHashMap<String, DataSource>();
        for (final DataSourceConfig source : config.datasources().values()) {
            out.put(source.name(), reflect(source));
        }
        return out;
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

    // ---------- DataSource 面 ----------

    private DataSource primary() {
        if (engineMode()) {
            return pools.values().iterator().next();
        }
        return delegate;
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return primary().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
        return iface.isInstance(this) || primary().isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return primary().getLogWriter();
    }

    @Override
    public void setLogWriter(final PrintWriter out) throws SQLException {
        primary().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(final int seconds) throws SQLException {
        primary().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return primary().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return primary().getParentLogger();
    }

    /** 关闭本类反射构造的池实例；注入形态不代关（部署方职责），幂等。 */
    @Override
    public void close() throws Exception {
        if (ownsPool) {
            for (final DataSource pool : pools.values()) {
                if (pool instanceof AutoCloseable) {
                    ((AutoCloseable) pool).close();
                }
            }
            if (delegate instanceof AutoCloseable) {
                ((AutoCloseable) delegate).close();
            }
        }
    }
}
