package com.shopsaga.inventory.adapter.in.event;

import com.shopsaga.events.Topics;
import com.shopsaga.events.commands.ReleaseStockCommand;
import com.shopsaga.events.commands.ReserveStockCommand;
import com.shopsaga.inventory.application.port.in.InventoryCommandUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Phase 13: 조정자의 <b>커맨드</b>를 받는 인바운드 어댑터.
 *
 * <p>Phase 12의 {@code OrderEventListener}(사실을 듣고 스스로 판단)와 대비된다 —
 * 여기서는 "무엇을 하라"는 지시가 명시적으로 온다.
 *
 * <p>⚠️ 멱등성은 메시지 id가 아니라 <b>커맨드 키</b>로 판단한다(유스케이스 내부) —
 * 타임아웃 sweep이 재전송하면 메시지 id는 새로 생기기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "saga.mode", havingValue = "orchestration", matchIfMissing = true)
@KafkaListener(topics = Topics.SAGA_COMMANDS, groupId = "inventory-service")
@RequiredArgsConstructor
@Slf4j
class SagaCommandListener {

    private final InventoryCommandUseCase inventoryCommandUseCase;

    @KafkaHandler
    void onReserveStock(ReserveStockCommand command) {
        log.info("[커맨드] ReserveStock 수신 sagaId={} orderId={} 품목수={}",
                command.sagaId(), command.orderId(), command.items().size());
        inventoryCommandUseCase.onReserveStock(command);
    }

    @KafkaHandler
    void onReleaseStock(ReleaseStockCommand command) {
        log.info("[커맨드] ReleaseStock 수신(보상 지시) sagaId={} orderId={}",
                command.sagaId(), command.orderId());
        inventoryCommandUseCase.onReleaseStock(command);
    }

    @KafkaHandler(isDefault = true)
    void onUnknown(Object command) {
        // ChargePayment 는 payment 에게 간 지시 — 재고는 무시한다(같은 토픽을 공유하므로 도착은 한다).
        log.debug("내 커맨드가 아님 — 무시 type={}", command.getClass().getSimpleName());
    }
}
