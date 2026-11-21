package io.github.susongyan.bobapour.config.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 算法声明（分片算法与加密算法共用形态，DESIGN §2.4 SPI）：类型名 + 属性表。
 * type 为注册名（内置或 SPI 实现注册）；props 值一律字符串形态（标量按文本收敛）。
 */
public final class AlgorithmConfig {

    private final String type;
    private final Map<String, String> props;

    public AlgorithmConfig(final String type, final Map<String, String> props) {
        this.type = type;
        this.props = Collections.unmodifiableMap(new LinkedHashMap<String, String>(props));
    }

    public String type() {
        return type;
    }

    public Map<String, String> props() {
        return props;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AlgorithmConfig)) {
            return false;
        }
        final AlgorithmConfig other = (AlgorithmConfig) obj;
        return Objects.equals(type, other.type) && Objects.equals(props, other.props);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, props);
    }

    @Override
    public String toString() {
        return type + props;
    }
}
