package com.shopsaga.orderquery.application.service;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.orderquery.application.port.out.OrderViewRepositoryPort;
import com.shopsaga.orderquery.domain.OrderView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 11: 투영의 <b>결정성</b> 검증 — 같은 이벤트를 몇 번 투영해도 항상 같은 값이 나와야 한다.
 * (여기서 {@code Instant.now()} 같은 걸 쓰면 이 테스트가 깨진다 = 리플레이 불가능한 투영을 잡아내는 가드.)
 */
@ExtendWith(MockitoExtension.class)
class OrderViewProjectionServiceTest {

    @Mock
    OrderViewRepositoryPort repository;
    @InjectMocks
    OrderViewProjectionService service;
    @Captor
    ArgumentCaptor<OrderView> captor;

    private OrderPlacedEvent event(UUID orderId, UUID customerId, UUID productId) {
        return new OrderPlacedEvent(orderId, customerId,
                List.of(new OrderPlacedEvent.Item(productId, 3, new BigDecimal("10.00"))),
                new BigDecimal("30.00"),
                Instant.parse("2026-07-18T10:00:00Z"));
    }

    @Test
    void project_mapsEventOntoReadModel() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        service.project(event(orderId, customerId, productId));

        verify(repository).save(captor.capture());
        OrderView view = captor.getValue();
        assertThat(view.getOrderId()).isEqualTo(orderId);
        assertThat(view.getCustomerId()).isEqualTo(customerId);
        assertThat(view.getStatus()).isEqualTo("CONFIRMED");
        assertThat(view.getTotalAmount()).isEqualByComparingTo("30.00");
        // ★ 이벤트가 실어 온 시각을 그대로 사용(투영 시점의 시계가 아님)
        assertThat(view.getPlacedAt()).isEqualTo(Instant.parse("2026-07-18T10:00:00Z"));
        assertThat(view.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getProductId()).isEqualTo(productId);
            assertThat(l.getQuantity()).isEqualTo(3);
            assertThat(l.getLineTotal()).isEqualByComparingTo("30.00");   // 읽기 최적화로 미리 계산
        });
    }

    @Test
    void project_isDeterministic_soReplayYieldsIdenticalState() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderPlacedEvent same = event(orderId, customerId, productId);

        service.project(same);
        service.project(same);   // 리플레이(같은 이벤트 재소비)

        verify(repository, times(2)).save(captor.capture());
        OrderView first = captor.getAllValues().get(0);
        OrderView second = captor.getAllValues().get(1);

        // 두 번의 투영 결과가 완전히 동일 → 읽기 DB를 지우고 offset 0부터 재생해도 같은 상태에 수렴한다.
        assertThat(second.getOrderId()).isEqualTo(first.getOrderId());
        assertThat(second.getStatus()).isEqualTo(first.getStatus());
        assertThat(second.getTotalAmount()).isEqualByComparingTo(first.getTotalAmount());
        assertThat(second.getPlacedAt()).isEqualTo(first.getPlacedAt());   // 비결정적 시계를 썼다면 여기서 실패
        assertThat(second.getLines()).hasSameSizeAs(first.getLines());
        assertThat(second.getLines().getFirst().getLineTotal())
                .isEqualByComparingTo(first.getLines().getFirst().getLineTotal());
    }
}
