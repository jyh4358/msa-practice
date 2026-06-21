package com.shopsaga.order.application.service;

import com.shopsaga.order.application.UseCase;
import com.shopsaga.order.application.port.in.GetOrderQuery;
import com.shopsaga.order.application.port.in.GetStockQuery;
import com.shopsaga.order.application.port.in.OrderView;
import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.application.port.in.PlaceOrderUseCase;
import com.shopsaga.order.application.port.in.StockView;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.LoadStockPort;
import com.shopsaga.order.application.port.out.PaymentGatewayPort;
import com.shopsaga.order.application.port.out.ReserveStockPort;
import com.shopsaga.order.application.port.out.SaveOrderPort;
import com.shopsaga.order.domain.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 유스케이스 구현. Phase 2: 결제가 원격(payment-service)이 되면서 단일 트랜잭션이 깨진다.
 * 재고 차감은 로컬 트랜잭션이지만, 결제는 원격 REST 호출이라 같은 트랜잭션으로 묶이지 않는다.
 */
@UseCase
@RequiredArgsConstructor
class OrderService implements PlaceOrderUseCase, GetOrderQuery, GetStockQuery {

    private final SaveOrderPort saveOrderPort;
    private final LoadOrderPort loadOrderPort;
    private final LoadStockPort loadStockPort;
    private final ReserveStockPort reserveStockPort;
    private final PaymentGatewayPort paymentGatewayPort;

    @Override
    @Transactional
    public OrderView placeOrder(PlaceOrderCommand command) {
        Order order = Order.create(command.customerId());
        command.items().forEach(i -> order.addItem(i.productId(), i.quantity(), i.unitPrice()));

        // (1) 재고 예약 — 로컬, 비관적 락(상품ID 정렬로 교착 회피).
        Map<UUID, Integer> quantityByProduct = new TreeMap<>();
        command.items().forEach(i ->
                quantityByProduct.merge(i.productId(), i.quantity(), Integer::sum));
        quantityByProduct.forEach(reserveStockPort::reserve);

        // (2) 결제 = 원격 호출(payment-service). 거절 → PaymentDeclinedException, 통신 실패 → PaymentGatewayException.
        //     ⚠️ 단일 트랜잭션 소멸: 결제는 이 로컬 @Transactional 에 묶이지 않는다.
        //        · 재고 비관적 락이 이 원격 호출 구간 내내 유지됨(원격 지연만큼 락 점유 → 처리량 저하).
        //        · 결제 성공 후 (3) 저장이 실패하면 결제는 원격에 남아 자동 원복 불가(= orphaned payment).
        //        이 잃어버린 원자성이 Phase 12 Saga(보상 트랜잭션)의 동기다.
        UUID paymentId = paymentGatewayPort.capture(order.getId(), order.getTotalAmount());

        // (3) 주문 확정 + 저장(로컬). 결제 거절/통신 실패 시엔 여기 도달 전에 예외 → 재고 차감은 로컬이라 롤백된다.
        order.confirm(paymentId);
        return OrderView.from(saveOrderPort.save(order));
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

    @Override
    @Transactional(readOnly = true)
    public StockView getStock(UUID productId) {
        return loadStockPort.loadByProductId(productId)
                .map(StockView::from)
                .orElseThrow(() -> new StockNotFoundException(productId));
    }
}
