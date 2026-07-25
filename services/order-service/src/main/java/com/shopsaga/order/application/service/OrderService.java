package com.shopsaga.order.application.service;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.order.application.UseCase;
import com.shopsaga.order.application.port.in.GetOrderQuery;
import com.shopsaga.order.application.port.in.OrderView;
import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.application.port.in.PlaceOrderUseCase;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.PaymentGatewayPort;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.order.application.port.out.SaveOrderPort;
import com.shopsaga.order.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 주문 유스케이스 구현.
 *
 * <p>Phase 9: 재고 예약을 <b>비동기 이벤트</b>로 위임한다. order는 더 이상 재고를 직접 예약하지 않고
 * {@code OrderPlaced} 를 발행하며, inventory-service가 소비해 예약한다(결과적 일관성).
 * <ul>
 *   <li>주문 시점엔 재고가 확정되지 않는다(재고 부족이어도 주문은 CONFIRMED) → Phase 12 Saga가 보상.</li>
 *   <li>save 와 이벤트 발행이 한 원자 트랜잭션이 아니다(dual-write) → Phase 10 outbox가 해결.</li>
 *   <li>결제는 아직 동기(payment-service) — Phase 12에서 이벤트 흐름으로 전환.</li>
 * </ul>
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class OrderService implements PlaceOrderUseCase, GetOrderQuery {

    private final SaveOrderPort saveOrderPort;
    private final LoadOrderPort loadOrderPort;
    private final PaymentGatewayPort paymentGatewayPort;
    private final PublishOrderEventPort publishOrderEventPort;

    @Override
    @Transactional
    public OrderView placeOrder(PlaceOrderCommand command) {
        Order order = Order.create(command.customerId());
        command.items().forEach(i -> order.addItem(i.productId(), i.quantity(), i.unitPrice()));
        log.info("주문 생성 시작 orderId={} customer={} 품목수={}",
                order.getId(), command.customerId(), command.items().size());

        // 결제 = 원격 호출(payment-service, 여전히 동기). 거절 → 402, 통신 실패 → 502.
        UUID paymentId = paymentGatewayPort.capture(order.getId(), order.getTotalAmount());

        // 주문 확정 + 저장(로컬).
        order.confirm(paymentId);
        OrderView saved = OrderView.from(saveOrderPort.save(order));

        // 재고 예약을 이벤트로 위임 — OrderPlaced 발행(fire-and-forget). inventory가 비동기 예약.
        publishOrderPlaced(order, command);
        log.info("주문 확정 orderId={} paymentId={} total={}", order.getId(), paymentId, order.getTotalAmount());
        return saved;
    }

    private void publishOrderPlaced(Order order, PlaceOrderCommand command) {
        List<OrderPlacedEvent.Item> items = command.items().stream()
                .map(i -> new OrderPlacedEvent.Item(i.productId(), i.quantity(), i.unitPrice()))
                .toList();
        // Phase 11: 총액·발생시각을 이벤트에 담는다 — 읽기 모델이 스스로 계산/시계를 읽지 않도록(투영 결정성).
        publishOrderEventPort.orderPlaced(new OrderPlacedEvent(
                order.getId(), command.customerId(), items, order.getTotalAmount(), order.getCreatedAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView getOrder(UUID id) {
        Order order = loadOrderPort.loadById(id).orElseThrow(() -> new OrderNotFoundException(id));
        return OrderView.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderView> listOrders() {
        return loadOrderPort.loadAll().stream().map(OrderView::from).toList();
    }
}
