package io.github.susongyan.bobapour.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

import io.github.susongyan.bobapour.engine.execute.Executor;

/**
 * 分片引擎模式预编译语句（T7）：<b>标量参数录制 + 每单元回放</b>——参数按绑定序录制成
 * 值快照，执行时每个 fan-out 单元独立 {@code prepare + 重放标量 + 执行}；路由裁决用
 * 绑定区间全量（含参数区间），分片键落在参数上的写路径由 Router 显式拒绝（D9），读路径
 * 参数回放即全区间扇出。
 *
 * <p><b>回放边界（诚实拒绝）</b>：流（InputStream/Reader）、LOB/RowId/SQLXML/Array/Ref
 * 等一次性对象不可跨单元重放——录制的值快照对每个单元是同一引用，流无法二次消费，
 * 显式拒绝且指明替代路径（直通模式或非 LOB 标量化）。日期带 Calendar 的录制成
 * {@link DatedParam} 保全时区语义。{@code setObject} 未指定目标类型的按运行时类型收敛。
 * 未设置的参数位不代填，各分片原生驱动按其契约报缺参错误。</p>
 */
final class ShardedPreparedStatement extends ShardedStatement implements PreparedStatement {

    private final String sql;
    /** 参数值快照（1 基位录到 0 基数组；null 槽 = 未设置，显式 setNull 录 NullParam 哨兵）。 */
    private Object[] params = new Object[4];

    ShardedPreparedStatement(final ShardedConnection connection, final String sql) {
        super(connection);
        this.sql = sql;
    }

    // ---------- 标量参数录制 ----------

    @Override
    public void setNull(final int index, final int sqlType) throws SQLException {
        set(index, new NullParam(sqlType, null));
    }

    @Override
    public void setNull(final int index, final int sqlType, final String typeName) throws SQLException {
        set(index, new NullParam(sqlType, typeName));
    }

    @Override
    public void setBoolean(final int index, final boolean value) throws SQLException {
        set(index, Boolean.valueOf(value));
    }

    @Override
    public void setByte(final int index, final byte value) throws SQLException {
        set(index, Byte.valueOf(value));
    }

    @Override
    public void setShort(final int index, final short value) throws SQLException {
        set(index, Short.valueOf(value));
    }

    @Override
    public void setInt(final int index, final int value) throws SQLException {
        set(index, Integer.valueOf(value));
    }

    @Override
    public void setLong(final int index, final long value) throws SQLException {
        set(index, Long.valueOf(value));
    }

    @Override
    public void setFloat(final int index, final float value) throws SQLException {
        set(index, Float.valueOf(value));
    }

    @Override
    public void setDouble(final int index, final double value) throws SQLException {
        set(index, Double.valueOf(value));
    }

    @Override
    public void setBigDecimal(final int index, final BigDecimal value) throws SQLException {
        set(index, value);
    }

    @Override
    public void setString(final int index, final String value) throws SQLException {
        set(index, value);
    }

    @Override
    public void setBytes(final int index, final byte[] value) throws SQLException {
        set(index, value);
    }

    @Override
    public void setDate(final int index, final Date value) throws SQLException {
        set(index, value);
    }

    @Override
    public void setTime(final int index, final Time value) throws SQLException {
        set(index, value);
    }

    @Override
    public void setTimestamp(final int index, final Timestamp value) throws SQLException {
        set(index, value);
    }

    @Override
    public void setDate(final int index, final Date value, final Calendar calendar) throws SQLException {
        set(index, new DatedParam(value, calendar));
    }

    @Override
    public void setTime(final int index, final Time value, final Calendar calendar) throws SQLException {
        set(index, new DatedParam(value, calendar));
    }

    @Override
    public void setTimestamp(final int index, final Timestamp value, final Calendar calendar)
            throws SQLException {
        set(index, new DatedParam(value, calendar));
    }

    @Override
    public void setObject(final int index, final Object value) throws SQLException {
        if (value instanceof Array || value instanceof Blob || value instanceof Clob
                || value instanceof NClob || value instanceof Ref || value instanceof RowId
                || value instanceof SQLXML) {
            throw unreplayable("对象参数");
        }
        set(index, value);
    }

    @Override
    public void setObject(final int index, final Object value, final int targetSqlType)
            throws SQLException {
        set(index, new TypedParam(value, targetSqlType, -1));
    }

    @Override
    public void setObject(final int index, final Object value, final int targetSqlType,
            final int scaleOrLength) throws SQLException {
        set(index, new TypedParam(value, targetSqlType, scaleOrLength));
    }

    @Override
    public void setURL(final int index, final URL value) throws SQLException {
        set(index, value);
    }

    @Override
    public void clearParameters() {
        params = new Object[4];
    }

    // ---------- 不可回放面（显式拒绝） ----------

    @Override
    public void setAsciiStream(final int index, final InputStream value, final int length)
            throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setAsciiStream(final int index, final InputStream value, final long length)
            throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setAsciiStream(final int index, final InputStream value) throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setUnicodeStream(final int index, final InputStream value, final int length)
            throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setBinaryStream(final int index, final InputStream value, final int length)
            throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setBinaryStream(final int index, final InputStream value, final long length)
            throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setBinaryStream(final int index, final InputStream value) throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setCharacterStream(final int index, final Reader value, final int length)
            throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setCharacterStream(final int index, final Reader value, final long length)
            throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setCharacterStream(final int index, final Reader value) throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setNCharacterStream(final int index, final Reader value, final long length)
            throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setNCharacterStream(final int index, final Reader value) throws SQLException {
        throw unreplayable("流参数");
    }

    @Override
    public void setNString(final int index, final String value) throws SQLException {
        set(index, value);
    }

    @Override
    public void setBlob(final int index, final Blob value) throws SQLException {
        throw unreplayable("LOB 参数");
    }

    @Override
    public void setBlob(final int index, final InputStream value, final long length)
            throws SQLException {
        throw unreplayable("LOB 参数");
    }

    @Override
    public void setBlob(final int index, final InputStream value) throws SQLException {
        throw unreplayable("LOB 参数");
    }

    @Override
    public void setClob(final int index, final Clob value) throws SQLException {
        throw unreplayable("LOB 参数");
    }

    @Override
    public void setClob(final int index, final Reader value, final long length) throws SQLException {
        throw unreplayable("LOB 参数");
    }

    @Override
    public void setClob(final int index, final Reader value) throws SQLException {
        throw unreplayable("LOB 参数");
    }

    @Override
    public void setNClob(final int index, final NClob value) throws SQLException {
        throw unreplayable("LOB 参数");
    }

    @Override
    public void setNClob(final int index, final Reader value, final long length) throws SQLException {
        throw unreplayable("LOB 参数");
    }

    @Override
    public void setNClob(final int index, final Reader value) throws SQLException {
        throw unreplayable("LOB 参数");
    }

    @Override
    public void setArray(final int index, final Array value) throws SQLException {
        throw unreplayable("数组参数");
    }

    @Override
    public void setRef(final int index, final Ref value) throws SQLException {
        throw unreplayable("Ref 参数");
    }

    @Override
    public void setRowId(final int index, final RowId value) throws SQLException {
        throw unreplayable("RowId 参数");
    }

    @Override
    public void setSQLXML(final int index, final SQLXML value) throws SQLException {
        throw unreplayable("SQLXML 参数");
    }

    // ---------- 执行（每单元 prepare + 回放） ----------

    @Override
    public ResultSet executeQuery() throws SQLException {
        requireOpen();
        final ShardedConnection.Routed routed = connection().route(sql);
        if (routed.bound.shape().write) {
            throw new SQLException("写语句须用 executeUpdate（executeQuery 只接受读）");
        }
        final ResultSet merged = connection().query(routed, replayQuery());
        trackResult(merged);
        return merged;
    }

    @Override
    public int executeUpdate() throws SQLException {
        requireOpen();
        final ShardedConnection.Routed routed = connection().route(sql);
        if (!routed.bound.shape().write) {
            throw new SQLException("读语句须用 executeQuery（executeUpdate 只接受写）");
        }
        final int count = connection().update(routed, replayUpdate());
        markUpdate(count);
        return count;
    }

    @Override
    public boolean execute() throws SQLException {
        requireOpen();
        final ShardedConnection.Routed routed = connection().route(sql);
        if (routed.bound.shape().write) {
            markUpdate(connection().update(routed, replayUpdate()));
            return true;
        }
        trackResult(connection().query(routed, replayQuery()));
        return false;
    }

    /** 读路径单元执行：prepare → 流式提示 → 回放 → executeQuery（游标持有者，语句随游标关）。 */
    private Executor.UnitQuery replayQuery() throws SQLException {
        final boolean streaming = connection().snapshot().guards().streamingFetch();
        return new Executor.UnitQuery() {
            @Override
            public ResultSet execute(final Connection shard, final String unitSql) throws SQLException {
                final PreparedStatement unit = shard.prepareStatement(unitSql);
                if (streaming) {
                    unit.setFetchSize(1);
                }
                replay(unit);
                return unit.executeQuery();
            }
        };
    }

    /** 写路径单元执行：prepare → 回放 → executeUpdate（语句与连接由本单元负责关闭）。 */
    private Executor.UnitUpdate replayUpdate() {
        return new Executor.UnitUpdate() {
            @Override
            public int execute(final Connection shard, final String unitSql) throws SQLException {
                try {
                    final PreparedStatement unit = shard.prepareStatement(unitSql);
                    try {
                        replay(unit);
                        return unit.executeUpdate();
                    } finally {
                        unit.close();
                    }
                } finally {
                    shard.close();
                }
            }
        };
    }

    // ---------- PreparedStatement 面的静态 SQL 形态（JDBC 契约拒绝） ----------

    @Override
    public ResultSet executeQuery(final String sql) throws SQLException {
        throw new SQLException("预编译语句不得携带 SQL 执行（参数已录制）");
    }

    @Override
    public int executeUpdate(final String sql) throws SQLException {
        throw new SQLException("预编译语句不得携带 SQL 执行（参数已录制）");
    }

    @Override
    public int executeUpdate(final String sql, final int autoGeneratedKeys) throws SQLException {
        throw new SQLException("预编译语句不得携带 SQL 执行（参数已录制）");
    }

    @Override
    public int executeUpdate(final String sql, final int[] columnIndexes) throws SQLException {
        throw new SQLException("预编译语句不得携带 SQL 执行（参数已录制）");
    }

    @Override
    public int executeUpdate(final String sql, final String[] columnNames) throws SQLException {
        throw new SQLException("预编译语句不得携带 SQL 执行（参数已录制）");
    }

    @Override
    public boolean execute(final String sql) throws SQLException {
        throw new SQLException("预编译语句不得携带 SQL 执行（参数已录制）");
    }

    @Override
    public boolean execute(final String sql, final int autoGeneratedKeys) throws SQLException {
        throw new SQLException("预编译语句不得携带 SQL 执行（参数已录制）");
    }

    @Override
    public boolean execute(final String sql, final int[] columnIndexes) throws SQLException {
        throw new SQLException("预编译语句不得携带 SQL 执行（参数已录制）");
    }

    @Override
    public boolean execute(final String sql, final String[] columnNames) throws SQLException {
        throw new SQLException("预编译语句不得携带 SQL 执行（参数已录制）");
    }

    @Override
    public void addBatch(final String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException("批量跨分片 v0 不支持（逐语句执行）");
    }

    @Override
    public void addBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException("批量跨分片 v0 不支持（参数回放逐语句执行）");
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException("参数元数据跨分片 v0 不支持（直通模式可用）");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException("预编译语句元数据跨分片 v0 不支持（直通模式可用）");
    }

    // ---------- 参数值形态 ----------

    /** 显式 setNull（含类型名形态）。 */
    private static final class NullParam {

        final int sqlType;
        final String typeName;

        NullParam(final int sqlType, final String typeName) {
            this.sqlType = sqlType;
            this.typeName = typeName;
        }
    }

    /** 日期/时间 + Calendar（时区语义保全回放）。 */
    private static final class DatedParam {

        final Object value;
        final Calendar calendar;

        DatedParam(final Object value, final Calendar calendar) {
            this.value = value;
            this.calendar = calendar;
        }
    }

    /** setObject 带目标类型（scaleOrLength ≥ 0 时带标度）。 */
    private static final class TypedParam {

        final Object value;
        final int targetSqlType;
        final int scaleOrLength;

        TypedParam(final Object value, final int targetSqlType, final int scaleOrLength) {
            this.value = value;
            this.targetSqlType = targetSqlType;
            this.scaleOrLength = scaleOrLength;
        }
    }

    // ---------- 内部 ----------

    private void set(final int index, final Object value) throws SQLException {
        requireOpen();
        if (1 > index) {
            throw new SQLException("参数位从 1 起（收到 " + index + "）");
        }
        if (params.length < index) {
            final Object[] grown = new Object[Math.max(index, params.length * 2)];
            System.arraycopy(params, 0, grown, 0, params.length);
            params = grown;
        }
        params[index - 1] = value;
    }

    /** 按录制形态向单元语句回放（运行时类型收敛；未设置位不触碰——原生驱动报缺参）。 */
    private void replay(final PreparedStatement unit) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            final Object value = params[i];
            if (null == value) {
                continue;
            }
            final int index = i + 1;
            if (value instanceof NullParam) {
                final NullParam nullParam = (NullParam) value;
                if (null != nullParam.typeName) {
                    unit.setNull(index, nullParam.sqlType, nullParam.typeName);
                } else {
                    unit.setNull(index, nullParam.sqlType);
                }
            } else if (value instanceof DatedParam) {
                final DatedParam dated = (DatedParam) value;
                if (dated.value instanceof Date) {
                    unit.setDate(index, (Date) dated.value, dated.calendar);
                } else if (dated.value instanceof Time) {
                    unit.setTime(index, (Time) dated.value, dated.calendar);
                } else {
                    unit.setTimestamp(index, (Timestamp) dated.value, dated.calendar);
                }
            } else if (value instanceof TypedParam) {
                final TypedParam typed = (TypedParam) value;
                if (0 <= typed.scaleOrLength) {
                    unit.setObject(index, typed.value, typed.targetSqlType, typed.scaleOrLength);
                } else {
                    unit.setObject(index, typed.value, typed.targetSqlType);
                }
            } else if (value instanceof Boolean) {
                unit.setBoolean(index, (Boolean) value);
            } else if (value instanceof Byte) {
                unit.setByte(index, (Byte) value);
            } else if (value instanceof Short) {
                unit.setShort(index, (Short) value);
            } else if (value instanceof Integer) {
                unit.setInt(index, (Integer) value);
            } else if (value instanceof Long) {
                unit.setLong(index, (Long) value);
            } else if (value instanceof Float) {
                unit.setFloat(index, (Float) value);
            } else if (value instanceof Double) {
                unit.setDouble(index, (Double) value);
            } else if (value instanceof BigDecimal) {
                unit.setBigDecimal(index, (BigDecimal) value);
            } else if (value instanceof String) {
                unit.setString(index, (String) value);
            } else if (value instanceof byte[]) {
                unit.setBytes(index, (byte[]) value);
            } else if (value instanceof Timestamp) {
                unit.setTimestamp(index, (Timestamp) value);
            } else if (value instanceof Time) {
                unit.setTime(index, (Time) value);
            } else if (value instanceof Date) {
                unit.setDate(index, (Date) value);
            } else {
                unit.setObject(index, value);
            }
        }
    }

    private static SQLFeatureNotSupportedException unreplayable(final String what) {
        return new SQLFeatureNotSupportedException(what + "不可跨分片回放"
                + "（一次性对象无值快照；直通模式或标量化后使用）");
    }
}
