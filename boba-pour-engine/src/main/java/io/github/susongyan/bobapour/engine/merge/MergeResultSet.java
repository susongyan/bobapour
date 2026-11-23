package io.github.susongyan.bobapour.engine.merge;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
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
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

import io.github.susongyan.bobapour.engine.execute.QueryFanout;

/**
 * 归并结果集基类（T7）：持有 {@link QueryFanout} 全分片游标，值读取<b>委托当前行所在的
 * 分片游标</b>（active）——驱动返回的原生类型与语义零转手（M1 直通锚定的契约行为在归并
 * 路径保持）。游标模型 = TYPE_FORWARD_ONLY + CONCUR_READ_ONLY：定位/回退/更新族显式
 * {@link SQLFeatureNotSupportedException}，不做静默降级；close 级联归还全部分片资源
 * （连接即池归还）。
 *
 * <p>子类职责：实现 {@link #next()} 的推进策略（拼接/流式/TopN/重组装），行交付时调
 * {@link #yielded()} 并置 {@link #active}。行计数与首尾态由基类维护。</p>
 */
public abstract class MergeResultSet implements ResultSet {

    /** 分片资源持有体（close 级联归还）。 */
    protected final QueryFanout fanout;

    /** 当前行所在分片游标（行外为 null——值读取前 {@link #requireRow()}）。 */
    protected ResultSet active;

    /** 已交付行数（getRow）。 */
    protected int rowIndex;

    /** 游标越过末行（isAfterLast）。 */
    protected boolean afterLast;

    private boolean closed;

    protected MergeResultSet(final QueryFanout fanout) {
        this.fanout = fanout;
    }

    /** 行推进策略（拼接顺序/优先队列/窗口跳取/重组装单行）。 */
    @Override
    public abstract boolean next() throws SQLException;

    /** 子类交付一行时调用（行计数自增）。 */
    protected final void yielded() {
        rowIndex++;
    }

    @Override
    public void close() throws SQLException {
        closed = true;
        active = null;
        fanout.close();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ---------- 值读取：委托活跃分片游标（类型保真） ----------

    private ResultSet requireRow() throws SQLException {
        if (null == active) {
            throw new SQLDataException("游标不在有效行上（TYPE_FORWARD_ONLY，行外无值可读）");
        }
        return active;
    }

    @Override
    public boolean wasNull() throws SQLException {
        return requireRow().wasNull();
    }

    @Override
    public String getString(final int columnIndex) throws SQLException {
        return requireRow().getString(columnIndex);
    }

    @Override
    public boolean getBoolean(final int columnIndex) throws SQLException {
        return requireRow().getBoolean(columnIndex);
    }

    @Override
    public byte getByte(final int columnIndex) throws SQLException {
        return requireRow().getByte(columnIndex);
    }

    @Override
    public short getShort(final int columnIndex) throws SQLException {
        return requireRow().getShort(columnIndex);
    }

    @Override
    public int getInt(final int columnIndex) throws SQLException {
        return requireRow().getInt(columnIndex);
    }

    @Override
    public long getLong(final int columnIndex) throws SQLException {
        return requireRow().getLong(columnIndex);
    }

    @Override
    public float getFloat(final int columnIndex) throws SQLException {
        return requireRow().getFloat(columnIndex);
    }

    @Override
    public double getDouble(final int columnIndex) throws SQLException {
        return requireRow().getDouble(columnIndex);
    }

    @Deprecated
    @Override
    public BigDecimal getBigDecimal(final int columnIndex, final int scale) throws SQLException {
        return requireRow().getBigDecimal(columnIndex, scale);
    }

    @Override
    public byte[] getBytes(final int columnIndex) throws SQLException {
        return requireRow().getBytes(columnIndex);
    }

    @Override
    public Date getDate(final int columnIndex) throws SQLException {
        return requireRow().getDate(columnIndex);
    }

    @Override
    public Time getTime(final int columnIndex) throws SQLException {
        return requireRow().getTime(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(final int columnIndex) throws SQLException {
        return requireRow().getTimestamp(columnIndex);
    }

    @Override
    public InputStream getAsciiStream(final int columnIndex) throws SQLException {
        return requireRow().getAsciiStream(columnIndex);
    }

    @Deprecated
    @Override
    public InputStream getUnicodeStream(final int columnIndex) throws SQLException {
        return requireRow().getUnicodeStream(columnIndex);
    }

    @Override
    public InputStream getBinaryStream(final int columnIndex) throws SQLException {
        return requireRow().getBinaryStream(columnIndex);
    }

    @Override
    public String getString(final String columnLabel) throws SQLException {
        return requireRow().getString(columnLabel);
    }

    @Override
    public boolean getBoolean(final String columnLabel) throws SQLException {
        return requireRow().getBoolean(columnLabel);
    }

    @Override
    public byte getByte(final String columnLabel) throws SQLException {
        return requireRow().getByte(columnLabel);
    }

    @Override
    public short getShort(final String columnLabel) throws SQLException {
        return requireRow().getShort(columnLabel);
    }

    @Override
    public int getInt(final String columnLabel) throws SQLException {
        return requireRow().getInt(columnLabel);
    }

    @Override
    public long getLong(final String columnLabel) throws SQLException {
        return requireRow().getLong(columnLabel);
    }

    @Override
    public float getFloat(final String columnLabel) throws SQLException {
        return requireRow().getFloat(columnLabel);
    }

    @Override
    public double getDouble(final String columnLabel) throws SQLException {
        return requireRow().getDouble(columnLabel);
    }

    @Deprecated
    @Override
    public BigDecimal getBigDecimal(final String columnLabel, final int scale) throws SQLException {
        return requireRow().getBigDecimal(columnLabel, scale);
    }

    @Override
    public byte[] getBytes(final String columnLabel) throws SQLException {
        return requireRow().getBytes(columnLabel);
    }

    @Override
    public Date getDate(final String columnLabel) throws SQLException {
        return requireRow().getDate(columnLabel);
    }

    @Override
    public Time getTime(final String columnLabel) throws SQLException {
        return requireRow().getTime(columnLabel);
    }

    @Override
    public Timestamp getTimestamp(final String columnLabel) throws SQLException {
        return requireRow().getTimestamp(columnLabel);
    }

    @Override
    public InputStream getAsciiStream(final String columnLabel) throws SQLException {
        return requireRow().getAsciiStream(columnLabel);
    }

    @Deprecated
    @Override
    public InputStream getUnicodeStream(final String columnLabel) throws SQLException {
        return requireRow().getUnicodeStream(columnLabel);
    }

    @Override
    public InputStream getBinaryStream(final String columnLabel) throws SQLException {
        return requireRow().getBinaryStream(columnLabel);
    }

    @Override
    public Object getObject(final int columnIndex) throws SQLException {
        return requireRow().getObject(columnIndex);
    }

    @Override
    public Object getObject(final String columnLabel) throws SQLException {
        return requireRow().getObject(columnLabel);
    }

    @Override
    public Reader getCharacterStream(final int columnIndex) throws SQLException {
        return requireRow().getCharacterStream(columnIndex);
    }

    @Override
    public Reader getCharacterStream(final String columnLabel) throws SQLException {
        return requireRow().getCharacterStream(columnLabel);
    }

    @Override
    public BigDecimal getBigDecimal(final int columnIndex) throws SQLException {
        return requireRow().getBigDecimal(columnIndex);
    }

    @Override
    public BigDecimal getBigDecimal(final String columnLabel) throws SQLException {
        return requireRow().getBigDecimal(columnLabel);
    }

    @Override
    public Ref getRef(final int columnIndex) throws SQLException {
        return requireRow().getRef(columnIndex);
    }

    @Override
    public Blob getBlob(final int columnIndex) throws SQLException {
        return requireRow().getBlob(columnIndex);
    }

    @Override
    public Clob getClob(final int columnIndex) throws SQLException {
        return requireRow().getClob(columnIndex);
    }

    @Override
    public Array getArray(final int columnIndex) throws SQLException {
        return requireRow().getArray(columnIndex);
    }

    @Override
    public Ref getRef(final String columnLabel) throws SQLException {
        return requireRow().getRef(columnLabel);
    }

    @Override
    public Blob getBlob(final String columnLabel) throws SQLException {
        return requireRow().getBlob(columnLabel);
    }

    @Override
    public Clob getClob(final String columnLabel) throws SQLException {
        return requireRow().getClob(columnLabel);
    }

    @Override
    public Array getArray(final String columnLabel) throws SQLException {
        return requireRow().getArray(columnLabel);
    }

    @Override
    public Date getDate(final int columnIndex, final Calendar cal) throws SQLException {
        return requireRow().getDate(columnIndex, cal);
    }

    @Override
    public Date getDate(final String columnLabel, final Calendar cal) throws SQLException {
        return requireRow().getDate(columnLabel, cal);
    }

    @Override
    public Time getTime(final int columnIndex, final Calendar cal) throws SQLException {
        return requireRow().getTime(columnIndex, cal);
    }

    @Override
    public Time getTime(final String columnLabel, final Calendar cal) throws SQLException {
        return requireRow().getTime(columnLabel, cal);
    }

    @Override
    public Timestamp getTimestamp(final int columnIndex, final Calendar cal) throws SQLException {
        return requireRow().getTimestamp(columnIndex, cal);
    }

    @Override
    public Timestamp getTimestamp(final String columnLabel, final Calendar cal) throws SQLException {
        return requireRow().getTimestamp(columnLabel, cal);
    }

    @Deprecated
    @Override
    public URL getURL(final int columnIndex) throws SQLException {
        return requireRow().getURL(columnIndex);
    }

    @Deprecated
    @Override
    public URL getURL(final String columnLabel) throws SQLException {
        return requireRow().getURL(columnLabel);
    }

    @Override
    public NClob getNClob(final int columnIndex) throws SQLException {
        return requireRow().getNClob(columnIndex);
    }

    @Override
    public NClob getNClob(final String columnLabel) throws SQLException {
        return requireRow().getNClob(columnLabel);
    }

    @Override
    public SQLXML getSQLXML(final int columnIndex) throws SQLException {
        return requireRow().getSQLXML(columnIndex);
    }

    @Override
    public SQLXML getSQLXML(final String columnLabel) throws SQLException {
        return requireRow().getSQLXML(columnLabel);
    }

    @Override
    public String getNString(final int columnIndex) throws SQLException {
        return requireRow().getNString(columnIndex);
    }

    @Override
    public String getNString(final String columnLabel) throws SQLException {
        return requireRow().getNString(columnLabel);
    }

    @Override
    public Reader getNCharacterStream(final int columnIndex) throws SQLException {
        return requireRow().getNCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(final String columnLabel) throws SQLException {
        return requireRow().getNCharacterStream(columnLabel);
    }

    @Override
    public RowId getRowId(final int columnIndex) throws SQLException {
        return requireRow().getRowId(columnIndex);
    }

    @Override
    public RowId getRowId(final String columnLabel) throws SQLException {
        return requireRow().getRowId(columnLabel);
    }

    @Override
    public <T> T getObject(final int columnIndex, final Class<T> type) throws SQLException {
        return requireRow().getObject(columnIndex, type);
    }

    @Override
    public <T> T getObject(final String columnLabel, final Class<T> type) throws SQLException {
        return requireRow().getObject(columnLabel, type);
    }

    @Override
    public Object getObject(final int columnIndex, final Map<String, Class<?>> map) throws SQLException {
        return requireRow().getObject(columnIndex, map);
    }

    @Override
    public Object getObject(final String columnLabel, final Map<String, Class<?>> map) throws SQLException {
        return requireRow().getObject(columnLabel, map);
    }

    // ---------- 元数据与列名（各分片同形，取首分片） ----------

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return firstShard().getMetaData();
    }

    @Override
    public int findColumn(final String columnLabel) throws SQLException {
        return firstShard().findColumn(columnLabel);
    }

    private ResultSet firstShard() throws SQLException {
        if (fanout.shards().isEmpty()) {
            throw new SQLException("fan-out 无分片（裁决不变量破坏）");
        }
        return fanout.shards().get(0).resultSet();
    }

    // ---------- 游标态（只进） ----------

    @Override
    public boolean isBeforeFirst() throws SQLException {
        return 0 == rowIndex && !afterLast && !fanout.shards().isEmpty() && firstShard().isBeforeFirst();
    }

    @Override
    public boolean isAfterLast() {
        return afterLast;
    }

    @Override
    public boolean isFirst() {
        return 1 == rowIndex;
    }

    @Override
    public boolean isLast() {
        return false;
    }

    @Override
    public int getRow() {
        return afterLast ? 0 : rowIndex;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null == active ? null : active.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        if (null != active) {
            active.clearWarnings();
        }
    }

    @Override
    public String getCursorName() {
        return null;
    }

    @Override
    public int getType() {
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() {
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getFetchDirection() {
        return FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(final int direction) throws SQLException {
        if (FETCH_FORWARD != direction) {
            throw new SQLFeatureNotSupportedException("归并游标只进（FETCH_FORWARD）");
        }
    }

    @Override
    public void setFetchSize(final int rows) {
        // 提示参数：流式提示已在 openQuery 按快照 guards 落在各分片语句上，此处不逐分片改写。
    }

    @Override
    public int getFetchSize() throws SQLException {
        return firstShard().getFetchSize();
    }

    @Override
    public int getHoldability() throws SQLException {
        return firstShard().getHoldability();
    }

    @Override
    public Statement getStatement() {
        return null;
    }

    // ---------- 只进拒绝面（显式，不静默降级） ----------

    private SQLFeatureNotSupportedException forwardOnly() {
        return new SQLFeatureNotSupportedException("归并游标 TYPE_FORWARD_ONLY（定位/回退不支持）");
    }

    @Override
    public boolean previous() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean absolute(final int row) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean relative(final int rows) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public void afterLast() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean first() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean last() throws SQLException {
        throw forwardOnly();
    }

    // ---------- 只读拒绝面（CONCUR_READ_ONLY） ----------

    private SQLFeatureNotSupportedException readOnly() {
        return new SQLFeatureNotSupportedException("归并游标 CONCUR_READ_ONLY（更新族不支持）");
    }

    @Override
    public boolean rowUpdated() {
        return false;
    }

    @Override
    public boolean rowInserted() {
        return false;
    }

    @Override
    public boolean rowDeleted() {
        return false;
    }

    @Override
    public void updateNull(final int columnIndex) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(final int columnIndex, final boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(final int columnIndex, final byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(final int columnIndex, final short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(final int columnIndex, final int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(final int columnIndex, final long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(final int columnIndex, final float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(final int columnIndex, final double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(final int columnIndex, final BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(final int columnIndex, final String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(final int columnIndex, final byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(final int columnIndex, final Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(final int columnIndex, final Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(final int columnIndex, final Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(final int columnIndex, final InputStream x, final int length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(final int columnIndex, final InputStream x, final int length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(final int columnIndex, final Reader x, final int length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(final int columnIndex, final Object x, final int scaleOrLength)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(final int columnIndex, final Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(final String columnLabel) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(final String columnLabel, final boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(final String columnLabel, final byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(final String columnLabel, final short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(final String columnLabel, final int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(final String columnLabel, final long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(final String columnLabel, final float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(final String columnLabel, final double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(final String columnLabel, final BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(final String columnLabel, final String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(final String columnLabel, final byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(final String columnLabel, final Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(final String columnLabel, final Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(final String columnLabel, final Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(final String columnLabel, final InputStream x, final int length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(final String columnLabel, final InputStream x, final int length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(final String columnLabel, final Reader reader, final int length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(final String columnLabel, final Object x, final int scaleOrLength)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(final String columnLabel, final Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(final int columnIndex, final Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(final String columnLabel, final Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(final int columnIndex, final Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(final String columnLabel, final Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(final int columnIndex, final Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(final String columnLabel, final Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(final int columnIndex, final Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(final String columnLabel, final Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(final int columnIndex, final RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(final String columnLabel, final RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(final int columnIndex, final String nString) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(final String columnLabel, final String nString) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(final int columnIndex, final NClob nClob) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(final String columnLabel, final NClob nClob) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(final int columnIndex, final SQLXML xmlObject) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(final String columnLabel, final SQLXML xmlObject) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(final int columnIndex, final Reader x, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(final String columnLabel, final Reader reader, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(final int columnIndex, final InputStream x, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(final int columnIndex, final InputStream x, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(final int columnIndex, final Reader x, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(final String columnLabel, final InputStream x, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(final String label, final InputStream x, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(final String label, final Reader reader, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(final int columnIndex, final InputStream inputStream, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(final String columnLabel, final InputStream inputStream, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(final int columnIndex, final Reader reader, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(final String columnLabel, final Reader reader, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(final int columnIndex, final Reader reader, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(final String columnLabel, final Reader reader, final long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(final int columnIndex, final Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(final String columnLabel, final Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(final int columnIndex, final InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(final String columnLabel, final InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(final int columnIndex, final InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(final String columnLabel, final InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(final int columnIndex, final Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(final String columnLabel, final Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(final int columnIndex, final InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(final String columnLabel, final InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(final int columnIndex, final Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(final String columnLabel, final Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(final int columnIndex, final Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(final String columnLabel, final Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void insertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw readOnly();
    }

    // ---------- Wrapper ----------

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLFeatureNotSupportedException("归并结果集不包装目标类型: " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) {
        return iface.isInstance(this);
    }
}
