package com.shopsaga.inventory.application.service;

import com.shopsaga.events.InventoryFailedEvent;
import com.shopsaga.events.InventoryReleasedEvent;
import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.inventory.application.port.out.ProcessedMessagePort;
import com.shopsaga.inventory.application.port.out.PublishInventoryEventPort;
import com.shopsaga.inventory.application.port.out.ReleaseStockPort;
import com.shopsaga.inventory.application.port.out.ReserveStockPort;
import com.shopsaga.inventory.application.port.out.StockReservationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Phase 12: 재고 Saga의 <b>트랜잭션 단위</b>들. {@link StockService} 가 이 빈을 호출해 조합한다.
 *
 * <p><b>왜 별도 빈인가:</b> 예약이 실패하면 두 가지를 동시에 원한다 —
 * ① 이미 차감된 일부 품목까지 <b>되돌리기</b>(롤백), ② "실패했다"는 사실을 <b>발행하기</b>.
 * 롤백된 트랜잭션에서는 outbox 에 쓴 것도 함께 사라지므로, 둘을 <b>서로 다른 트랜잭션</b>으로 나눠야 한다.
 * 같은 클래스 안에서 호출하면 프록시를 우회해 {@code @Transactional} 이 걸리지 않으므로 빈을 분리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class StockSagaTransactions {

    private final ReserveStockPort reserveStockPort;
    private final ReleaseStockPort releaseStockPort;
    private final StockReservationPort stockReservationPort;
    private final PublishInventoryEventPort publishInventoryEventPort;
    private final ProcessedMessagePort processedMessagePort;

    /**
     * 트랜잭션 ①: 예약 + 원장 기록 + InventoryReserved 발행 + 처리기록을 <b>한 커밋</b>으로.
     * 재고가 부족하면 예외가 전파되어 <b>전부 롤백</b>된다(부분 차감 없음).
     */
    @Transactional
    void reserve(UUID messageId, OrderPlacedEvent event, Map<UUID, Integer> quantityByProduct) {
        // 상품ID 정렬(TreeMap)로 교착 회피 — 여러 주문이 같은 상품들을 동시에 예약해도 락 획득 순서를 일관되게.
        new TreeMap<>(quantityByProduct).forEach(reserveStockPort::reserve);

        stockReservationPort.record(event.orderId(), quantityByProduct);   // 보상 대비 원장
        publishInventoryEventPort.inventoryReserved(new InventoryReservedEvent(
                event.orderId(), event.customerId(), event.totalAmount(), Instant.now()));
        processedMessagePort.markProcessed(messageId);

        log.info("재고 예약 성공 orderId={} 품목수={}", event.orderId(), quantityByProduct.size());
    }

    /**
     * 트랜잭션 ②: 예약 실패 사실 발행 + 처리기록. ①이 롤백된 뒤 <b>새 트랜잭션</b>에서 실행된다.
     * 이 이벤트가 있어야 order가 주문을 취소할 수 있다(Phase 9의 "매달린 주문" 문제 해결).
     */
    @Transactional
    void recordFailure(UUID messageId, OrderPlacedEvent event, String reason) {
        publishInventoryEventPort.inventoryFailed(
                new InventoryFailedEvent(event.orderId(), reason, Instant.now()));
        processedMessagePort.markProcessed(messageId);
        log.warn("재고 예약 실패 → InventoryFailed 발행 orderId={} reason={}", event.orderId(), reason);
    }

    /**
     * 보상 트랜잭션: 원장을 꺼내(삭제) 수량을 되돌리고 InventoryReleased 발행 + 처리기록을 한 커밋으로.
     * 원장이 비어 있으면(이미 보상됐거나 예약조차 실패한 주문) 되돌릴 것이 없다 → 자연 멱등.
     */
    @Transactional
    void release(UUID messageId, UUID orderId) {
        Map<UUID, Integer> reserved = stockReservationPort.takeForRelease(orderId);
        if (reserved.isEmpty()) {
            log.info("해제할 예약 없음(이미 보상됐거나 예약 실패한 주문) orderId={}", orderId);
        } else {
            new TreeMap<>(reserved).forEach(releaseStockPort::release);
            publishInventoryEventPort.inventoryReleased(new InventoryReleasedEvent(orderId, Instant.now()));
            log.info("보상 완료 — 재고 해제 orderId={} 품목수={}", orderId, reserved.size());
        }
        processedMessagePort.markProcessed(messageId);
    }

    /** 멱등 가드용 조회(자체 트랜잭션). */
    @Transactional(readOnly = true)
    boolean alreadyProcessed(UUID messageId) {
        return processedMessagePort.isAlreadyProcessed(messageId);
    }
}
