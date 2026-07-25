package com.shopsaga.orderquery;

import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.orderquery.application.port.in.GetOrderViewQuery;
import com.shopsaga.orderquery.application.port.in.OrderSummary;
import com.shopsaga.orderquery.application.port.in.ProjectOrderEventsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11/12: 실제 MongoDB(Testcontainers)로 투영·조회·상태 전이를 검증한다.
 *
 * <p>핵심 검증:
 * <ul>
 *   <li><b>멱등 투영</b>: 같은 이벤트를 두 번 투영해도 문서가 1개 — 리플레이 안전성의 근거.</li>
 *   <li><b>비정규화 조회</b>: 조인 없이 문서 하나로 품목까지(금액은 Decimal128로 값 보존).</li>
 *   <li><b>단조 상태 전이</b>(Phase 12): 순서가 뒤바뀐 이벤트가 상태를 <b>되돌리지 못한다</b>.</li>
 * </ul>
 * Kafka 자동구성은 제외(브로커 없이 투영·저장 로직에만 집중). Docker 필요.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@Testcontainers
class OrderViewProjectionIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        // 컨테이너는 인증 없이 뜨므로 uri 하나로 지정(host/username 등 개별 키는 무시된다).
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("eureka.client.enabled", () -> "false");
    }

    @Autowired
    ProjectOrderEventsUseCase projector;
    @Autowired
    GetOrderViewQuery query;

    private static final Instant PLACED_AT = Instant.parse("2026-07-18T09:30:00Z");

    private OrderPlacedEvent placed(UUID orderId, UUID customerId) {
        return new OrderPlacedEvent(orderId, customerId,
                List.of(new OrderPlacedEvent.Item(UUID.randomUUID(), 2, new BigDecimal("19.99"))),
                new BigDecimal("39.98"), PLACED_AT);
    }

    @Test
    void duplicateProjection_upsertsSingleDocument() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        OrderPlacedEvent event = placed(orderId, customerId);

        projector.onOrderPlaced(event);
        projector.onOrderPlaced(event);   // 재배달/리플레이

        List<OrderSummary> byCustomer = query.findByCustomer(customerId);
        assertThat(byCustomer).hasSize(1);   // 문서 2개가 아니라 1개 — upsert 로 덮어썼다
        assertThat(byCustomer.getFirst().orderId()).isEqualTo(orderId);
    }

    @Test
    void query_returnsDenormalizedDocumentWithPreservedAmounts() {
        UUID orderId = UUID.randomUUID();
        projector.onOrderPlaced(placed(orderId, UUID.randomUUID()));

        OrderSummary summary = query.getByOrderId(orderId);

        assertThat(summary.status()).isEqualTo("PENDING");   // Saga 시작 상태
        assertThat(summary.placedAt()).isEqualTo(PLACED_AT);
        // Decimal128 왕복 후에도 금액이 정확히 보존되는지(부동소수 오차 없음)
        assertThat(summary.totalAmount()).isEqualByComparingTo("39.98");
        assertThat(summary.lines()).singleElement().satisfies(l -> {
            assertThat(l.unitPrice()).isEqualByComparingTo("19.99");
            assertThat(l.lineTotal()).isEqualByComparingTo("39.98");
        });
    }

    @Test
    void sagaHappyPath_statusAdvancesPendingToReservedToConfirmed() {
        UUID orderId = UUID.randomUUID();
        projector.onOrderPlaced(placed(orderId, UUID.randomUUID()));

        projector.onInventoryReserved(new InventoryReservedEvent(
                orderId, UUID.randomUUID(), new BigDecimal("39.98"), PLACED_AT));
        assertThat(query.getByOrderId(orderId).status()).isEqualTo("INVENTORY_RESERVED");

        projector.onOrderConfirmed(new OrderConfirmedEvent(orderId, UUID.randomUUID(), PLACED_AT));
        assertThat(query.getByOrderId(orderId).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void lateOrderPlacedRedelivery_doesNotResetStatus() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        OrderPlacedEvent event = placed(orderId, customerId);
        projector.onOrderPlaced(event);
        projector.onInventoryReserved(new InventoryReservedEvent(
                orderId, customerId, new BigDecimal("39.98"), PLACED_AT));
        projector.onOrderConfirmed(new OrderConfirmedEvent(orderId, UUID.randomUUID(), PLACED_AT));

        // 확정된 뒤에 OrderPlaced 가 재배달됨(리플레이·재시도) → 상태가 PENDING 으로 되돌아가면 안 된다
        projector.onOrderPlaced(event);

        assertThat(query.getByOrderId(orderId).status()).isEqualTo("CONFIRMED");
        assertThat(query.getByOrderId(orderId).totalAmount()).isEqualByComparingTo("39.98");   // 본문은 갱신됨
    }

    @Test
    void outOfOrderEvents_cannotRegressTerminalStatus() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        projector.onOrderPlaced(placed(orderId, customerId));

        // 취소가 먼저 처리되고, 뒤늦게 InventoryReserved 가 도착(토픽이 달라 순서 보장 없음)
        projector.onOrderCancelled(new OrderCancelledEvent(orderId, "재고 예약 실패", PLACED_AT));
        projector.onInventoryReserved(new InventoryReservedEvent(
                orderId, customerId, new BigDecimal("39.98"), PLACED_AT));

        // 단조 전이라 종료 상태가 뒤로 밀리지 않는다
        assertThat(query.getByOrderId(orderId).status()).isEqualTo("CANCELLED");
    }
}
