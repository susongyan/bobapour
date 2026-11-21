package io.github.susongyan.bobapour.engine.merge;

import java.util.Collections;
import java.util.List;

import io.github.susongyan.bobapour.config.metadata.DataNode;
import io.github.susongyan.bobapour.config.model.GuardsConfig;

/**
 * 裁决产物（MergePlan，DESIGN §5.2）：形态 × 路由结果 × 表开关 × guards 的唯一裁决出口——
 * 放行（kind + 策略说明 + 目标集合）或拒绝（UnsupportedQueryException，不产出本对象）。
 * 不可变；执行/归并层只消费本对象，不回看 AST。
 */
public final class MergePlan {

    /** 放行策略（三级能力模型的第二/三级出口 + 写路径）。 */
    public enum Kind {
        /** 单分片透传（归并不存在）。 */
        PASSTHROUGH("单分片透传"),
        /** 跨分片拼接。 */
        CONCAT("跨分片拼接"),
        /** 流式归并（优先队列，排序下推）。 */
        STREAM_MERGE("流式归并"),
        /** TopN：下推 offset+limit，外层取窗口。 */
        TOP_N("TopN"),
        /** 聚合重组装：SUM(SUM)/MAX(MAX)…。 */
        REASSEMBLE("聚合重组装"),
        /** UPDATE/DELETE 多分片 = 影响行数求和（D9）。 */
        WRITE_ROW_SUM("写影响行数求和"),
        /** INSERT 按分片键拆行路由（D9）。 */
        WRITE_SPLIT("INSERT拆行"),
        /** 广播表写入（全副本，guards 分片数豁免，D10）。 */
        BROADCAST_WRITE("广播写");

        private final String label;

        Kind(final String label) {
            this.label = label;
        }

        /** 报告用中文标签。 */
        public String label() {
            return label;
        }
    }

    private final Kind kind;
    private final String description;
    private final List<DataNode> targets;
    private final GuardsConfig guards;
    private final int locatedPartitions;
    private final int fallbackPartitions;

    MergePlan(final Kind kind, final String description, final List<DataNode> targets,
            final GuardsConfig guards, final int locatedPartitions, final int fallbackPartitions) {
        this.kind = kind;
        this.description = description;
        this.targets = Collections.unmodifiableList(targets);
        this.guards = guards;
        this.locatedPartitions = locatedPartitions;
        this.fallbackPartitions = fallbackPartitions;
    }

    public Kind kind() {
        return kind;
    }

    /** 策略说明（含运行期注意事项，如动态 LIMIT 窗口）。 */
    public String description() {
        return description;
    }

    /** 目标集合（直通/广播读为空或取样节点，见 description）。 */
    public List<DataNode> targets() {
        return targets;
    }

    public GuardsConfig guards() {
        return guards;
    }

    /** D13 分区定位次数（写路径）。 */
    public int locatedPartitions() {
        return locatedPartitions;
    }

    /** D13 分区回退次数（写路径，回退父表）。 */
    public int fallbackPartitions() {
        return fallbackPartitions;
    }

    @Override
    public String toString() {
        return kind.label() + " → " + targets + (description.isEmpty() ? "" : "（" + description + "）");
    }
}
