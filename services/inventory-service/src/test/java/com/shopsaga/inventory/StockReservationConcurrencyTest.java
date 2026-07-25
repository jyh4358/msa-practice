package com.shopsaga.inventory;

import com.shopsaga.inventory.application.port.in.GetStockQuery;
import com.shopsaga.inventory.application.port.out.ReserveStockPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성(oversell) 회귀 가드 — 비관적 락이 동시 예약에서 재고 초과판매를 막는지 실제 PostgreSQL로 검증.
 *
 * <p>Phase 12부터 이 서비스는 outbox 릴레이(→ KafkaTemplate 필요)를 갖는다. 그래서 Kafka 자동구성을
 * 제외하지 않고, 대신 <b>브로커에 붙으려는 동작만</b> 끈다: 리스너를 기동하지 않고 릴레이 폴링 주기를 길게 둔다.
 * (KafkaTemplate 생성 자체는 브로커 연결이 필요 없다.) Docker 필요.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.listener.auto-startup=false",   // 브로커 없이 @KafkaListener 를 띄우지 않는다
                "outbox.relay.fixed-delay=3600000"            // 릴레이 폴링 사실상 비활성(이 테스트의 관심사가 아님)
        })
@Testcontainers
class StockReservationConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    ReserveStockPort reserveStockPort;
    @Autowired
    GetStockQuery getStock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void pessimisticLock_preventsOversell() throws Exception {
        UUID product = UUID.randomUUID();
        int initialStock = 5;
        int threads = 20;
        jdbc.update("INSERT INTO stock_items(product_id, available_quantity) VALUES (?, ?)",
                product, initialStock);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    // 각 예약을 독립 트랜잭션으로 — 비관적 락이 트랜잭션 커밋까지 유지되게.
                    tx.executeWithoutResult(s -> reserveStockPort.reserve(product, 1));
                    reserved.incrementAndGet();
                } catch (RuntimeException expected) {
                    // 재고 부족으로 실패 — 정상(초과 예약은 거부돼야 함)
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(reserved.get()).isEqualTo(initialStock);                     // 정확히 재고만큼만 성공
        assertThat(getStock.getStock(product).availableQuantity()).isZero();    // 음수 없음(oversell 없음)
    }
}
