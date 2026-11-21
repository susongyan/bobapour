package io.github.susongyan.bobapour.config.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.susongyan.bobapour.config.model.PartitionBound;
import io.github.susongyan.bobapour.config.model.PartitionConfig;
import io.github.susongyan.bobapour.config.model.PartitionType;

/**
 * D13 分区定位黄金用例（T3）：RANGE 上界排他边界归属右段、≥ 末上界与类型不符回退 null；
 * LIST 字符串相等命中、miss 回退 null——不可定位一律 null（回退父表，永不制造错误行为）。
 */
final class PartitionIndexTest {

    private static PartitionIndex rangeIndex() {
        return PartitionIndex.of(new PartitionConfig("created_at", PartitionType.RANGE, Arrays.asList(
                PartitionBound.range("p2025", "2026-01-01"),
                PartitionBound.range("p2026", "2027-01-01"),
                PartitionBound.range("p2027", "2028-01-01"))));
    }

    @Test
    void range_各段命中() {
        final PartitionIndex index = rangeIndex();
        assertEquals("p2025", index.locate("2025-06-01"));
        assertEquals("p2026", index.locate("2026-06-01"));
        assertEquals("p2027", index.locate("2027-06-01"));
    }

    @Test
    void range_上界值为排他边界_归属右段() {
        final PartitionIndex index = rangeIndex();
        assertEquals("p2026", index.locate("2026-01-01"));
        assertEquals("p2027", index.locate("2027-01-01"));
    }

    @Test
    void range_超末上界_回退null() {
        assertNull(rangeIndex().locate("2028-06-01"));
    }

    @Test
    void range_数值型() {
        final PartitionIndex index = PartitionIndex.of(new PartitionConfig("level", PartitionType.RANGE,
                Arrays.asList(PartitionBound.range("low", 100L), PartitionBound.range("high", 200L))));
        assertEquals("low", index.locate(50L));
        assertEquals("high", index.locate(100L));
        assertEquals("high", index.locate(150));
        assertNull(index.locate(200L));
    }

    @Test
    void range_类型不符_回退null() {
        final PartitionIndex index = rangeIndex();
        assertNull(index.locate(20260101L));
        assertNull(index.locate("not-a-number"));
        assertNull(index.locate(null));
        final PartitionIndex numeric = PartitionIndex.of(new PartitionConfig("level", PartitionType.RANGE,
                Arrays.asList(PartitionBound.range("low", 100L))));
        assertNull(numeric.locate("100"));
    }

    @Test
    void list_字符串相等命中() {
        final PartitionIndex index = PartitionIndex.of(new PartitionConfig("region", PartitionType.LIST,
                Arrays.asList(PartitionBound.list("p_north", Arrays.asList("bj", "tj")),
                        PartitionBound.list("p_south", Arrays.asList("sz")))));
        assertEquals("p_north", index.locate("bj"));
        assertEquals("p_north", index.locate("tj"));
        assertEquals("p_south", index.locate("sz"));
        assertNull(index.locate("sh"));
        assertNull(index.locate(null));
    }

    @Test
    void list_数值键按字符串形态命中() {
        final PartitionIndex index = PartitionIndex.of(new PartitionConfig("code", PartitionType.LIST,
                Arrays.asList(PartitionBound.list("p_a", Arrays.asList("1", "2")))));
        assertEquals("p_a", index.locate(1));
        assertEquals("p_a", index.locate("1"));
    }
}
