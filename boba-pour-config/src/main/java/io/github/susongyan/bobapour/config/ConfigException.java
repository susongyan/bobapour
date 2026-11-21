package io.github.susongyan.bobapour.config;

/**
 * 配置加载失败：结构错误、未知键、类型不符、交叉引用断裂、分区边界非法。
 * 消息携带配置路径（如 {@code sharding.tables[t_order].partition.ranges[1].upper}）
 * 与具体原因——配置自身错误 fail-fast（DESIGN D13 v1.8 口径）。
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(final String message) {
        super(message);
    }
}
