package com.shopsaga.orderquery.application.service;

import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 11/12: 투영의 <b>결정성</b>과 <b>단조 상태 전이</b> 검증.
 * (여기서 {@code Instant.now()} 같은 걸 쓰면 결정성 테스트가 깨진다 = 리플레이 불가 투영을 잡아내는 가드.)
 */
@ExtendWith(MockitoExtension.class)
class OrderViewProjectionServiceTest {

    @Mock
    OrderViewRepositoryPort repository;
    @InjectMocks
    OrderViewProjectionService service;
    @Captor
    ArgumentCaptor<OrderView> viewCaptor;
    @Captor
    ArgumentCaptor<Set<String>> overwritableCaptor;

    private static final Instant PLACED_AT = Instant.parse("2026-07-18T10:00:00Z");

    private OrderPlacedEvent placed(UUID orderId, UUID customerId, UUID productId) {
        return new OrderPlacedEvent(orderId, customerId,
                List.of(new OrderPlacedEvent.Item(productId, 3, new BigDecimal("10.00"))),
                new BigDecimal("30.00"), PLACED_AT);
    }

    @Test
    void onOrderPlaced_createsPendingViewFromEventValuesOnly() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        service.onOrderPlaced(placed(orderId, customerId, productId));

        verify(repository).upsertBase(viewCaptor.capture(), eq("PENDING"));
        OrderView view = viewCaptor.getValue();
        assertThat(view.getOrderId()).isEqualTo(orderId);
        assertThat(view.getCustomerId()).isEqualTo(customerId);
        assertThat(view.getStatus()).isEqualTo("PENDING");   // Saga 시작 시점 상태(예전엔 바로 CONFIRMED였다)
        assertThat(view.getTotalAmount()).isEqualByComparingTo("30.00");
        assertThat(view.getPlacedAt()).isEqualTo(PLACED_AT);   // ★ 이벤트의 시각(투영 시점의 시계가 아님)
        assertThat(view.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getProductId()).isEqualTo(productId);
            assertThat(l.getLineTotal()).isEqualByComparingTo("30.00");   // 읽기 최적화로 미리 계산
        });
    }

    @Test
    void onOrderPlaced_isDeterministic_soReplayYieldsIdenticalState() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderPlacedEvent same = placed(orderId, customerId, productId);

        service.onOrderPlaced(same);
        service.onOrderPlaced(same);   // 리플레이(같은 이벤트 재소비)

        verify(repository, times(2)).upsertBase(viewCaptor.capture(), eq("PENDING"));
        OrderView first = viewCaptor.getAllValues().get(0);
        OrderView second = viewCaptor.getAllValues().get(1);

        // 두 번의 투영 결과가 완전히 동일 → 읽기 DB를 지우고 offset 0부터 재생해도 같은 상태에 수렴한다.
        assertThat(second.getOrderId()).isEqualTo(first.getOrderId());
        assertThat(second.getTotalAmount()).isEqualByComparingTo(first.getTotalAmount());
        assertThat(second.getPlacedAt()).isEqualTo(first.getPlacedAt());   // 비결정적 시계를 썼다면 실패
        assertThat(second.getLines().getFirst().getLineTotal())
                .isEqualByComparingTo(first.getLines().getFirst().getLineTotal());
    }

    @Test
    void inventoryReserved_onlyOverwritesPending() {
        UUID orderId = UUID.randomUUID();

        service.onInventoryReserved(new InventoryReservedEvent(
                orderId, UUID.randomUUID(), new BigDecimal("30.00"), PLACED_AT));

        verify(repository).applyStatusIfCurrentIn(eq(orderId), eq("INVENTORY_RESERVED"), overwritableCaptor.capture());
        // 자기보다 앞선 상태(PENDING)만 덮어쓴다 → 이미 확정/취소된 주문을 되돌리지 않는다
        assertThat(overwritableCaptor.getValue()).containsExactly("PENDING");
    }

    @Test
    void terminalStatuses_overwriteEarlierStatesButNotEachOther() {
        UUID confirmedOrder = UUID.randomUUID();
        UUID cancelledOrder = UUID.randomUUID();

        service.onOrderConfirmed(new OrderConfirmedEvent(confirmedOrder, UUID.randomUUID(), PLACED_AT));
        service.onOrderCancelled(new OrderCancelledEvent(cancelledOrder, "결제 거절", PLACED_AT));

        verify(repository).applyStatusIfCurrentIn(eq(confirmedOrder), eq("CONFIRMED"), overwritableCaptor.capture());
        assertThat(overwritableCaptor.getValue())
                .containsExactlyInAnyOrder("PENDING", "INVENTORY_RESERVED");   // CANCELLED 는 덮지 않음

        verify(repository).applyStatusIfCurrentIn(eq(cancelledOrder), eq("CANCELLED"), overwritableCaptor.capture());
        assertThat(overwritableCaptor.getValue())
                .containsExactlyInAnyOrder("PENDING", "INVENTORY_RESERVED");   // CONFIRMED 는 덮지 않음
    }

    @Test
    void statusTransition_neverCallsFullReplace() {
        // 상태 전이는 조건부 갱신만 사용해야 한다 — 본문 덮어쓰기를 하면 상태가 되돌아갈 수 있다.
        service.onOrderConfirmed(new OrderConfirmedEvent(UUID.randomUUID(), UUID.randomUUID(), PLACED_AT));

        verify(repository, times(0)).upsertBase(any(), any());
    }
}
