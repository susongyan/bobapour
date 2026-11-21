package io.github.susongyan.bobapour.config.yaml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 序列节点：子节点任意、顺序敏感（equals 按序比较）；构造期由解析器独占。
 */
public final class SeqNode extends YamlNode {

    private final List<YamlNode> items = new ArrayList<YamlNode>();

    /** 空序列（外部构造默认值的合法途径；add 包内私有保证其不可变）。 */
    public static SeqNode empty() {
        return new SeqNode();
    }

    void add(final YamlNode item) {
        items.add(item);
    }

    public List<YamlNode> items() {
        return Collections.unmodifiableList(items);
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SeqNode)) {
            return false;
        }
        return items.equals(((SeqNode) obj).items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        return items.toString();
    }
}
