package com.shopsaga.order.application.service;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.order.application.UseCase;
import com.shopsaga.order.application.port.in.GetOrderQuery;
import com.shopsaga.order.application.port.in.OrderView;
import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.application.port.in.PlaceOrderResult;
import com.shopsaga.order.application.port.in.PlaceOrderUseCase;
import com.shopsaga.order.application.port.in.StockPrecheck;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.order.application.port.out.SagaStarterPort;
import com.shopsaga.order.application.port.out.SaveOrderPort;
import com.shopsaga.order.application.port.out.StockAvailabilityPort;
import com.shopsaga.order.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    /** Phase 14: 재고 사전 확인(부가 기능). 토글로 끌 수 있으므로 빈이 없을 수도 있다. */
    private final ObjectProvider<StockAvailabilityPort> stockAvailabilityPort;

    /**
     * 사전 확인이 "부족"이라고 해도 <b>기본은 그대로 접수</b>한다(참고용). true 로 켜면 409 로 빠르게 거절한다.
     * 끄고 켜 보면 "빠른 실패 UX"와 "Saga 단일 판정" 사이의 트레이드오프가 눈에 보인다.
     */
    @Value("${order.stock-precheck.reject-on-insufficient:false}")
    private boolean rejectOnInsufficient;

    @Override
    @Transactional
    public PlaceOrderResult placeOrder(PlaceOrderCommand command) {
        // ⚠️ 사전 확인은 트랜잭션 안에서 원격 호출을 한다는 점에서 이상적이진 않다(커넥션 점유).
        //    TimeLimiter 로 상한이 걸려 있어 허용한 것이며, 상한이 없다면 트랜잭션 밖으로 빼야 한다.
        StockPrecheck precheck = precheck(command);
        if (rejectOnInsufficient && precheck.status() == StockPrecheck.Status.INSUFFICIENT) {
            throw new StockPrecheckRejectedException(precheck.detail());
        }

        Order order = Order.create(command.customerId());
        command.items().forEach(i -> order.addItem(i.productId(), i.quantity(), i.unitPrice()));

        OrderView saved = OrderView.from(saveOrderPort.save(order));
        publishOrderPlaced(order, command);   // 같은 트랜잭션의 outbox row (아직 Kafka 전송 아님)

        // Phase 13: Saga를 어떻게 시작할지는 모드에 따라 다르다(코레오그래피=무동작 / 오케스트레이션=커맨드 발행).
        //           주문 저장과 같은 트랜잭션이라 "주문만 저장되고 Saga는 안 뜨는" 상태가 없다.
        sagaStarterPort.start(order, command);

        log.info("주문 접수(Saga 시작) orderId={} customer={} 품목수={} total={} status={} 사전확인={}",
                order.getId(), command.customerId(), command.items().size(),
                order.getTotalAmount(), order.getStatus(), precheck.status());
        return new PlaceOrderResult(saved, precheck);
    }

    /** 사전 확인 어댑터가 꺼져 있으면 "모름"으로 취급한다 — 없어도 주문은 그대로 흐른다. */
    private StockPrecheck precheck(PlaceOrderCommand command) {
        StockAvailabilityPort port = stockAvailabilityPort.getIfAvailable();
        if (port == null) {
            return StockPrecheck.unknown("사전 확인 비활성");
        }
        List<StockAvailabilityPort.Line> lines = command.items().stream()
                .map(i -> new StockAvailabilityPort.Line(i.productId(), i.quantity()))
                .toList();
        return port.precheck(lines);
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
