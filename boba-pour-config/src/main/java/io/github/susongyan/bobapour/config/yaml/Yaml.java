package io.github.susongyan.bobapour.config.yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * YAML 子集解析入口（T2-①）。空文档（或仅注释）返回 null。
 *
 * <pre>
 *   YamlNode node = Yaml.parse("a:\n  b: 1");      // String
 *   YamlNode node = Yaml.parse(Paths.get("x.yaml")); // UTF-8 文件
 * </pre>
 *
 * <p>子集范围与拒绝面见 {@link YamlParser}；一切失败为 {@link YamlParseException}
 * （消息含行号与原文片段）。</p>
 */
public final class Yaml {

    private Yaml() {
    }

    public static YamlNode parse(final String text) {
        return YamlParser.parse(text);
    }

    /** UTF-8 文件解析；IO 失败包装为 YamlParseException（配置加载的单一异常面）。 */
    public static YamlNode parse(final Path file) {
        final String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new YamlParseException("读取失败 " + file + ": " + e.getMessage());
        }
        return YamlParser.parse(text);
    }
}
