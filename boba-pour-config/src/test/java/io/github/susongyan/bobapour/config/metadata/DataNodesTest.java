package io.github.susongyan.bobapour.config.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 模式展开黄金用例（T3）：两段笛卡尔展开的序与去重、字面段、数据源视图、
 * 展开上限防线（不可能状态在快照装配层翻译为带路径配置错误——另见 MetadataSnapshotTest）。
 */
final class DataNodesTest {

    @Test
    void 区间乘区间展开_库维在外() {
        final DataNodes nodes = DataNodes.of("ds_${0..1}.t_order_${0..2}");
        assertEquals(6, nodes.size());
        final List<String> flat = new ArrayList<String>();
        for (final DataNode node : nodes.all()) {
            flat.add(node.toString());
        }
        assertEquals("ds_0.t_order_0, ds_0.t_order_1, ds_0.t_order_2, "
                + "ds_1.t_order_0, ds_1.t_order_1, ds_1.t_order_2", String.join(", ", flat));
    }

    @Test
    void 区间不零填充() {
        final DataNodes nodes = DataNodes.of("ds_${10..11}.t");
        assertEquals("ds_10.t, ds_11.t", nodes.all().get(0) + ", " + nodes.all().get(1));
    }

    @Test
    void 字面单节点() {
        final DataNodes nodes = DataNodes.of("ds_0.t_order_item");
        assertEquals(1, nodes.size());
        assertEquals(new DataNode("ds_0", "t_order_item"), nodes.all().get(0));
    }

    @Test
    void 数据源视图去重保序() {
        final DataNodes nodes = DataNodes.of("ds_${0..2}.t_${0..1}");
        assertEquals(6, nodes.size());
        assertEquals("ds_0, ds_1, ds_2", String.join(", ", nodes.datasourceNames()));
        assertTrue(nodes.coversDatasource("ds_1"));
        assertTrue(!nodes.coversDatasource("ds_9"));
    }

    @Test
    void 绑定表数据源布局对齐() {
        assertTrue(DataNodes.of("ds_${0..1}.a_${0..3}").sameDatasourceLayout(DataNodes.of("ds_${0..1}.b_${0..3}")));
        assertTrue(!DataNodes.of("ds_${0..1}.a").sameDatasourceLayout(DataNodes.of("ds_0.a")));
    }

    @Test
    void 拒绝_展开超上限() {
        assertThrows(IllegalStateException.class, () -> DataNodes.of("ds_${0..300}.t_${0..300}"));
    }

    @Test
    void 拒绝_一段模式() {
        assertThrows(IllegalStateException.class, () -> DataNodes.of("only_one_segment"));
    }
}
