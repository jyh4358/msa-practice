package com.shopsaga.payment.application.port.in;

import com.shopsaga.events.InventoryReservedEvent;

import java.util.UUID;

/**
 * 인바운드 포트: 결제의 Saga 참여(Phase 12).
 *
 * <p>Phase 2~11에서 결제는 order가 <b>동기 REST로 호출</b>하는 서비스였다. 이제는 재고 예약 성공 사실을
 * <b>스스로 듣고</b> 청구한다 — 호출자가 없다. 결과(성공/거절)는 이벤트로 알린다.
 *
 * <p>거절은 <b>예외가 아니라 정상적인 업무 결과</b>다: HTTP라면 402를 반환했겠지만, 이벤트 흐름에서는
 * {@code PaymentDeclined} 라는 사실을 발행하고 소비는 정상 종료된다(그래야 재시도 루프에 빠지지 않는다).
 */
public interface PaymentSagaUseCase {

    void onInventoryReserved(UUID messageId, InventoryReservedEvent event);
}
