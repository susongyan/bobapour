package io.github.susongyan.bobapour.config.yaml;

/**
 * YAML 子集解析产物节点（T2-①）。三形态：映射 {@link MapNode} / 序列 {@link SeqNode} /
 * 标量 {@link ScalarNode}；结构相等含标量值与序列顺序（映射键序由 {@link MapNode#names()} 保序暴露），
 * 供黄金用例断言与后续 diff 报告使用。
 */
public abstract class YamlNode {

    @Override
    public abstract String toString();
}
