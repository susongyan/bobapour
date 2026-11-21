package io.github.susongyan.bobapour.config.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.susongyan.bobapour.config.model.PartitionBound;
import io.github.susongyan.bobapour.config.model.PartitionConfig;
import io.github.susongyan.bobapour.config.model.PartitionType;

/**
 * D13 分区边界的运行时索引：由 {@link PartitionConfig}（加载期已校验同型/递增/不重叠）构建一次、只读。
 * locate 语义——RANGE：值落 [前上界, 本上界) 命中本分区（上界值为排他边界，归属右段）；
 * 值 ≥ 末上界、类型与上界不同型（Long 上界遇非数值 / String 上界遇非字符串）、入参 null → 一律返回 null
 * （D13 铁律：不可定位即回退父表，永不制造错误行为）。LIST：值的字符串形态与声明值相等命中，
 * miss → null。RANGE 查找为二分（分区数典型为逐月/逐年滚动，量级 ≤ 千）。
 */
public final class PartitionIndex {

    private final String key;
    private final PartitionType type;
    private final List<String> names;
    private final List<Object> uppers;
    private final Map<String, String> listIndex;

    private PartitionIndex(final String key, final PartitionType type, final List<String> names,
            final List<Object> uppers, final Map<String, String> listIndex) {
        this.key = key;
        this.type = type;
        this.names = names;
        this.uppers = uppers;
        this.listIndex = listIndex;
    }

    /** 由已校验配置构建索引；RANGE 提取上界序列，LIST 构建值→分区映射。 */
    public static PartitionIndex of(final PartitionConfig config) {
        final List<String> names = new ArrayList<String>(config.partitions().size());
        if (PartitionType.RANGE == config.type()) {
            final List<Object> uppers = new ArrayList<Object>(config.partitions().size());
            for (final PartitionBound bound : config.partitions()) {
                names.add(bound.name());
                uppers.add(bound.upper());
            }
            return new PartitionIndex(config.key(), PartitionType.RANGE,
                    Collections.unmodifiableList(names), Collections.unmodifiableList(uppers), null);
        }
        final Map<String, String> index = new HashMap<String, String>();
        for (final PartitionBound bound : config.partitions()) {
            names.add(bound.name());
            for (final String value : bound.values()) {
                index.put(value, bound.name());
            }
        }
        return new PartitionIndex(config.key(), PartitionType.LIST,
                Collections.unmodifiableList(names), null, Collections.unmodifiableMap(index));
    }

    public String key() {
        return key;
    }

    public PartitionType type() {
        return type;
    }

    /** 分区名清单（声明序，RANGE 即边界序）。 */
    public List<String> names() {
        return names;
    }

    /** 定位分区名；不可定位返回 null（回退父表）。 */
    public String locate(final Object value) {
        if (null == value) {
            return null;
        }
        if (PartitionType.LIST == type) {
            return listIndex.get(String.valueOf(value));
        }
        final boolean longBounds = uppers.get(0) instanceof Long;
        if (longBounds ? !(value instanceof Number) : !(value instanceof String)) {
            return null;
        }
        int lo = 0;
        int hi = uppers.size() - 1;
        int hit = -1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (compare(uppers.get(mid), value) > 0) {
                hit = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return hit < 0 ? null : names.get(hit);
    }

    private static int compare(final Object upper, final Object value) {
        if (upper instanceof Long && value instanceof Number) {
            return Long.compare(((Long) upper).longValue(), ((Number) value).longValue());
        }
        return ((String) upper).compareTo((String) value);
    }
}
