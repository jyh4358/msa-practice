package com.shopsaga.inventory.application.service;

import com.shopsaga.events.InventoryFailedEvent;
import com.shopsaga.events.InventoryReleasedEvent;
import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.commands.ReserveStockCommand;
import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.inventory.application.port.out.ProcessedCommandPort;
import com.shopsaga.inventory.application.port.out.PublishInventoryEventPort;
import com.shopsaga.inventory.application.port.out.PublishSagaReplyPort;
import com.shopsaga.inventory.application.port.out.ReleaseStockPort;
import com.shopsaga.inventory.application.port.out.ReserveStockPort;
import com.shopsaga.inventory.application.port.out.StockReservationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Phase 13: 재고 커맨드 처리의 <b>트랜잭션 단위</b>들.
 *
 * <p>Phase 12와 같은 이유로 성공/실패를 <b>다른 트랜잭션</b>으로 나눈다:
 * 예약이 중간에 실패하면 이미 차감된 품목을 롤백해야 하는데, 롤백하면 같은 트랜잭션에 쓴
 * "실패 리플라이"까지 사라져 조정자가 영영 기다리게 된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class InventoryCommandTransactions {

    private final ReserveStockPort reserveStockPort;
    private final ReleaseStockPort releaseStockPort;
    private final StockReservationPort stockReservationPort;
    private final PublishSagaReplyPort publishSagaReplyPort;
    private final PublishInventoryEventPort publishInventoryEventPort;
    private final ProcessedCommandPort processedCommandPort;

    /** 예약 + 원장 기록 + 리플라이(STOCK_RESERVED) + 사실 이벤트 + 처리기록을 한 커밋으로. */
    @Transactional
    void reserve(UUID commandKey, ReserveStockCommand command, Map<UUID, Integer> quantityByProduct) {
        // 상품ID 정렬(TreeMap)로 교착 회피.
        new TreeMap<>(quantityByProduct).forEach(reserveStockPort::reserve);
        stockReservationPort.record(command.orderId(), quantityByProduct);

        Instant now = Instant.now();
        publishSagaReplyPort.reply(SagaReply.ok(
                command.sagaId(), command.orderId(), SagaReply.Kind.STOCK_RESERVED, now));
        // 사실 이벤트도 함께 발행 — 읽기 모델(CQRS)처럼 Saga와 무관한 구독자를 위한 것이다.
        // 리플라이(조정자용)와 이벤트(세상용)는 목적이 다르므로 둘 다 낸다.
        publishInventoryEventPort.inventoryReserved(new InventoryReservedEvent(
                command.orderId(), command.customerId(), command.totalAmount(), now));
        processedCommandPort.record(commandKey, command.sagaId(), command.orderId(),
                SagaReply.Kind.STOCK_RESERVED, null);

        log.info("[커맨드] 재고 예약 성공 sagaId={} orderId={} 품목수={}",
                command.sagaId(), command.orderId(), quantityByProduct.size());
    }

    /** 예약 실패 리플라이 + 사실 이벤트 + 처리기록(새 트랜잭션 — 위 트랜잭션이 롤백된 뒤 실행). */
    @Transactional
    void recordReserveFailure(UUID commandKey, ReserveStockCommand command, String reason) {
        Instant now = Instant.now();
        publishSagaReplyPort.reply(SagaReply.failed(
                command.sagaId(), command.orderId(), SagaReply.Kind.STOCK_RESERVATION_FAILED, reason, now));
        publishInventoryEventPort.inventoryFailed(new InventoryFailedEvent(command.orderId(), reason, now));
        processedCommandPort.record(commandKey, command.sagaId(), command.orderId(),
                SagaReply.Kind.STOCK_RESERVATION_FAILED, reason);

        log.warn("[커맨드] 재고 예약 실패 → 실패 리플라이 sagaId={} orderId={} reason={}",
                command.sagaId(), command.orderId(), reason);
    }

    /** 보상: 원장을 꺼내(삭제) 되돌리고 리플라이(STOCK_RELEASED) + 사실 이벤트 + 처리기록을 한 커밋으로. */
    @Transactional
    void release(UUID commandKey, UUID sagaId, UUID orderId) {
        Map<UUID, Integer> reserved = stockReservationPort.takeForRelease(orderId);
        Instant now = Instant.now();
        if (reserved.isEmpty()) {
            // 예약이 없었거나 이미 보상됨 — 되돌릴 게 없어도 "완료"로 응답해야 Saga가 끝난다.
            log.info("[커맨드] 해제할 예약 없음(이미 보상됐거나 예약 실패) orderId={}", orderId);
        } else {
            new TreeMap<>(reserved).forEach(releaseStockPort::release);
            publishInventoryEventPort.inventoryReleased(new InventoryReleasedEvent(orderId, now));
            log.info("[커맨드] 보상 완료 — 재고 해제 sagaId={} orderId={} 품목수={}", sagaId, orderId, reserved.size());
        }
        publishSagaReplyPort.reply(SagaReply.ok(sagaId, orderId, SagaReply.Kind.STOCK_RELEASED, now));
        processedCommandPort.record(commandKey, sagaId, orderId, SagaReply.Kind.STOCK_RELEASED, null);
    }

    /**
     * 중복 커맨드 — 저장해 둔 결과로 <b>같은 리플라이를 다시</b> 보낸다.
     * (조용히 무시하면 조정자가 영영 기다린다. 재전송의 목적은 "응답을 받는 것"이므로 응답해 줘야 한다.)
     */
    @Transactional
    void replayReply(UUID sagaId, UUID orderId, ProcessedCommandPort.PriorOutcome prior) {
        Instant now = Instant.now();
        publishSagaReplyPort.reply(new SagaReply(
                sagaId, orderId, prior.kind(), null, null, prior.reason(), now));
        log.info("[커맨드] 중복 — 이전 결과로 리플라이 재전송 sagaId={} kind={}", sagaId, prior.kind());
    }

    @Transactional(readOnly = true)
    Optional<ProcessedCommandPort.PriorOutcome> priorOutcome(UUID commandKey) {
        return processedCommandPort.findOutcome(commandKey);
    }
}
