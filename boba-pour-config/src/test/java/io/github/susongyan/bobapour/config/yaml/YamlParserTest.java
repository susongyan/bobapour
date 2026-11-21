package io.github.susongyan.bobapour.config.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * YAML 子集解析器黄金用例（T2-①）。正向覆盖配置 schema 会用到的全部形态面
 * （嵌套映射/同缩进与深缩进序列/序列内映射/序列嵌套/标量定型/引号转义/注释），
 * 负向覆盖子集外构造与结构错误的显式拒绝（拒绝面 = javadoc 声明面的逐条回归）。
 */
final class YamlParserTest {

    // ---------- 构树助手（期望树手写，断言用 equals 全等） ----------

    private static MapNode map(final Object... pairs) {
        final MapNode out = new MapNode();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (YamlNode) pairs[i + 1]);
        }
        return out;
    }

    private static SeqNode seq(final YamlNode... items) {
        final SeqNode out = new SeqNode();
        for (final YamlNode item : items) {
            out.add(item);
        }
        return out;
    }

    private static ScalarNode sc(final Object value) {
        return new ScalarNode(String.valueOf(value), value);
    }

    private static ScalarNode nul() {
        return new ScalarNode("", null);
    }

    // ---------- 正向 ----------

    @Test
    void 嵌套映射与深缩进() {
        final YamlNode node = Yaml.parse("a: 1\nb:\n  c: 2\n  d:\n    e: f\n");
        assertEquals(map("a", sc(1L), "b", map("c", sc(2L), "d", map("e", sc("f")))), node);
    }

    @Test
    void 同缩进序列归属父键() {
        final YamlNode node = Yaml.parse("tables:\n- t1\n- t2\nother: 2\n");
        assertEquals(map("tables", seq(sc("t1"), sc("t2")), "other", sc(2L)), node);
    }

    @Test
    void 深缩进序列() {
        final YamlNode node = Yaml.parse("tables:\n  - t1\n  - t2\n");
        assertEquals(map("tables", seq(sc("t1"), sc("t2"))), node);
    }

    @Test
    void 序列内映射与续行对齐() {
        final YamlNode node = Yaml.parse("rules:\n- name: t_order\n  db: ds_$0\n  count: 2\n- name: t_user\n  db: ds_$1\n");
        assertEquals(map("rules", seq(
                map("name", sc("t_order"), "db", sc("ds_$0"), "count", sc(2L)),
                map("name", sc("t_user"), "db", sc("ds_$1")))), node);
    }

    @Test
    void 序列嵌套序列() {
        final YamlNode node = Yaml.parse("m:\n- - a\n  - b\n- c\n");
        assertEquals(map("m", seq(seq(sc("a"), sc("b")), sc("c"))), node);
    }

    @Test
    void 空序列项与空键值为null标量() {
        final YamlNode node = Yaml.parse("a:\nb: ~\nc: null\n");
        assertEquals(map("a", nul(), "b", nul(), "c", nul()), node);
        assertEquals(null, ((ScalarNode) ((MapNode) node).get("a")).value());
    }

    @Test
    void 标量定型_布尔数字字符串() {
        final YamlNode node = Yaml.parse("t: true\nf: False\ni: 42\nneg: -7\nd: 3.25\ne: 1e3\ns: hello\nver: 1.0.0\nport: 08\n");
        final MapNode m = (MapNode) node;
        assertEquals(Boolean.TRUE, ((ScalarNode) m.get("t")).value());
        assertEquals(Boolean.FALSE, ((ScalarNode) m.get("f")).value());
        assertEquals(42L, ((ScalarNode) m.get("i")).value());
        assertEquals(-7L, ((ScalarNode) m.get("neg")).value());
        assertEquals(3.25D, ((ScalarNode) m.get("d")).value());
        assertEquals(1000.0D, ((ScalarNode) m.get("e")).value());
        assertEquals("hello", ((ScalarNode) m.get("s")).value());
        assertEquals("1.0.0", ((ScalarNode) m.get("ver")).value());
        assertEquals(8L, ((ScalarNode) m.get("port")).value());
    }

    @Test
    void 引号键值与转义() {
        final YamlNode node = Yaml.parse("\"key with space\": \"tab\\tnew\\nquote\\\"\"\n's q': 'it''s'\n");
        final MapNode m = (MapNode) node;
        assertEquals("tab\tnew\nquote\"", ((ScalarNode) m.get("key with space")).value());
        assertEquals("it's", ((ScalarNode) m.get("s q")).value());
    }

    @Test
    void 双引号转义unicode() {
        final YamlNode node = Yaml.parse("a: \"\\u4e2d\\u6587\"\n");
        assertEquals("中文", ((ScalarNode) ((MapNode) node).get("a")).value());
    }

    @Test
    void 注释_整行与行尾() {
        final YamlNode node = Yaml.parse("# 头注释\na: 1 # 行尾注释\n   # 缩进注释\nb: 'x # 非注释'\nc: v#非注释\n");
        final MapNode m = (MapNode) node;
        assertEquals(1L, ((ScalarNode) m.get("a")).value());
        assertEquals("x # 非注释", ((ScalarNode) m.get("b")).value());
        assertEquals("v#非注释", ((ScalarNode) m.get("c")).value());
    }

    @Test
    void 键序保持文档序() {
        final MapNode m = (MapNode) Yaml.parse("z: 1\na: 2\nm: 3\n");
        assertEquals(Arrays.asList("z", "a", "m"), m.names());
    }

    @Test
    void 值内冒号不切分() {
        final YamlNode node = Yaml.parse("url: http://db.example:3306/x\n");
        assertEquals("http://db.example:3306/x", ((ScalarNode) ((MapNode) node).get("url")).value());
    }

    @Test
    void 空文档与纯注释() {
        assertEquals(null, Yaml.parse(""));
        assertEquals(null, Yaml.parse("\n\n# 只有注释\n"));
    }

    @Test
    void 根序列文档() {
        assertEquals(seq(sc(1L), sc(2L)), Yaml.parse("- 1\n- 2\n"));
    }

    @Test
    void 拒绝_键值冒号后无空格() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("k:v\n"));
    }

    // ---------- 负向：拒绝面回归 ----------

    @Test
    void 拒绝_制表符缩进() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a:\n\tb: 1\n"));
    }

    @Test
    void 拒绝_重复键() {
        final YamlParseException e = assertThrows(YamlParseException.class,
                () -> Yaml.parse("a: 1\na: 2\n"));
        assertEquals(true, e.getMessage().contains("重复键"));
    }

    @Test
    void 拒绝_流式集合() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: [1, 2]\n"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: {b: 1}\n"));
    }

    @Test
    void 拒绝_锚点别名() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: &anchor 1\n"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: *anchor\n"));
    }

    @Test
    void 拒绝_块标量() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: |\n  text\n"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: >\n  text\n"));
    }

    @Test
    void 拒绝_行内序列值() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: - x\n"));
    }

    @Test
    void 拒绝_未闭合引号() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: \"unclosed\n"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: 'unclosed\n"));
    }

    @Test
    void 拒绝_缩进跳跃() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: 1\n    b: 2\n"));
    }

    @Test
    void 拒绝_映射层缺冒号() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: 1\nb\n"));
    }

    @Test
    void 拒绝_映射层序列项() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: 1\n- x\n"));
    }

    @Test
    void 拒绝_空键() {
        assertThrows(YamlParseException.class, () -> Yaml.parse(": 1\n"));
    }

    @Test
    void 拒绝_不支持的转义() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: \"\\q\"\n"));
    }
}
