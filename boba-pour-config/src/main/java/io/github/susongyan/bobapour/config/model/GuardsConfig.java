package io.github.susongyan.bobapour.config.model;

import java.util.Objects;

/**
 * 全局 guards（D3 第二防线，超限=执行前拒绝）。默认从严：maxShardsPerQuery=8
 * （广播表写入除外）、maxMergeWindowRows=10000（TopN 窗口 offset+limit 总量）、
 * streamingFetch=true（分片查询强制游标流式——第三防线，不给驱动默认全量缓冲留机会）。
 */
public final class GuardsConfig {

    private final int maxShardsPerQuery;
    private final int maxMergeWindowRows;
    private final boolean streamingFetch;

    public GuardsConfig() {
        this(8, 10000, true);
    }

    public GuardsConfig(final int maxShardsPerQuery, final int maxMergeWindowRows, final boolean streamingFetch) {
        this.maxShardsPerQuery = maxShardsPerQuery;
        this.maxMergeWindowRows = maxMergeWindowRows;
        this.streamingFetch = streamingFetch;
    }

    public int maxShardsPerQuery() {
        return maxShardsPerQuery;
    }

    public int maxMergeWindowRows() {
        return maxMergeWindowRows;
    }

    public boolean streamingFetch() {
        return streamingFetch;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof GuardsConfig)) {
            return false;
        }
        final GuardsConfig other = (GuardsConfig) obj;
        return maxShardsPerQuery == other.maxShardsPerQuery
                && maxMergeWindowRows == other.maxMergeWindowRows
                && streamingFetch == other.streamingFetch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxShardsPerQuery, maxMergeWindowRows, streamingFetch);
    }

    @Override
    public String toString() {
        return "shards<=" + maxShardsPerQuery + " window<=" + maxMergeWindowRows
                + (streamingFetch ? " streaming" : "");
    }
}
