package io.github.susongyan.bobapour.engine.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.susongyan.bobapour.config.metadata.DataNode;

/**
 * 路由结果（T4 骨架）：分片维目标集合 + INSERT 逐行目标 + D13 分区定位统计。
 * 目标为空 = 无分片表参与（直通/广播表，由裁决层判定）；不可静态路由（缺键）= 该维全量节点
 * （显式交由裁决层按矩阵/guards 处置，不做静默猜测）。
 */
public final class RouteResult {

    private final Map<String, List<DataNode>> tableTargets;
    private final List<DataNode> targets;
    private final List<List<DataNode>> insertRowTargets;
    private final int locatedPartitions;
    private final int fallbackPartitions;

    RouteResult(final Map<String, List<DataNode>> tableTargets, final List<List<DataNode>> insertRowTargets,
            final int locatedPartitions, final int fallbackPartitions) {
        this.tableTargets = Collections.unmodifiableMap(tableTargets);
        final Set<DataNode> union = new LinkedHashSet<DataNode>();
        for (final List<DataNode> nodes : tableTargets.values()) {
            union.addAll(nodes);
        }
        this.targets = new ArrayList<DataNode>(union);
        this.insertRowTargets = insertRowTargets;
        this.locatedPartitions = locatedPartitions;
        this.fallbackPartitions = fallbackPartitions;
    }

    /** 逐表目标集合（逻辑表 → 物理节点）。 */
    public Map<String, List<DataNode>> tableTargets() {
        return tableTargets;
    }

    /** 全部目标（去重、稳定序）；空 = 无分片目标。 */
    public List<DataNode> targets() {
        return Collections.unmodifiableList(targets);
    }

    /** INSERT 逐行目标（每行单节点，D9 拆行）；非 INSERT 为 null。 */
    public List<List<DataNode>> insertRowTargets() {
        return insertRowTargets;
    }

    /** D13 分区定位次数（写路径，命中声明边界）。 */
    public int locatedPartitions() {
        return locatedPartitions;
    }

    /** D13 分区回退次数（写路径，边界 miss/无键/类型不符 → 回退父表）。 */
    public int fallbackPartitions() {
        return fallbackPartitions;
    }

    /** 唯一目标判定（单分片透传前提）。 */
    public boolean singleTarget() {
        return 1 == targets.size();
    }
}
