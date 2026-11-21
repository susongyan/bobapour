package io.github.susongyan.bobapour.config.yaml;

import java.util.Objects;

/**
 * 标量节点。{@link #value()} 类型四选一：null（空值/null/~）/ Boolean / Long / Double / String——
 * 布尔仅 true|false（含首字母大写形态），数字十进制（整型 Long、小数/科学计数 Double），
 * 其余一律 String；{@link #raw()} 保留引号内剥前原文供诊断。裸空串（{@code key:} 后无内容）
 * 解析为 value=null 的标量而非 Java null——映射中"键存在且值为空"与"键不存在"由此区分。
 */
public final class ScalarNode extends YamlNode {

    private final String raw;
    private final Object value;

    ScalarNode(final String raw, final Object value) {
        this.raw = raw;
        this.value = value;
    }

    public Object value() {
        return value;
    }

    /** 原文（引号形态保留引号本体），诊断与 diff 报告用。 */
    public String raw() {
        return raw;
    }

    /** 值的字符串形态；null 值返回 null。 */
    public String asText() {
        return null == value ? null : String.valueOf(value);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ScalarNode)) {
            return false;
        }
        final ScalarNode other = (ScalarNode) obj;
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return null == value ? "null" : String.valueOf(value);
    }
}
