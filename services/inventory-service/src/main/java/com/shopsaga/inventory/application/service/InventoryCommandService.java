package com.shopsaga.inventory.application.service;

import com.shopsaga.events.commands.ReleaseStockCommand;
import com.shopsaga.events.commands.ReserveStockCommand;
import com.shopsaga.inventory.application.UseCase;
import com.shopsaga.inventory.application.port.in.InventoryCommandUseCase;
import com.shopsaga.inventory.application.port.out.ProcessedCommandPort;
import com.shopsaga.inventory.domain.InsufficientStockException;
import com.shopsaga.outbox.CommandKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 13: 재고 서비스의 커맨드 핸들러 — 지시받은 일을 하고 <b>반드시 결과를 돌려준다</b>.
 *
 * <p>모든 핸들러가 같은 골격이다:
 * <ol>
 *   <li>결정적 키로 <b>중복 확인</b> → 중복이면 저장된 결과로 <b>리플라이 재전송</b>(무시하지 않는다).</li>
 *   <li>처음이면 작업 수행 → 결과 리플라이 + 사실 이벤트 + 처리기록(한 트랜잭션).</li>
 * </ol>
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class InventoryCommandService implements InventoryCommandUseCase {

    static final String CMD_RESERVE = "ReserveStock";
    static final String CMD_RELEASE = "ReleaseStock";

    private final InventoryCommandTransactions transactions;

    @Override
    public void onReserveStock(ReserveStockCommand command) {
        UUID commandKey = CommandKeys.of(command.sagaId(), CMD_RESERVE);
        Optional<ProcessedCommandPort.PriorOutcome> prior = transactions.priorOutcome(commandKey);
        if (prior.isPresent()) {
            transactions.replayReply(command.sagaId(), command.orderId(), prior.get());
            return;
        }

        Map<UUID, Integer> quantityByProduct = new HashMap<>();
        command.items().forEach(i -> quantityByProduct.merge(i.productId(), i.quantity(), Integer::sum));

        try {
            transactions.reserve(commandKey, command, quantityByProduct);
        } catch (InsufficientStockException | StockNotFoundException e) {
            // 예약 트랜잭션은 롤백됐다(부분 차감 없음). 실패 사실만 새 트랜잭션에서 응답한다.
            transactions.recordReserveFailure(commandKey, command, e.getMessage());
        }
    }

    @Override
    public void onReleaseStock(ReleaseStockCommand command) {
        UUID commandKey = CommandKeys.of(command.sagaId(), CMD_RELEASE);
        Optional<ProcessedCommandPort.PriorOutcome> prior = transactions.priorOutcome(commandKey);
        if (prior.isPresent()) {
            transactions.replayReply(command.sagaId(), command.orderId(), prior.get());
            return;
        }
        transactions.release(commandKey, command.sagaId(), command.orderId());
    }
}
