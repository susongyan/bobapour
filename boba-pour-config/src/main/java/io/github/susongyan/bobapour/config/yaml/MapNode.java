package io.github.susongyan.bobapour.config.yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 映射节点：键为字符串、子节点任意；构造期由解析器独占（{@link #put} 包内可见），
 * 对外只读。键序保持文档书写序（{@link LinkedHashMap}）。
 */
public final class MapNode extends YamlNode {

    private final Map<String, YamlNode> children = new LinkedHashMap<String, YamlNode>();

    void put(final String key, final YamlNode value) {
        children.put(key, value);
    }

    /** 取子节点；键不存在返回 null（与值为 null 的标量区分：见 {@link ScalarNode}）。 */
    public YamlNode get(final String key) {
        return children.get(key);
    }

    /** 全部键，按文档书写序。 */
    public List<String> names() {
        return new ArrayList<String>(children.keySet());
    }

    public int size() {
        return children.size();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MapNode)) {
            return false;
        }
        return children.equals(((MapNode) obj).children);
    }

    @Override
    public int hashCode() {
        return children.hashCode();
    }

    @Override
    public String toString() {
        final StringBuilder out = new StringBuilder("{");
        for (final Map.Entry<String, YamlNode> entry : children.entrySet()) {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return out.append('}').toString();
    }
}
