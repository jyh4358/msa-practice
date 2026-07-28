package com.shopsaga.events.commands;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 14: <b>결제 보상 커맨드</b> — 오케스트레이터가 payment에게 "이 결제를 환불하라"라고 지시한다.
 *
 * <h2>왜 Phase 14에서 생겼나 — Phase 13이 남긴 '고아 결제'</h2>
 * Phase 13의 타임아웃 sweep 은 응답 없는 결제 단계를 <b>포기</b>하고 재고를 되돌린 뒤 주문을 취소한다.
 * 그런데 죽어 있던 payment-service 가 되살아나면 큐에 남아 있던 {@code ChargePayment} 를 <b>뒤늦게</b> 수행한다
 * → 주문은 CANCELLED 인데 결제만 살아남는다. 이것이 실측으로 확인된 결함
 * (docs/PHASE-13-SAGA-ORCHESTRATION.md §7.2).
 *
 * <h2>해법이 '무시'가 아닌 이유</h2>
 * 늦게 온 성공 리플라이를 그냥 버리면 <b>돈은 이미 빠져나간 상태</b>로 남는다.
 * Saga 에서 이미 일어난 일은 롤백할 수 없다 — <b>상쇄(semantic undo)</b>해야 한다.
 * 그래서 조정자는 "늦게 성공한 결제"를 보면 환불을 지시한다. 보상이 보상을 부르는 셈이지만,
 * 그것이 분산 트랜잭션 없이 최종 정합성을 지키는 정직한 방법이다.
 *
 * <p>멱등성은 결정적 커맨드 키({@code CommandKeys.of(sagaId, "RefundPayment")})가 보장한다 —
 * 같은 지시가 두 번 와도 환불은 한 번뿐이고, 두 번째는 저장된 결과만 다시 리플라이한다.
 */
public record RefundPaymentCommand(
        UUID sagaId,
        UUID orderId,
        /** 되돌릴 결제 id — 늦게 도착한 {@code PAYMENT_CHARGED} 리플라이가 알려 준 값. */
        UUID paymentId,
        /** 감사 로그용 사유(예: "Saga 종료 후 도착한 결제"). */
        String reason,
        Instant occurredAt
) {
}
