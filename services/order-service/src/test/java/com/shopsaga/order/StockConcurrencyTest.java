package com.shopsaga.order;

import com.shopsaga.order.application.port.in.GetStockQuery;
import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.application.port.in.PlaceOrderUseCase;
import com.shopsaga.order.application.port.out.PaymentGatewayPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 동시성(oversell) 회귀 가드 — 비관적 락이 동시 주문에서 재고 초과판매를 막는지 실제 PostgreSQL로 검증.
 * 원격 결제(payment-service)는 @MockitoBean 으로 대체해 재고 동시성에만 집중한다. Docker 필요.
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

    @MockitoBean
    PaymentGatewayPort paymentGateway;   // 원격 결제 대체 — 항상 성공(paymentId 반환)

    @Autowired
    PlaceOrderUseCase placeOrder;
    @Autowired
    GetStockQuery getStock;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void pessimisticLock_preventsOversell() throws Exception {
        when(paymentGateway.capture(any(), any())).thenReturn(UUID.randomUUID());

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
                start.await();
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

        assertThat(confirmed.get()).isEqualTo(initialStock);                    // 정확히 재고만큼만 성공
        assertThat(getStock.getStock(product).availableQuantity()).isZero();    // 음수 없음(oversell 없음)
    }
}
