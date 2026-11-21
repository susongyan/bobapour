package io.github.susongyan.bobapour.config.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.susongyan.bobapour.config.model.PartitionBound;
import io.github.susongyan.bobapour.config.model.PartitionConfig;

/**
 * D13 运维校验（库形态）：配置声明边界 vs 库内 catalog 事实 → 人读 diff 报告行列表，空 = 一致。
 * 纯内存比对、不连库——库内事实由巡检命令以 {@link CatalogPartition} 喂入，报告供人审处置
 * （修配置或修库，工具只报告不动手，v1.9 ADR）。比对规则：
 * 分区名双向差集；RANGE 上界按声明型别比较（Long 声明侧文本需可解析为数值，String 直接文本相等，
 * 库内日期时间带尾缀等形态差都会如实报告——这正是巡检要暴露的）；LIST 值集双向差集。
 */
public final class PartitionDiff {

    private PartitionDiff() {
    }

    /**
     * 比对一张表的分区声明与库内事实。
     *
     * @param logicalTable 逻辑表名（仅用于报告行前缀）
     * @param declared 配置声明（已过加载校验）
     * @param actual 库内巡检事实
     * @return 报告行列表；空列表 = 完全一致
     */
    public static List<String> diff(final String logicalTable, final PartitionConfig declared,
            final List<CatalogPartition> actual) {
        final List<String> findings = new ArrayList<String>();
        final Map<String, PartitionBound> declaredByName = new TreeMap<String, PartitionBound>();
        for (final PartitionBound bound : declared.partitions()) {
            declaredByName.put(bound.name(), bound);
        }
        final Map<String, CatalogPartition> actualByName = new TreeMap<String, CatalogPartition>();
        for (final CatalogPartition partition : actual) {
            actualByName.put(partition.name(), partition);
        }
        final Set<String> onlyDeclared = new TreeSet<String>(declaredByName.keySet());
        onlyDeclared.removeAll(actualByName.keySet());
        for (final String name : onlyDeclared) {
            findings.add(logicalTable + " 分区 " + name + "：配置已声明，库内不存在");
        }
        final Set<String> onlyActual = new TreeSet<String>(actualByName.keySet());
        onlyActual.removeAll(declaredByName.keySet());
        for (final String name : onlyActual) {
            findings.add(logicalTable + " 分区 " + name + "：库内存在，配置未声明");
        }
        for (final Map.Entry<String, PartitionBound> entry : declaredByName.entrySet()) {
            final CatalogPartition catalog = actualByName.get(entry.getKey());
            if (null == catalog) {
                continue;
            }
            diffPartition(logicalTable, entry.getKey(), entry.getValue(), catalog, findings);
        }
        return findings;
    }

    private static void diffPartition(final String logicalTable, final String name,
            final PartitionBound declared, final CatalogPartition actual, final List<String> findings) {
        if (null != declared.upper() && null != actual.upper()) {
            diffRange(logicalTable, name, declared.upper(), actual.upper(), findings);
        } else if (null != declared.values() && null != actual.values()) {
            diffList(logicalTable, name, declared.values(), actual.values(), findings);
        } else {
            findings.add(logicalTable + " 分区 " + name + "：形态不一致（配置 "
                    + (null != declared.upper() ? "RANGE" : "LIST") + "，库内 "
                    + (null != actual.upper() ? "RANGE" : "LIST") + "）");
        }
    }

    private static void diffRange(final String logicalTable, final String name, final Object declaredUpper,
            final String actualUpper, final List<String> findings) {
        if (declaredUpper instanceof Long) {
            final Long parsed;
            try {
                parsed = Long.valueOf(actualUpper.trim());
            } catch (final NumberFormatException e) {
                findings.add(logicalTable + " 分区 " + name + "：上界形态不可比（配置数值 " + declaredUpper
                        + "，库内文本 '" + actualUpper + "'）");
                return;
            }
            if (0 != Long.compare(((Long) declaredUpper).longValue(), parsed.longValue())) {
                findings.add(logicalTable + " 分区 " + name + "：上界不一致（配置 " + declaredUpper
                        + "，库内 " + parsed + "）");
            }
            return;
        }
        if (!declaredUpper.equals(actualUpper)) {
            findings.add(logicalTable + " 分区 " + name + "：上界不一致（配置 '" + declaredUpper
                    + "'，库内 '" + actualUpper + "'）");
        }
    }

    private static void diffList(final String logicalTable, final String name, final List<String> declaredValues,
            final List<String> actualValues, final List<String> findings) {
        final Set<String> declared = new TreeSet<String>(declaredValues);
        final Set<String> catalog = new TreeSet<String>(actualValues);
        final Set<String> missing = new TreeSet<String>(declared);
        missing.removeAll(catalog);
        for (final String value : missing) {
            findings.add(logicalTable + " 分区 " + name + "：值 '" + value + "' 配置已声明，库内不存在");
        }
        final Set<String> extra = new TreeSet<String>(catalog);
        extra.removeAll(declared);
        for (final String value : extra) {
            findings.add(logicalTable + " 分区 " + name + "：值 '" + value + "' 库内存在，配置未声明");
        }
    }
}
