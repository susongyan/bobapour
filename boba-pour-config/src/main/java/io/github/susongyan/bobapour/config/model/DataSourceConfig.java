package io.github.susongyan.bobapour.config.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 物理数据源声明（D12：池由部署方供给）。className = 池/驱动 DataSource 实现类全名
 * （经无参构造 + properties 反射注入）；亦可在代码中以 DataSource 实例直接注入
 * （文件声明与程序注入二选一，程序注入形态在 jdbc 模块 M1 落地）。
 * props 值为字符串（标量按文本收敛），如 jdbcUrl/username/maximumPoolSize。
 */
public final class DataSourceConfig {

    private final String name;
    private final String className;
    private final Map<String, String> props;

    public DataSourceConfig(final String name, final String className, final Map<String, String> props) {
        this.name = name;
        this.className = className;
        this.props = Collections.unmodifiableMap(new LinkedHashMap<String, String>(props));
    }

    public String name() {
        return name;
    }

    public String className() {
        return className;
    }

    public Map<String, String> props() {
        return props;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DataSourceConfig)) {
            return false;
        }
        final DataSourceConfig other = (DataSourceConfig) obj;
        return Objects.equals(name, other.name) && Objects.equals(className, other.className)
                && Objects.equals(props, other.props);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, className, props);
    }

    @Override
    public String toString() {
        return name + "(" + className + ")";
    }
}
