package io.github.susongyan.bobapour.config.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * D13 分区边界：RANGE 形态填 {@code upper}（Long 或 String，同表须同型且严格递增），
 * LIST 形态填 {@code values}（跨分区不重叠）。另一形态字段为 null——由类型约定，
 * 加载校验保证不会两可。
 */
public final class PartitionBound {

    private final String name;
    private final Object upper;
    private final List<String> values;

    /** RANGE 边界。 */
    public static PartitionBound range(final String name, final Object upper) {
        return new PartitionBound(name, upper, null);
    }

    /** LIST 边界。 */
    public static PartitionBound list(final String name, final List<String> values) {
        return new PartitionBound(name, null, Collections.unmodifiableList(values));
    }

    private PartitionBound(final String name, final Object upper, final List<String> values) {
        this.name = name;
        this.upper = upper;
        this.values = values;
    }

    public String name() {
        return name;
    }

    /** RANGE 上界（Long 或 String；LIST 形态为 null）。 */
    public Object upper() {
        return upper;
    }

    /** LIST 值清单（RANGE 形态为 null）。 */
    public List<String> values() {
        return values;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PartitionBound)) {
            return false;
        }
        final PartitionBound other = (PartitionBound) obj;
        return Objects.equals(name, other.name) && Objects.equals(upper, other.upper)
                && Objects.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, upper, values);
    }

    @Override
    public String toString() {
        return null != upper ? name + ": <" + upper + "]" : name + ": " + values;
    }
}
