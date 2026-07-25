package com.shopsaga.inventory.application.service;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.inventory.application.UseCase;
import com.shopsaga.inventory.application.port.in.GetStockQuery;
import com.shopsaga.inventory.application.port.in.InventorySagaUseCase;
import com.shopsaga.inventory.application.port.in.StockView;
import com.shopsaga.inventory.application.port.out.LoadStockPort;
import com.shopsaga.inventory.domain.InsufficientStockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 재고 유스케이스 구현 — 조회 + Saga 참여(예약/보상)의 <b>흐름 조합</b>.
 *
 * <p><b>Phase 12에서 달라진 점:</b> 예약 결과를 <b>이벤트로 알린다</b>. Phase 9에서는 재고가 부족해도
 * 로그만 남겨 주문이 영원히 매달려 있었다(정합성 구멍). 이제 실패도 사실로 발행되어 order가 주문을 취소한다.
 * 성공 시엔 무엇을 잡았는지 원장에 남겨 두고, 결제가 거절되면 그 원장을 보고 <b>보상</b>한다.
 *
 * <p>이 클래스는 <b>트랜잭션 경계를 조합</b>만 한다(실제 단위는 {@link StockSagaTransactions}) —
 * 성공/실패가 각각 독립된 커밋이어야 하기 때문이다(실패는 롤백하되 실패 이벤트는 남아야 한다).
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class StockService implements GetStockQuery, InventorySagaUseCase {

    private final LoadStockPort loadStockPort;
    private final StockSagaTransactions transactions;

    @Override
    @Transactional(readOnly = true)
    public StockView getStock(UUID productId) {
        return loadStockPort.loadByProductId(productId)
                .map(StockView::from)
                .orElseThrow(() -> new StockNotFoundException(productId));
    }

    @Override
    public void onOrderPlaced(UUID messageId, OrderPlacedEvent event) {
        if (transactions.alreadyProcessed(messageId)) {
            log.info("이미 처리된 메시지 — 예약 건너뜀 messageId={} orderId={}", messageId, event.orderId());
            return;
        }
        Map<UUID, Integer> quantityByProduct = aggregateByProduct(event);
        try {
            transactions.reserve(messageId, event, quantityByProduct);
        } catch (InsufficientStockException | StockNotFoundException e) {
            // 예약 트랜잭션은 롤백됐다(부분 차감 없음). 실패 사실만 새 트랜잭션에서 발행한다.
            transactions.recordFailure(messageId, event, e.getMessage());
        }
    }

    @Override
    public void onPaymentDeclined(UUID messageId, PaymentDeclinedEvent event) {
        if (transactions.alreadyProcessed(messageId)) {
            log.info("이미 처리된 메시지 — 보상 건너뜀 messageId={} orderId={}", messageId, event.orderId());
            return;
        }
        transactions.release(messageId, event.orderId());
    }

    private Map<UUID, Integer> aggregateByProduct(OrderPlacedEvent event) {
        Map<UUID, Integer> quantityByProduct = new HashMap<>();
        event.items().forEach(i -> quantityByProduct.merge(i.productId(), i.quantity(), Integer::sum));
        return quantityByProduct;
    }
}
