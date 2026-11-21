package io.github.susongyan.bobapour.engine;

import io.github.susongyan.bobachop.api.BobaChop;
import io.github.susongyan.bobachop.ast.Statement;
import io.github.susongyan.bobachop.dialect.DialectProfile;
import io.github.susongyan.bobachop.exception.LexException;
import io.github.susongyan.bobachop.exception.ParseException;
import io.github.susongyan.bobachop.exception.UnsupportedSyntaxException;
import io.github.susongyan.bobapour.config.metadata.MetadataSnapshot;
import io.github.susongyan.bobapour.engine.bind.Binder;
import io.github.susongyan.bobapour.engine.bind.BoundStatement;
import io.github.susongyan.bobapour.engine.merge.MergePlan;
import io.github.susongyan.bobapour.engine.merge.MergePlanner;
import io.github.susongyan.bobapour.engine.route.RouteResult;
import io.github.susongyan.bobapour.engine.route.Router;
import io.github.susongyan.bobapour.exception.UnsupportedQueryException;

/**
 * 决策链门面（T4）：解析 → 绑定 → 路由 → MergePlan 裁决，单入口单条 SQL。
 * 全链纯内存零 IO、无锁（只读快照）；裁决点唯一在 MergePlanner（DESIGN §5.2）。
 * 解析层白名单外/语法错误包装为 {@link UnsupportedQueryException}（构造名带"解析"前缀分流），
 * 决策链因此对外只有一种失败形态——执行前显式拒绝。
 */
public final class DecisionChain {

    private DecisionChain() {
    }

    /** MySQL 方言裁决（等价 {@code decide(sql, snapshot, DialectProfile.MYSQL)}）。 */
    public static MergePlan decide(final String sql, final MetadataSnapshot snapshot) {
        return decide(sql, snapshot, DialectProfile.MYSQL);
    }

    /** 指定方言裁决：放行返回 MergePlan；拒绝抛 UnsupportedQueryException。 */
    public static MergePlan decide(final String sql, final MetadataSnapshot snapshot,
            final DialectProfile profile) {
        final Statement statement;
        try {
            statement = BobaChop.parse(sql, profile);
        } catch (final UnsupportedSyntaxException e) {
            throw new UnsupportedQueryException("解析白名单", -1,
                    "白名单外构造（内核 UnsupportedSyntaxException）", e.getMessage());
        } catch (final ParseException e) {
            throw new UnsupportedQueryException("解析语法", -1, "语法错误（内核 ParseException）", e.getMessage());
        } catch (final LexException e) {
            throw new UnsupportedQueryException("解析词法", -1, "词法错误（内核 LexException）", e.getMessage());
        }
        final BoundStatement bound = Binder.bind(statement, snapshot);
        final RouteResult route = Router.route(bound, snapshot);
        return MergePlanner.plan(bound, route, snapshot);
    }
}
