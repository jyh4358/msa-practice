package com.shopsaga.order.application.service;

import com.shopsaga.order.application.UseCase;
import com.shopsaga.order.application.port.in.GetOrderQuery;
import com.shopsaga.order.application.port.in.GetStockQuery;
import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.application.port.in.PlaceOrderUseCase;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.LoadStockPort;
import com.shopsaga.order.application.port.out.SaveOrderPort;
import com.shopsaga.order.application.port.out.SaveStockPort;
import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.StockItem;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 유스케이스 구현. Phase 1 모놀리스: placeOrder 가 주문 생성 + 재고 차감 + 결제를
 * 하나의 @Transactional 안에서 처리한다 → 한 단계라도 실패하면 전부 롤백(ACID).
 */
@UseCase
class OrderService implements PlaceOrderUseCase, GetOrderQuery, GetStockQuery {

    private final SaveOrderPort saveOrderPort;
    private final LoadOrderPort loadOrderPort;
    private final LoadStockPort loadStockPort;
    private final SaveStockPort saveStockPort;

    OrderService(SaveOrderPort saveOrderPort, LoadOrderPort loadOrderPort,
                 LoadStockPort loadStockPort, SaveStockPort saveStockPort) {
        this.saveOrderPort = saveOrderPort;
        this.loadOrderPort = loadOrderPort;
        this.loadStockPort = loadStockPort;
        this.saveStockPort = saveStockPort;
    }

    @Override
    @Transactional
    public Order placeOrder(PlaceOrderCommand command) {
        // ── Phase 1 ACID 기준선 ───────────────────────────────────────────────
        // 재고 차감 + 결제 캡처 + 주문 저장이 하나의 로컬 DB 트랜잭션을 공유 → 원자적.
        // 한 단계라도 실패하면 전부 롤백(결제 거절 시 앞서 차감한 재고도 원복).
        // ⚠️ Phase 2에서 inventory/payment 가 원격 서비스로 분리되면 이 트랜잭션은 그들을 더 이상 감싸지
        //    못한다 → 결제 거절이 원격 재고 예약을 자동 원복 못 함 → 이 잃어버린 원자성이 Phase 12 Saga의 동기.
        Order order = Order.create(command.customerId());
        command.items().forEach(i -> order.addItem(i.productId(), i.quantity(), i.unitPrice()));

        // (1) 재고 차감 — 상품별 수량 합산 후 한 번씩 예약(같은 상품 중복 차감 방지).
        // ⚠️ 동시성 한계(의도적, Phase 1 단일 사용자 가정): load→reserve→save 는 락/@Version 이 없어
        //    동시 주문 시 lost-update(oversell) 가능. 해결은 낙관적 락(@Version+재시도) 또는
        //    원자적 UPDATE(SET available_quantity = available_quantity - :q WHERE available_quantity >= :q).
        Map<UUID, Integer> quantityByProduct = new LinkedHashMap<>();
        command.items().forEach(i ->
                quantityByProduct.merge(i.productId(), i.quantity(), Integer::sum));
        quantityByProduct.forEach((productId, quantity) -> {
            StockItem stock = loadStockPort.loadByProductId(productId)
                    .orElseThrow(() -> new StockNotFoundException(productId));
            stock.reserve(quantity);          // 부족하면 InsufficientStockException → 롤백
            saveStockPort.save(stock);
        });

        // (2) 결제 캡처 — 거절되면 PaymentDeclinedException → (1)의 재고 차감까지 롤백.
        order.capturePayment();

        // (3) 주문 저장(주문+항목+결제 한 번에). 같은 트랜잭션이므로 위 단계와 원자적.
        return saveOrderPort.save(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Order getOrder(UUID id) {
        return loadOrderPort.loadById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> listOrders() {
        return loadOrderPort.loadAll();
    }

    @Override
    @Transactional(readOnly = true)
    public StockItem getStock(UUID productId) {
        return loadStockPort.loadByProductId(productId)
                .orElseThrow(() -> new StockNotFoundException(productId));
    }
}
