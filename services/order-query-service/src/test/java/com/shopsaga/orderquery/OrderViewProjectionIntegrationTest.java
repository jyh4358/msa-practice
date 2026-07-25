package com.shopsaga.orderquery;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.orderquery.application.port.in.GetOrderViewQuery;
import com.shopsaga.orderquery.application.port.in.OrderSummary;
import com.shopsaga.orderquery.application.port.in.ProjectOrderPlacedUseCase;
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
 * Phase 11: 실제 MongoDB(Testcontainers)로 투영·조회를 검증한다.
 *
 * <p>핵심 검증 2가지:
 * <ul>
 *   <li><b>멱등 투영</b>: 같은 이벤트를 두 번 투영해도 문서가 1개(orderId 기준 upsert) — 리플레이 안전성의 근거.</li>
 *   <li><b>비정규화 조회</b>: 조인 없이 문서 하나로 품목까지 반환(금액은 Decimal128로 저장돼 값 보존).</li>
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
    ProjectOrderPlacedUseCase projector;
    @Autowired
    GetOrderViewQuery query;

    private OrderPlacedEvent event(UUID orderId, UUID customerId) {
        return new OrderPlacedEvent(orderId, customerId,
                List.of(new OrderPlacedEvent.Item(UUID.randomUUID(), 2, new BigDecimal("19.99"))),
                new BigDecimal("39.98"),
                Instant.parse("2026-07-18T09:30:00Z"));
    }

    @Test
    void duplicateProjection_upsertsSingleDocument() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        OrderPlacedEvent event = event(orderId, customerId);

        projector.project(event);
        projector.project(event);   // 재배달/리플레이

        List<OrderSummary> byCustomer = query.findByCustomer(customerId);
        assertThat(byCustomer).hasSize(1);   // 문서 2개가 아니라 1개 — upsert 로 덮어썼다
        assertThat(byCustomer.getFirst().orderId()).isEqualTo(orderId);
    }

    @Test
    void query_returnsDenormalizedDocumentWithPreservedAmounts() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        projector.project(event(orderId, customerId));

        OrderSummary summary = query.getByOrderId(orderId);

        assertThat(summary.status()).isEqualTo("CONFIRMED");
        assertThat(summary.placedAt()).isEqualTo(Instant.parse("2026-07-18T09:30:00Z"));
        // Decimal128 왕복 후에도 금액이 정확히 보존되는지(부동소수 오차 없음)
        assertThat(summary.totalAmount()).isEqualByComparingTo("39.98");
        assertThat(summary.lines()).singleElement().satisfies(l -> {
            assertThat(l.unitPrice()).isEqualByComparingTo("19.99");
            assertThat(l.lineTotal()).isEqualByComparingTo("39.98");
        });
    }
}
