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
 * 유스케이스 구현. Phase 1 모놀리스: placeOrder 가 주문 생성 + 재고 차감 + 결제를
 * 하나의 @Transactional 안에서 처리한다 → 한 단계라도 실패하면 전부 롤백(ACID).
 * 반환은 도메인이 아니라 불변 뷰(OrderView/StockView) — 도메인이 어댑터로 새지 않는다.
 */
@UseCase
@RequiredArgsConstructor
class OrderService implements PlaceOrderUseCase, GetOrderQuery, GetStockQuery {

    private final SaveOrderPort saveOrderPort;
    private final LoadOrderPort loadOrderPort;
    private final LoadStockPort loadStockPort;
    private final ReserveStockPort reserveStockPort;

    @Override
    @Transactional
    public OrderView placeOrder(PlaceOrderCommand command) {
        // ── Phase 1 ACID 기준선 ──
        // 재고 차감 + 결제 캡처 + 주문 저장이 하나의 로컬 DB 트랜잭션을 공유 → 원자적.
        // (Phase 2에서 inventory/payment 가 원격 서비스로 분리되면 이 트랜잭션이 사라짐 → Phase 12 Saga.)
        Order order = Order.create(command.customerId());
        command.items().forEach(i -> order.addItem(i.productId(), i.quantity(), i.unitPrice()));

        // (1) 재고 예약 — 상품ID 정렬 순서로 차감(어댑터가 비관적 락 적용):
        //     · 락은 ReserveStockPort 뒤에 숨김(애플리케이션은 "예약" 의도만)
        //     · 상품별 수량 합산(중복 차감 방지) + TreeMap 정렬로 다중 상품 교착(deadlock) 회피
        Map<UUID, Integer> quantityByProduct = new TreeMap<>();
        command.items().forEach(i ->
                quantityByProduct.merge(i.productId(), i.quantity(), Integer::sum));
        quantityByProduct.forEach(reserveStockPort::reserve);

        // (2) 결제 캡처 — 거절되면 PaymentDeclinedException → (1)의 재고 차감까지 롤백.
        order.capturePayment();

        // (3) 주문 저장(주문+항목+결제 한 번에). 같은 트랜잭션이므로 위 단계와 원자적.
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
