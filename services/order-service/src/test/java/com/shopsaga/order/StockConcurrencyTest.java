package com.shopsaga.order;

import com.shopsaga.order.application.port.in.GetStockQuery;
import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.application.port.in.PlaceOrderUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
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
 * 동시성(oversell) 회귀 가드 — 비관적 락이 동시 주문에서 재고 초과판매를 막는지 실제 PostgreSQL로 검증.
 * 재고 K개에 N개 동시 주문(각 1개) → 정확히 K건만 성공, 최종 재고 0(음수 불가). Docker 필요.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class StockConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    PlaceOrderUseCase placeOrder;
    @Autowired
    GetStockQuery getStock;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void pessimisticLock_preventsOversell() throws Exception {
        UUID product = UUID.randomUUID();
        int initialStock = 5;
        int threads = 20;
        jdbc.update("INSERT INTO stock_items(product_id, available_quantity) VALUES (?, ?)",
                product, initialStock);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();   // 모든 스레드를 동시에 출발시켜 경합 최대화
                try {
                    placeOrder.placeOrder(new PlaceOrderCommand(UUID.randomUUID(),
                            List.of(new PlaceOrderCommand.Item(product, 1, new BigDecimal("10.00")))));
                    confirmed.incrementAndGet();
                } catch (RuntimeException expected) {
                    // 재고 부족 등으로 실패 — 정상(초과 주문은 거부돼야 함)
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(confirmed.get()).isEqualTo(initialStock);                 // 정확히 재고만큼만 성공
        assertThat(getStock.getStock(product).availableQuantity()).isZero(); // 음수 없음(oversell 없음)
    }
}
