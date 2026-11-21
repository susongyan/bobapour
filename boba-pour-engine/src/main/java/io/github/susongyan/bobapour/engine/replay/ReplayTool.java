package io.github.susongyan.bobapour.engine.replay;

import java.util.List;

import io.github.susongyan.bobachop.dialect.DialectProfile;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.engine.DecisionChain;
import io.github.susongyan.bobapour.engine.merge.MergePlan;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * 语料回放工具（T4 DoD，对拍三关之关 1）：逐条跑决策链（解析→绑定→路由→MergePlan 裁决），
 * 产出落格分布/拒绝清单/D13 分区分布——零执行零成本、不需要真实库（DESIGN §9）。
 * 生产实例语料走同一链路，回放在内网执行（语料不出内网）。
 */
public final class ReplayTool {

    private ReplayTool() {
    }

    /** MySQL 方言回放（等价 {@code run(cases, snapshot, DialectProfile.MYSQL)}）。 */
    public static ReplayReport run(final List<ReplayCase> cases, final MetadataSnapshot snapshot) {
        return run(cases, snapshot, DialectProfile.MYSQL);
    }

    /** 指定方言回放：逐条裁决，放行计落格、拒绝计清单，全部条目必有归宿。 */
    public static ReplayReport run(final List<ReplayCase> cases, final MetadataSnapshot snapshot,
            final DialectProfile profile) {
        final ReplayReport report = new ReplayReport(cases.size());
        for (final ReplayCase item : cases) {
            try {
                final MergePlan plan = DecisionChain.decide(item.sql(), snapshot, profile);
                report.tally(plan.kind().label());
                report.partitions(plan.locatedPartitions(), plan.fallbackPartitions());
            } catch (final UnsupportedQueryException e) {
                report.reject("[" + item.id() + "] " + e.construct() + " → " + e.rule()
                        + (e.detail().isEmpty() ? "" : "｜" + e.detail()) + "｜" + item.sql());
            }
        }
        return report;
    }
}
