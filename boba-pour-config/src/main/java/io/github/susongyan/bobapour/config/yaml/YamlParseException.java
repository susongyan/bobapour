package io.github.susongyan.bobapour.config.yaml;

/**
 * YAML 子集解析失败：语法错误、子集外构造、非法缩进。消息携带行号与原文片段，
 * 定位到行（配置文件人工可修，拒绝显式可读——DESIGN §2.3 铁律在配置层的落点）。
 */
public final class YamlParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    YamlParseException(final String message) {
        super(message);
    }
}
