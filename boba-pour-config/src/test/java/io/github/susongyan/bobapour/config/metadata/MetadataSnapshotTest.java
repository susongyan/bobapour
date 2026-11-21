package io.github.susongyan.bobapour.config.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.susongyan.bobapour.config.ConfigException;
import io.github.susongyan.bobapour.config.ConfigLoader;
import io.github.susongyan.bobapour.config.model.CrossShardQuery;

/**
 * 快照装配黄金用例（T3 DoD）：PourConfig → 只读快照逐项等价（节点展开/绑定组/广播节点/加密视图/
 * guards 透传）；跨节交叉校验（加密列不得为分区键）；展开超限翻译为带路径配置错误；
 * 无锁读取——不可变构造 + 多线程并发读一致性冒烟（读侧零同步零异常）。
 */
final class MetadataSnapshotTest {

    private static final String GOLDEN = String.join("\n",
        "datasources:",
        "  ds_0:",
        "    className: com.example.pool.DataSource",
        "  ds_1:",
        "    className: com.example.pool.DataSource",
        "sharding:",
        "  tables:",
        "    t_order:",
        "      actualDataNodes: ds_${0..1}.t_order_${0..7}",
        "      databaseStrategy:",
        "        shardingColumn: user_id",
        "        algorithm:",
        "          type: MOD",
        "          props:",
        "            count: 2",
        "      tableStrategy:",
        "        shardingColumn: order_id",
        "        algorithm:",
        "          type: MOD",
        "          props:",
        "            count: 8",
        "      crossShardQuery: WHITELIST",
        "      partition:",
        "        key: created_at",
        "        type: RANGE",
        "        partitions:",
        "          - name: p2026",
        "            upper: '2027-01-01'",
        "    t_order_item:",
        "      actualDataNodes: ds_0.t_order_item",
        "encrypt:",
        "  encryptors:",
        "    aes_det:",
        "      type: AES",
        "  tables:",
        "    t_order:",
        "      columns:",
        "        phone:",
        "          cipherColumn: phone_cipher",
        "          encryptor: aes_det",
        "broadcastTables:",
        "  - t_config",
        "bindingTables:",
        "  - - t_order",
        "    - t_order_item",
        "guards:",
        "  maxShardsPerQuery: 4",
        "");

    @Test
    void 装配黄金_逐项等价() {
        final MetadataSnapshot snapshot = MetadataSnapshot.of(ConfigLoader.load(GOLDEN));
        assertEquals("ds_0, ds_1", String.join(", ", snapshot.datasourceNames()));
        final TableSnapshot order = snapshot.table("t_order");
        assertNotNull(order);
        assertEquals(16, order.dataNodes().size());
        assertEquals(new DataNode("ds_1", "t_order_7"), order.dataNodes().all().get(15));
        assertEquals(CrossShardQuery.WHITELIST, order.rule().crossShardQuery());
        assertEquals("user_id", order.rule().databaseStrategy().shardingColumn());
        assertEquals("p2026", order.partition().locate("2026-06-01"));
        assertEquals("t_order, t_order_item", String.join(", ", order.bindingGroup()));
        assertEquals(order.bindingGroup(), snapshot.bindingGroup("t_order_item"));
        assertTrue(!snapshot.isBroadcast("t_order") && snapshot.isSharded("t_order"));
        assertTrue(snapshot.isBroadcast("t_config") && !snapshot.isSharded("t_config"));
        assertNull(snapshot.table("t_missing"));
        assertEquals(2, snapshot.broadcastNodes("t_config").size());
        assertEquals(new DataNode("ds_1", "t_config"), snapshot.broadcastNodes("t_config").get(1));
        assertEquals(1, snapshot.encryptColumns("t_order").size());
        assertEquals("phone_cipher", snapshot.encryptColumns("t_order").get("phone").cipherColumn());
        assertEquals("AES", snapshot.encryptor("aes_det").type());
        assertTrue(snapshot.encryptColumns("t_missing").isEmpty());
        assertEquals(4, snapshot.guards().maxShardsPerQuery());
        assertEquals(10000, snapshot.guards().maxMergeWindowRows());
    }

    @Test
    void 拒绝_加密列为分区键() {
        final String yaml = GOLDEN.replace("key: created_at", "key: phone");
        final ConfigException e = assertThrows(ConfigException.class,
                () -> MetadataSnapshot.of(ConfigLoader.load(yaml)));
        assertTrue(e.getMessage().contains("加密列不得为分区键"), e.getMessage());
    }

    @Test
    void 拒绝_展开超限_带路径() {
        final String yaml = GOLDEN.replace("ds_${0..1}.t_order_${0..7}", "ds_${0..300}.t_order_${0..300}");
        final ConfigException e = assertThrows(ConfigException.class,
                () -> MetadataSnapshot.of(ConfigLoader.load(yaml)));
        assertTrue(e.getMessage().contains("sharding.tables.t_order.actualDataNodes"), e.getMessage());
    }

    /**
     * 无锁读取冒烟（T3 DoD「快照无锁读取单测」）：8 线程并发读全部视图（表/绑定/加密/分区定位/
     * guards），零同步零异常且答案与单线程一致——不可变构造使读侧天然无锁，本用例锁死该构造不被回归。
     */
    @Test
    void 并发无锁读取_答案一致() throws Exception {
        final MetadataSnapshot snapshot = MetadataSnapshot.of(ConfigLoader.load(GOLDEN));
        final int threads = 8;
        final int rounds = 500;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<Void>> futures = new ArrayList<Future<Void>>(threads);
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    final TableSnapshot order = snapshot.table("t_order");
                    assertNotNull(order);
                    assertEquals(16, order.dataNodes().size());
                    assertEquals("p2026", order.partition().locate("2026-06-01"));
                    assertEquals(2, order.bindingGroup().size());
                    assertEquals("phone_cipher",
                            snapshot.encryptColumns("t_order").get("phone").cipherColumn());
                    assertEquals(2, snapshot.broadcastNodes("t_config").size());
                    assertEquals(4, snapshot.guards().maxShardsPerQuery());
                }
                return null;
            }));
        }
        start.countDown();
        for (final Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }
}
