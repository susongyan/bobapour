package io.github.susongyan.bobapour.config.yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 最小 YAML 子集解析器（T2-①，行导向递归下降——与内核同一纪律：自家 schema 需要什么就支持什么，
 * 子集外构造显式拒绝且消息带行号）。零第三方依赖红线不破（DESIGN §0）。
 *
 * <p><b>支持面</b>：块映射（嵌套缩进、同缩进序列归属父键）、块序列（含序列内映射
 * {@code - key: v} 与续行、序列嵌套序列）、标量（裸 true/false/null/~、十进制整型/小数/科学计数、
 * 单引号双写转义、双引号 \\ 转义含 \\uXXXX）、整行与行尾 {@code #} 注释（引号内不生效）。</p>
 *
 * <p><b>显式拒绝（YamlParseException）</b>：锚点/别名 {@code &} {@code *}、块标量
 * {@code |} {@code &gt;}、流式集合 {@code [} {@code {}、多文档 {@code ---}、标签/指令
 * {@code !} {@code %}、行内序列值（{@code key: - x}）、制表符缩进、重复键、空键、
 * 未闭合引号、缩进跳跃、序列项出现在映射层、引号键后缺冒号。</p>
 */
final class YamlParser {

    /** 逻辑行：空行与纯注释行已剔除，行尾注释已剥离。 */
    private static final class Line {
        final int indent;
        final String text;
        final int number;

        Line(final int indent, final String text, final int number) {
            this.indent = indent;
            this.text = text;
            this.number = number;
        }
    }

    /** 小数/科学计数形态（先 Long 后此正则，杜绝 Double.parseDouble 收 Infinity/0x1p3 之类的宽口）。 */
    private static final Pattern DOUBLE = Pattern.compile("[+-]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?");

    private final List<Line> lines;
    private int pos;

    private YamlParser(final List<Line> lines) {
        this.lines = lines;
    }

    static YamlNode parse(final String input) {
        final List<Line> logical = new ArrayList<Line>();
        // BOM（0xFEFF）剥除：int 码点比较，避免源文件里出现不可见字符
        String normalized = input;
        if (!normalized.isEmpty() && 0xFEFF == normalized.charAt(0)) {
            normalized = normalized.substring(1);
        }
        final String[] physical = normalized.split("\n", -1);
        for (int i = 0; i < physical.length; i++) {
            final String raw = physical[i].endsWith("\r") ? physical[i].substring(0, physical[i].length() - 1)
                    : physical[i];
            final Line line = toLine(raw, i + 1);
            if (null != line) {
                logical.add(line);
            }
        }
        final YamlParser parser = new YamlParser(logical);
        if (logical.isEmpty()) {
            return null;
        }
        final YamlNode root = parser.node(logical.get(0).indent);
        if (parser.pos != logical.size()) {
            throw parser.fail(logical.get(parser.pos), "内容无法挂接（缩进低于所在层）");
        }
        return root;
    }

    /** 物理行 → 逻辑行；空行/纯注释返回 null。缩进只许空格，行尾注释引号感知剥离。 */
    private static Line toLine(final String raw, final int number) {
        int indent = 0;
        while (indent < raw.length() && ' ' == raw.charAt(indent)) {
            indent++;
        }
        if (indent < raw.length() && '\t' == raw.charAt(indent)) {
            throw new YamlParseException("第 " + number + " 行: 缩进不允许制表符");
        }
        final String stripped = stripComment(raw.substring(indent)).replaceAll("\\s+$", "");
        if (stripped.isEmpty()) {
            return null;
        }
        return new Line(indent, stripped, number);
    }

    /** 行尾注释剥离：# 位于行首或前随空白且不在引号内才生效。 */
    private static String stripComment(final String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (0 != quote) {
                if ('\\' == quote && '\\' == c && i + 1 < text.length()) {
                    i++;
                } else if ('\'' == quote && '\'' == c && i + 1 < text.length() && '\'' == text.charAt(i + 1)) {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if ('\'' == c || '"' == c) {
                quote = c;
            } else if ('#' == c && (0 == i || ' ' == text.charAt(i - 1) || '\t' == text.charAt(i - 1))) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    private YamlNode node(final int indent) {
        return isSeqEntry(lines.get(pos).text) ? seq(indent) : map(indent);
    }

    private MapNode map(final int indent) {
        final MapNode out = new MapNode();
        while (pos < lines.size()) {
            final Line cur = lines.get(pos);
            if (cur.indent < indent) {
                return out;
            }
            if (cur.indent > indent) {
                throw fail(cur, "缩进跳跃（无法挂接到任何键）");
            }
            if (isSeqEntry(cur.text)) {
                throw fail(cur, "映射层出现序列项（'-' 应属于上方某键或独立成文档）");
            }
            final String[] kv = splitKey(cur);
            if (null == kv[0]) {
                throw fail(cur, "缺少 ':' 分隔");
            }
            if (kv[0].isEmpty()) {
                throw fail(cur, "键为空");
            }
            if (out.names().contains(kv[0])) {
                throw fail(cur, "重复键 '" + kv[0] + "'");
            }
            pos++;
            out.put(kv[0], kv[1].isEmpty() ? childOrEmpty(indent, cur) : value(kv[1], cur));
        }
        return out;
    }

    /** 空值键取子节点：深缩进取节点；同缩进序列归属本键；否则空标量。 */
    private YamlNode childOrEmpty(final int keyIndent, final Line keyLine) {
        if (pos >= lines.size()) {
            return new ScalarNode("", null);
        }
        final Line next = lines.get(pos);
        if (next.indent > keyIndent) {
            return node(next.indent);
        }
        if (next.indent == keyIndent && isSeqEntry(next.text)) {
            return seq(keyIndent);
        }
        return new ScalarNode("", null);
    }

    private SeqNode seq(final int indent) {
        final SeqNode out = new SeqNode();
        while (pos < lines.size()) {
            final Line cur = lines.get(pos);
            if (cur.indent < indent || !isSeqEntry(cur.text)) {
                return out;
            }
            if (cur.indent > indent) {
                throw fail(cur, "缩进跳跃（无法挂接到任何序列项）");
            }
            final String rest = "-".equals(cur.text) ? "" : cur.text.substring(2);
            if (rest.isEmpty()) {
                pos++;
                out.add(pos < lines.size() && lines.get(pos).indent > indent
                        ? node(lines.get(pos).indent) : new ScalarNode("", null));
            } else if (isInlineNode(rest)) {
                int lead = 0;
                while (lead < rest.length() && ' ' == rest.charAt(lead)) {
                    lead++;
                }
                // 序列项内容重写为虚缩进行，交由 node() 按新基线消费（- key: v 的续行与其对齐）
                lines.set(pos, new Line(cur.indent + 2 + lead, rest.substring(lead), cur.number));
                out.add(node(cur.indent + 2 + lead));
            } else {
                pos++;
                out.add(scalar(rest, cur));
            }
        }
        return out;
    }

    /** 序列项内容是否为结构节点（嵌套序列或映射起始），否则按标量处理。引号开头一律标量。 */
    private static boolean isInlineNode(final String rest) {
        if ('\'' == rest.charAt(0) || '"' == rest.charAt(0)) {
            return false;
        }
        return isSeqEntry(rest) || null != plainKeyColon(rest);
    }

    /** 键值切分：[0]=键（null 表示无 ':' 分隔）、[1]=冒号后内容（已 trim）。引号键支持转义。 */
    private static String[] splitKey(final Line line) {
        final String text = line.text;
        if ('\'' == text.charAt(0) || '"' == text.charAt(0)) {
            final int close = findQuoteClose(text, 0, line);
            final String key = unquote(text.substring(0, close + 1), line);
            if (close + 1 >= text.length() || ':' != text.charAt(close + 1)) {
                throw fail(line, "引号键后缺 ':'");
            }
            return new String[]{key, afterColon(text, close + 1)};
        }
        final Integer colon = plainKeyColon(text);
        if (null == colon) {
            return new String[]{null, ""};
        }
        final String key = text.substring(0, colon.intValue()).trim();
        return new String[]{key, afterColon(text, colon.intValue())};
    }

    private static String afterColon(final String text, final int colon) {
        return text.substring(colon + 1).trim();
    }

    /** 裸键首个后随空格/行尾的冒号位置；无则 null。 */
    private static Integer plainKeyColon(final String text) {
        for (int i = 0; i < text.length(); i++) {
            if (':' == text.charAt(i) && (i + 1 == text.length() || ' ' == text.charAt(i + 1))) {
                return Integer.valueOf(i);
            }
        }
        return null;
    }

    /** 值解析：子集外构造显式拒绝，行内序列拒绝，其余标量。 */
    private YamlNode value(final String rest, final Line line) {
        final char c = rest.charAt(0);
        if ('&' == c || '*' == c) {
            throw fail(line, "不支持的 YAML 构造: 锚点/别名（子集外）");
        }
        if ('|' == c || '>' == c) {
            throw fail(line, "不支持的 YAML 构造: 块标量（子集外）");
        }
        if ('[' == c || '{' == c) {
            throw fail(line, "不支持的 YAML 构造: 流式集合（子集外）");
        }
        if ('!' == c || '%' == c || '@' == c || '`' == c) {
            throw fail(line, "不支持的 YAML 构造: 标签/指令/保留字符（子集外）");
        }
        if ("-".equals(rest) || rest.startsWith("- ")) {
            throw fail(line, "值不支持行内序列（块序列须另起一行）");
        }
        if ('-' == rest.charAt(0) && rest.startsWith("---")) {
            throw fail(line, "不支持的 YAML 构造: 多文档分隔（子集外）");
        }
        return scalar(rest, line);
    }

    private static boolean isSeqEntry(final String text) {
        return "-".equals(text) || text.startsWith("- ");
    }

    /** 标量定型：引号→String（转义展开）；裸 true/false（含首字母大写）/null/~；数字；其余 String。 */
    private static ScalarNode scalar(final String raw, final Line line) {
        final char c = raw.charAt(0);
        if ('"' == c) {
            return new ScalarNode(raw, unescapeDouble(raw, line));
        }
        if ('\'' == c) {
            return new ScalarNode(raw, unescapeSingle(raw, line));
        }
        if ("null".equals(raw) || "Null".equals(raw) || "NULL".equals(raw) || "~".equals(raw)) {
            return new ScalarNode(raw, null);
        }
        if ("true".equals(raw) || "True".equals(raw) || "TRUE".equals(raw)) {
            return new ScalarNode(raw, Boolean.TRUE);
        }
        if ("false".equals(raw) || "False".equals(raw) || "FALSE".equals(raw)) {
            return new ScalarNode(raw, Boolean.FALSE);
        }
        if (isInteger(raw)) {
            return new ScalarNode(raw, Long.valueOf(Long.parseLong(raw)));
        }
        if (DOUBLE.matcher(raw).matches()) {
            return new ScalarNode(raw, Double.valueOf(Double.parseDouble(raw)));
        }
        return new ScalarNode(raw, raw);
    }

    private static boolean isInteger(final String raw) {
        final String digits = raw.startsWith("-") || raw.startsWith("+") ? raw.substring(1) : raw;
        if (digits.isEmpty()) {
            return false;
        }
        for (int i = 0; i < digits.length(); i++) {
            final char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        try {
            Long.parseLong(raw);
            return true;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    /** 引号闭合位置（含转义跳越）；未闭合即拒绝。 */
    private static int findQuoteClose(final String text, final int start, final Line line) {
        final char quote = text.charAt(start);
        for (int i = start + 1; i < text.length(); i++) {
            final char c = text.charAt(i);
            if ('"' == quote && '\\' == c) {
                i++;
            } else if ('\'' == quote && '\'' == c) {
                if (i + 1 < text.length() && '\'' == text.charAt(i + 1)) {
                    i++;
                } else {
                    return i;
                }
            } else if (c == quote) {
                return i;
            }
        }
        throw fail(line, "引号未闭合");
    }

    private static String unquote(final String quoted, final Line line) {
        return '\'' == quoted.charAt(0) ? unescapeSingle(quoted, line) : unescapeDouble(quoted, line);
    }

    private static String unescapeDouble(final String raw, final Line line) {
        if (raw.length() < 2 || '"' != raw.charAt(raw.length() - 1)) {
            throw fail(line, "双引号未闭合");
        }
        final StringBuilder out = new StringBuilder(raw.length());
        for (int i = 1; i < raw.length() - 1; i++) {
            final char c = raw.charAt(i);
            if ('\\' != c) {
                out.append(c);
                continue;
            }
            if (i + 1 >= raw.length() - 1) {
                throw fail(line, "转义序列不完整");
            }
            final char e = raw.charAt(++i);
            if ('n' == e) {
                out.append('\n');
            } else if ('t' == e) {
                out.append('\t');
            } else if ('r' == e) {
                out.append('\r');
            } else if ('"' == e || '\\' == e || '\'' == e) {
                out.append(e);
            } else if ('u' == e) {
                if (i + 4 >= raw.length()) {
                    throw fail(line, "\\uXXXX 转义位数不足");
                }
                final String hex = raw.substring(i + 1, i + 5);
                try {
                    out.append((char) Integer.parseInt(hex, 16));
                } catch (final NumberFormatException ex) {
                    throw fail(line, "\\u 转义非十六进制: " + hex);
                }
                i += 4;
            } else {
                throw fail(line, "不支持的转义 \\ 转义符（" + e + "）");
            }
        }
        return out.toString();
    }

    private static String unescapeSingle(final String raw, final Line line) {
        if (raw.length() < 2 || '\'' != raw.charAt(raw.length() - 1)) {
            throw fail(line, "单引号未闭合");
        }
        return raw.substring(1, raw.length() - 1).replace("''", "'");
    }

    private static YamlParseException fail(final Line line, final String reason) {
        final String excerpt = line.text.length() <= 60 ? line.text : line.text.substring(0, 60) + "…";
        return new YamlParseException("第 " + line.number + " 行: " + reason + "（原文: " + excerpt + "）");
    }
}
