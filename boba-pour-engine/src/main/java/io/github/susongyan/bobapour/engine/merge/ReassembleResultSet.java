package io.github.susongyan.bobapour.engine.merge;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import io.github.susongyan.bobapour.engine.execute.QueryFanout;

/**
 * 重组装归并（T7，矩阵"COUNT/SUM/MIN/MAX 无 GROUP BY"格）：每分片恰一行部分聚合，
 * 外层逐投影位合并——SUM(SUM) 累加（全 NULL 分量和 → NULL，部分 NULL 按 0 贡献）、
 * COUNT 求和、MIN/MAX 逐对取胜（胜者保留分片原生值，类型保真）。<b>内存上界 = 常数
 * （单行快照）</b>；合并完成即释放全部分片资源（构造期 eager close，元数据同为值快照
 * ——驱动元数据对象随分片游标关闭失效，H2 等驱动 checkClosed）。
 *
 * <p>重组装行是<b>值快照</b>而非活跃游标：标量取值走快照转换（wasNull 按位），流式/
 * 更新族与 MERGE 游标同样显式拒绝。</p>
 */
public final class ReassembleResultSet extends MergeResultSet {

    private final String[] aggregateNames;
    private final Object[] row;
    private final ResultSetMetaData metaData;
    private boolean consumed;
    private boolean lastWasNull;

    public ReassembleResultSet(final QueryFanout fanout, final List<String> aggregateNames)
            throws SQLException {
        super(fanout);
        this.aggregateNames = aggregateNames.toArray(new String[0]);
        this.row = new Object[this.aggregateNames.length];
        try {
            this.metaData = MetaSnapshot.of(fanout.shards().get(0).resultSet().getMetaData());
            final Object[] merged = new Object[this.aggregateNames.length];
            for (int s = 0; s < fanout.shards().size(); s++) {
                final ResultSet shard = fanout.shards().get(s).resultSet();
                if (!shard.next()) {
                    throw new SQLException("分片聚合查询未返回行（无 GROUP BY 聚合不变量破坏）");
                }
                for (int i = 0; i < this.aggregateNames.length; i++) {
                    merged[i] = merge(this.aggregateNames[i], merged[i], shard.getObject(i + 1));
                }
            }
            System.arraycopy(merged, 0, row, 0, merged.length);
            fanout.close();
        } catch (final SQLException e) {
            try {
                fanout.close();
            } catch (final SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /** 元数据值快照：分片释放后驱动元数据对象随父游标失效（H2 checkClosed），须在关闭前整拷。 */
    private static final class MetaSnapshot implements ResultSetMetaData {

        private final int columns;
        private final String[] catalogName;
        private final String[] columnClassName;
        private final String[] columnLabel;
        private final String[] columnName;
        private final String[] columnTypeName;
        private final String[] schemaName;
        private final String[] tableName;
        private final int[] columnType;
        private final int[] displaySize;
        private final int[] nullable;
        private final int[] precision;
        private final int[] scale;
        private final boolean[] autoIncrement;
        private final boolean[] caseSensitive;
        private final boolean[] currency;
        private final boolean[] definitelyWritable;
        private final boolean[] readOnly;
        private final boolean[] searchable;
        private final boolean[] signed;
        private final boolean[] writable;

        static MetaSnapshot of(final ResultSetMetaData source) throws SQLException {
            final int count = source.getColumnCount();
            final MetaSnapshot out = new MetaSnapshot(count);
            for (int i = 1; i <= count; i++) {
                final int s = i - 1;
                out.catalogName[s] = source.getCatalogName(i);
                out.columnClassName[s] = source.getColumnClassName(i);
                out.columnLabel[s] = source.getColumnLabel(i);
                out.columnName[s] = source.getColumnName(i);
                out.columnTypeName[s] = source.getColumnTypeName(i);
                out.schemaName[s] = source.getSchemaName(i);
                out.tableName[s] = source.getTableName(i);
                out.columnType[s] = source.getColumnType(i);
                out.displaySize[s] = source.getColumnDisplaySize(i);
                out.nullable[s] = source.isNullable(i);
                out.precision[s] = source.getPrecision(i);
                out.scale[s] = source.getScale(i);
                out.autoIncrement[s] = source.isAutoIncrement(i);
                out.caseSensitive[s] = source.isCaseSensitive(i);
                out.currency[s] = source.isCurrency(i);
                out.definitelyWritable[s] = source.isDefinitelyWritable(i);
                out.readOnly[s] = source.isReadOnly(i);
                out.searchable[s] = source.isSearchable(i);
                out.signed[s] = source.isSigned(i);
                out.writable[s] = source.isWritable(i);
            }
            return out;
        }

        private MetaSnapshot(final int columns) {
            this.columns = columns;
            this.catalogName = new String[columns];
            this.columnClassName = new String[columns];
            this.columnLabel = new String[columns];
            this.columnName = new String[columns];
            this.columnTypeName = new String[columns];
            this.schemaName = new String[columns];
            this.tableName = new String[columns];
            this.columnType = new int[columns];
            this.displaySize = new int[columns];
            this.nullable = new int[columns];
            this.precision = new int[columns];
            this.scale = new int[columns];
            this.autoIncrement = new boolean[columns];
            this.caseSensitive = new boolean[columns];
            this.currency = new boolean[columns];
            this.definitelyWritable = new boolean[columns];
            this.readOnly = new boolean[columns];
            this.searchable = new boolean[columns];
            this.signed = new boolean[columns];
            this.writable = new boolean[columns];
        }

        private int check(final int column) throws SQLException {
            if (1 > column || columns < column) {
                throw new SQLDataException("列序越界: " + column);
            }
            return column - 1;
        }

        @Override
        public int getColumnCount() {
            return columns;
        }

        @Override
        public String getCatalogName(final int column) throws SQLException {
            return catalogName[check(column)];
        }

        @Override
        public String getColumnClassName(final int column) throws SQLException {
            return columnClassName[check(column)];
        }

        @Override
        public String getColumnLabel(final int column) throws SQLException {
            return columnLabel[check(column)];
        }

        @Override
        public String getColumnName(final int column) throws SQLException {
            return columnName[check(column)];
        }

        @Override
        public String getColumnTypeName(final int column) throws SQLException {
            return columnTypeName[check(column)];
        }

        @Override
        public String getSchemaName(final int column) throws SQLException {
            return schemaName[check(column)];
        }

        @Override
        public String getTableName(final int column) throws SQLException {
            return tableName[check(column)];
        }

        @Override
        public int getColumnType(final int column) throws SQLException {
            return columnType[check(column)];
        }

        @Override
        public int getColumnDisplaySize(final int column) throws SQLException {
            return displaySize[check(column)];
        }

        @Override
        public int isNullable(final int column) throws SQLException {
            return nullable[check(column)];
        }

        @Override
        public int getPrecision(final int column) throws SQLException {
            return precision[check(column)];
        }

        @Override
        public int getScale(final int column) throws SQLException {
            return scale[check(column)];
        }

        @Override
        public boolean isAutoIncrement(final int column) throws SQLException {
            return autoIncrement[check(column)];
        }

        @Override
        public boolean isCaseSensitive(final int column) throws SQLException {
            return caseSensitive[check(column)];
        }

        @Override
        public boolean isCurrency(final int column) throws SQLException {
            return currency[check(column)];
        }

        @Override
        public boolean isDefinitelyWritable(final int column) throws SQLException {
            return definitelyWritable[check(column)];
        }

        @Override
        public boolean isReadOnly(final int column) throws SQLException {
            return readOnly[check(column)];
        }

        @Override
        public boolean isSearchable(final int column) throws SQLException {
            return searchable[check(column)];
        }

        @Override
        public boolean isSigned(final int column) throws SQLException {
            return signed[check(column)];
        }

        @Override
        public boolean isWritable(final int column) throws SQLException {
            return writable[check(column)];
        }

        @Override
        public <T> T unwrap(final Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLFeatureNotSupportedException("元数据快照不包装: " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(final Class<?> iface) {
            return iface.isInstance(this);
        }
    }

    private Object merge(final String aggregate, final Object champion, final Object candidate)
            throws SQLException {
        if ("COUNT".equals(aggregate)) {
            final long count = (null == champion ? 0L : ((Number) champion).longValue())
                    + (null == candidate ? 0L : ((Number) candidate).longValue());
            return Long.valueOf(count);
        }
        if ("SUM".equals(aggregate)) {
            if (null == candidate) {
                return champion;
            }
            if (null == champion) {
                return candidate instanceof Number ? MergeValues.toBigDecimal((Number) candidate) : candidate;
            }
            if (champion instanceof Number && candidate instanceof Number) {
                return MergeValues.toBigDecimal((Number) champion)
                        .add(MergeValues.toBigDecimal((Number) candidate));
            }
            throw new SQLException("SUM 重组装遇到非数值分片值: "
                    + (null == champion ? candidate : champion).getClass().getName());
        }
        return MergeValues.pick("MAX".equals(aggregate), champion, candidate);
    }

    @Override
    public boolean next() {
        if (consumed) {
            afterLast = true;
            return false;
        }
        consumed = true;
        yielded();
        return true;
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return metaData;
    }

    /** 列名解析走缓存元数据（分片已释放，不等底层游标）。 */
    @Override
    public int findColumn(final String columnLabel) throws SQLException {
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            if (metaData.getColumnLabel(i).equalsIgnoreCase(columnLabel)) {
                return i;
            }
        }
        throw new SQLDataException("列名不存在: " + columnLabel);
    }

    // ---------- 快照取值（wasNull 按位） ----------

    private Object value(final int columnIndex) throws SQLException {
        if (!consumed || afterLast) {
            throw new SQLDataException("游标不在有效行上（TYPE_FORWARD_ONLY，行外无值可读）");
        }
        if (columnIndex < 1 || columnIndex > row.length) {
            throw new SQLDataException("列序越界: " + columnIndex);
        }
        lastWasNull = null == row[columnIndex - 1];
        return row[columnIndex - 1];
    }

    @Override
    public boolean wasNull() {
        return lastWasNull;
    }

    @Override
    public Object getObject(final int columnIndex) throws SQLException {
        return value(columnIndex);
    }

    @Override
    public Object getObject(final String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public String getString(final int columnIndex) throws SQLException {
        final Object v = value(columnIndex);
        return null == v ? null : v.toString();
    }

    @Override
    public String getString(final String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(final int columnIndex) throws SQLException {
        final Object v = value(columnIndex);
        if (null == v) {
            return false;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof Number) {
            return 0 != ((Number) v).doubleValue();
        }
        throw typeMismatch(columnIndex, v, "BOOLEAN");
    }

    @Override
    public boolean getBoolean(final String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(final int columnIndex) throws SQLException {
        return number(columnIndex).byteValue();
    }

    @Override
    public byte getByte(final String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(final int columnIndex) throws SQLException {
        return number(columnIndex).shortValue();
    }

    @Override
    public short getShort(final String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(final int columnIndex) throws SQLException {
        return number(columnIndex).intValue();
    }

    @Override
    public int getInt(final String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(final int columnIndex) throws SQLException {
        return number(columnIndex).longValue();
    }

    @Override
    public long getLong(final String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(final int columnIndex) throws SQLException {
        return number(columnIndex).floatValue();
    }

    @Override
    public float getFloat(final String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(final int columnIndex) throws SQLException {
        return number(columnIndex).doubleValue();
    }

    @Override
    public double getDouble(final String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(final int columnIndex) throws SQLException {
        final Object v = value(columnIndex);
        if (null == v) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return MergeValues.toBigDecimal((Number) v);
        }
        throw typeMismatch(columnIndex, v, "DECIMAL");
    }

    @Override
    public BigDecimal getBigDecimal(final String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Deprecated
    @Override
    public BigDecimal getBigDecimal(final int columnIndex, final int scale) throws SQLException {
        final BigDecimal v = getBigDecimal(columnIndex);
        return null == v ? null : v.setScale(scale, BigDecimal.ROUND_HALF_UP);
    }

    @Deprecated
    @Override
    public BigDecimal getBigDecimal(final String columnLabel, final int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public byte[] getBytes(final int columnIndex) throws SQLException {
        final Object v = value(columnIndex);
        if (null == v || v instanceof byte[]) {
            return (byte[]) v;
        }
        throw typeMismatch(columnIndex, v, "BINARY");
    }

    @Override
    public byte[] getBytes(final String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(final int columnIndex) throws SQLException {
        final Object v = value(columnIndex);
        if (null == v || v instanceof Date) {
            return (Date) v;
        }
        throw typeMismatch(columnIndex, v, "DATE");
    }

    @Override
    public Date getDate(final String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Time getTime(final int columnIndex) throws SQLException {
        final Object v = value(columnIndex);
        if (null == v || v instanceof Time) {
            return (Time) v;
        }
        throw typeMismatch(columnIndex, v, "TIME");
    }

    @Override
    public Time getTime(final String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(final int columnIndex) throws SQLException {
        final Object v = value(columnIndex);
        if (null == v || v instanceof Timestamp) {
            return (Timestamp) v;
        }
        throw typeMismatch(columnIndex, v, "TIMESTAMP");
    }

    @Override
    public Timestamp getTimestamp(final String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public String getNString(final int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public String getNString(final String columnLabel) throws SQLException {
        return getString(columnLabel);
    }

    private Number number(final int columnIndex) throws SQLException {
        final Object v = value(columnIndex);
        if (null == v) {
            return Integer.valueOf(0);
        }
        if (v instanceof Number) {
            return (Number) v;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue() ? Integer.valueOf(1) : Integer.valueOf(0);
        }
        throw typeMismatch(columnIndex, v, "数值");
    }

    private SQLDataException typeMismatch(final int columnIndex, final Object v, final String target) {
        lastWasNull = false;
        return new SQLDataException("重组装快照值类型不匹配: 列=" + columnIndex + " 值类型="
                + v.getClass().getName() + " 目标=" + target);
    }

    // ---------- 快照不适用的取值形态（显式拒绝） ----------

    private SQLFeatureNotSupportedException snapshot() {
        return new SQLFeatureNotSupportedException("重组装行为值快照（流式/对象族取值不支持）");
    }

    @Override
    public InputStream getAsciiStream(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public InputStream getAsciiStream(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Deprecated
    @Override
    public InputStream getUnicodeStream(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Deprecated
    @Override
    public InputStream getUnicodeStream(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public InputStream getBinaryStream(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public InputStream getBinaryStream(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public Reader getCharacterStream(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public Reader getCharacterStream(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public Reader getNCharacterStream(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public Reader getNCharacterStream(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public Ref getRef(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public Ref getRef(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public Blob getBlob(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public Blob getBlob(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public Clob getClob(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public Clob getClob(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public Array getArray(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public Array getArray(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public NClob getNClob(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public NClob getNClob(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public <T> T getObject(final int columnIndex, final Class<T> type) throws SQLException {
        throw snapshot();
    }

    @Override
    public <T> T getObject(final String columnLabel, final Class<T> type) throws SQLException {
        throw snapshot();
    }

    @Override
    public Object getObject(final int columnIndex, final Map<String, Class<?>> map) throws SQLException {
        throw snapshot();
    }

    @Override
    public Object getObject(final String columnLabel, final Map<String, Class<?>> map) throws SQLException {
        throw snapshot();
    }

    @Override
    public java.net.URL getURL(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public java.net.URL getURL(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public RowId getRowId(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public RowId getRowId(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public java.sql.SQLXML getSQLXML(final int columnIndex) throws SQLException {
        throw snapshot();
    }

    @Override
    public java.sql.SQLXML getSQLXML(final String columnLabel) throws SQLException {
        throw snapshot();
    }

    @Override
    public Date getDate(final int columnIndex, final java.util.Calendar cal) throws SQLException {
        throw snapshot();
    }

    @Override
    public Date getDate(final String columnLabel, final java.util.Calendar cal) throws SQLException {
        throw snapshot();
    }

    @Override
    public Time getTime(final int columnIndex, final java.util.Calendar cal) throws SQLException {
        throw snapshot();
    }

    @Override
    public Time getTime(final String columnLabel, final java.util.Calendar cal) throws SQLException {
        throw snapshot();
    }

    @Override
    public Timestamp getTimestamp(final int columnIndex, final java.util.Calendar cal) throws SQLException {
        throw snapshot();
    }

    @Override
    public Timestamp getTimestamp(final String columnLabel, final java.util.Calendar cal) throws SQLException {
        throw snapshot();
    }
}
