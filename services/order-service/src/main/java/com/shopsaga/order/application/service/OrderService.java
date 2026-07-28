package com.shopsaga.order.application.service;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.order.application.UseCase;
import com.shopsaga.order.application.port.in.GetOrderQuery;
import com.shopsaga.order.application.port.in.OrderView;
import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.application.port.in.PlaceOrderUseCase;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.order.application.port.out.SagaStarterPort;
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
 * <p><b>Phase 12(Saga): 주문 생성이 "시작 신호"만 된다.</b> 예전엔 이 메서드 안에서 결제까지 동기로 끝냈지만,
 * 이제는 주문을 PENDING 으로 저장하고 {@code OrderPlaced} 를 발행한 뒤 <b>즉시 반환</b>한다.
 * 재고 예약·결제·확정은 다른 서비스들이 이벤트를 주고받으며 진행한다(코레오그래피).
 * <ul>
 *   <li>저장과 이벤트 기록이 <b>한 트랜잭션</b>(outbox) → 이중 쓰기 없음(Phase 10).</li>
 *   <li>응답 시점의 status 는 <b>PENDING</b> — 확정 여부는 조회로 확인해야 한다(결과적 일관성).</li>
 *   <li>결제 거절/재고 부족은 더 이상 HTTP 에러가 아니다 → Saga가 주문을 CANCELLED 로 만든다.</li>
 * </ul>
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class OrderService implements PlaceOrderUseCase, GetOrderQuery {

    private final SaveOrderPort saveOrderPort;
    private final LoadOrderPort loadOrderPort;
    private final PublishOrderEventPort publishOrderEventPort;
    private final SagaStarterPort sagaStarterPort;

    @Override
    @Transactional
    public OrderView placeOrder(PlaceOrderCommand command) {
        Order order = Order.create(command.customerId());
        command.items().forEach(i -> order.addItem(i.productId(), i.quantity(), i.unitPrice()));

        OrderView saved = OrderView.from(saveOrderPort.save(order));
        publishOrderPlaced(order, command);   // 같은 트랜잭션의 outbox row (아직 Kafka 전송 아님)

        // Phase 13: Saga를 어떻게 시작할지는 모드에 따라 다르다(코레오그래피=무동작 / 오케스트레이션=커맨드 발행).
        //           주문 저장과 같은 트랜잭션이라 "주문만 저장되고 Saga는 안 뜨는" 상태가 없다.
        sagaStarterPort.start(order, command);

        log.info("주문 접수(Saga 시작) orderId={} customer={} 품목수={} total={} status={}",
                order.getId(), command.customerId(), command.items().size(),
                order.getTotalAmount(), order.getStatus());
        return saved;
    }

    private void publishOrderPlaced(Order order, PlaceOrderCommand command) {
        List<OrderPlacedEvent.Item> items = command.items().stream()
                .map(i -> new OrderPlacedEvent.Item(i.productId(), i.quantity(), i.unitPrice()))
                .toList();
        // 총액·발생시각을 이벤트에 담는다 — 소비자가 스스로 계산/시계를 읽지 않도록(투영 결정성·Phase 11).
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
