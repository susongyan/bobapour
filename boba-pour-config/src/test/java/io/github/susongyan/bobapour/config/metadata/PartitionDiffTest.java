package io.github.susongyan.bobapour.config.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.susongyan.bobapour.config.model.PartitionBound;
import io.github.susongyan.bobapour.config.model.PartitionConfig;
import io.github.susongyan.bobapour.config.model.PartitionType;

/**
 * D13 运维校验（配置 vs catalog diff）黄金用例（T3）：全等空报告；分区名双向差集；
 * RANGE 上界文本/数值两型比较；LIST 值集双向差集；形态不一致如实报告——工具只报告不动手。
 */
final class PartitionDiffTest {

    private static PartitionConfig declaredRange() {
        return new PartitionConfig("created_at", PartitionType.RANGE, Arrays.asList(
                PartitionBound.range("p2025", "2026-01-01"),
                PartitionBound.range("p2026", "2027-01-01")));
    }

    @Test
    void range_全等_空报告() {
        final List<String> findings = PartitionDiff.diff("t_order", declaredRange(), Arrays.asList(
                CatalogPartition.range("p2025", "2026-01-01"),
                CatalogPartition.range("p2026", "2027-01-01")));
        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    void 分区名双向差集() {
        final List<String> findings = PartitionDiff.diff("t_order", declaredRange(), Arrays.asList(
                CatalogPartition.range("p2026", "2027-01-01"),
                CatalogPartition.range("p2028", "2029-01-01")));
        assertEquals(2, findings.size());
        assertTrue(findings.get(0).contains("p2025") && findings.get(0).contains("库内不存在"));
        assertTrue(findings.get(1).contains("p2028") && findings.get(1).contains("配置未声明"));
    }

    @Test
    void range_上界文本不一致() {
        final List<String> findings = PartitionDiff.diff("t_order", declaredRange(), Arrays.asList(
                CatalogPartition.range("p2025", "2026-01-01"),
                CatalogPartition.range("p2026", "2027-01-01 00:00:00")));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).contains("上界不一致"), findings.get(0));
    }

    @Test
    void range_数值上界与不可解析文本() {
        final PartitionConfig declared = new PartitionConfig("level", PartitionType.RANGE,
                Arrays.asList(PartitionBound.range("low", 100L), PartitionBound.range("high", 200L)));
        assertEquals(0, PartitionDiff.diff("t", declared, Arrays.asList(
                CatalogPartition.range("low", "100"), CatalogPartition.range("high", " 200 "))).size());
        final List<String> findings = PartitionDiff.diff("t", declared, Arrays.asList(
                CatalogPartition.range("low", "abc"), CatalogPartition.range("high", "200")));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).contains("不可比"), findings.get(0));
    }

    @Test
    void list_值集双向差集() {
        final PartitionConfig declared = new PartitionConfig("region", PartitionType.LIST,
                Arrays.asList(PartitionBound.list("p_north", Arrays.asList("bj", "tj"))));
        final List<String> findings = PartitionDiff.diff("t", declared, Arrays.asList(
                CatalogPartition.list("p_north", "bj", "sh")));
        assertEquals(2, findings.size());
        assertTrue(findings.get(0).contains("'tj'") && findings.get(0).contains("库内不存在"));
        assertTrue(findings.get(1).contains("'sh'") && findings.get(1).contains("配置未声明"));
    }

    @Test
    void 形态不一致() {
        final PartitionConfig declared = new PartitionConfig("region", PartitionType.LIST,
                Arrays.asList(PartitionBound.list("p_north", Arrays.asList("bj"))));
        final List<String> findings = PartitionDiff.diff("t", declared,
                new ArrayList<CatalogPartition>(
                        Arrays.asList(CatalogPartition.range("p_north", "100"))));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).contains("形态不一致"), findings.get(0));
    }
}
