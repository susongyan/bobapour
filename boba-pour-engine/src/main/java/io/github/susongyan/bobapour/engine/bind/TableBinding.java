package io.github.susongyan.bobapour.engine.bind;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.susongyan.bobapour.config.metadata.TableSnapshot;

/**
 * 单表绑定结果（T4 骨架）：快照引用 + 从语句提取的键值。提取面（诚实边界）——
 * WHERE/ON 合取式中 {@code col = 字面量} 与 {@code col IN (字面量...)}；
 * INSERT 的列清单 × VALUES 行（逐行全列值）。参数标记（{@code ?}）、标量函数、
 * 跨列运算不产生键值（按缺键=该维全路由处理，交由裁决层按矩阵/guards 决定）。
 * 值形态：数值字面量 → {@link Long}，字符串字面量 → 去引号 {@link String}（与
 * {@code PartitionIndex.locate} 输入约定一致）。
 */
public final class TableBinding {

    private final TableSnapshot table;
    private final List<Object> databaseKeyValues;
    private final List<Object> tableKeyValues;
    private final List<Object> partitionKeyValues;
    private final List<Map<String, Object>> insertRows;

    TableBinding(final TableSnapshot table, final List<Object> databaseKeyValues,
            final List<Object> tableKeyValues, final List<Object> partitionKeyValues,
            final List<Map<String, Object>> insertRows) {
        this.table = Objects.requireNonNull(table, "table");
        this.databaseKeyValues = safe(databaseKeyValues);
        this.tableKeyValues = safe(tableKeyValues);
        this.partitionKeyValues = safe(partitionKeyValues);
        this.insertRows = null == insertRows ? null : Collections.unmodifiableList(insertRows);
    }

    private static List<Object> safe(final List<Object> values) {
        return null == values ? Collections.<Object>emptyList() : Collections.unmodifiableList(values);
    }

    public TableSnapshot table() {
        return table;
    }

    /** 库维分片键值集（等值+IN 累积，含重复不去重——路由自行聚合）；空 = 未提取到（该维全路由）。 */
    public List<Object> databaseKeyValues() {
        return databaseKeyValues;
    }

    /** 表维分片键值集；空 = 未提取到。 */
    public List<Object> tableKeyValues() {
        return tableKeyValues;
    }

    /** 分区键值集（D13，仅声明分区的表非空）。 */
    public List<Object> partitionKeyValues() {
        return partitionKeyValues;
    }

    /** INSERT 逐行全列值（列名→值）；非 INSERT 为 null。多行 insert-select 见 BoundStatement.shape。 */
    public List<Map<String, Object>> insertRows() {
        return insertRows;
    }
}
