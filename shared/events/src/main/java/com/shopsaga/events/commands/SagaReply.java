package com.shopsaga.events.commands;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 13: 참여 서비스가 커맨드 처리 결과를 오케스트레이터에게 돌려주는 <b>리플라이</b>.
 *
 * <p>모든 리플라이가 <b>하나의 타입·하나의 토픽</b>({@code saga-replies})으로 모인다 —
 * 오케스트레이터가 한 곳만 들으면 되므로 상태 기계가 단순해진다.
 * {@link Kind} 로 어떤 결과인지 구분하고, {@code sagaId} 로 어느 Saga의 것인지 식별한다.
 *
 * <p>참여 서비스는 성공/실패를 <b>모두</b> 리플라이로 돌려줘야 한다. 응답이 없으면 오케스트레이터는
 * 그 단계에서 영원히 기다리게 되고, 그때 구해 주는 것이 <b>타임아웃 sweep</b>이다.
 */
public record SagaReply(
        UUID sagaId,
        UUID orderId,
        Kind kind,
        /** 결제 성공 시 발급된 결제 id(그 외에는 null). */
        UUID paymentId,
        /** 결제 금액 등 참고 값(없으면 null). */
        BigDecimal amount,
        /** 실패 사유(성공 시 null). */
        String reason,
        Instant occurredAt
) {
    public enum Kind {
        STOCK_RESERVED,
        STOCK_RESERVATION_FAILED,
        STOCK_RELEASED,
        PAYMENT_CHARGED,
        PAYMENT_DECLINED,
        /**
         * Phase 14: 결제 보상 완료. Saga 가 이미 끝난 뒤 도착한 결제를 되돌렸다는 뜻이며,
         * 조정자에게는 "고아 결제가 정리됐다"는 감사 기록으로만 쓰인다(상태 전이는 없음).
         */
        PAYMENT_REFUNDED
    }

    public static SagaReply ok(UUID sagaId, UUID orderId, Kind kind, Instant at) {
        return new SagaReply(sagaId, orderId, kind, null, null, null, at);
    }

    public static SagaReply failed(UUID sagaId, UUID orderId, Kind kind, String reason, Instant at) {
        return new SagaReply(sagaId, orderId, kind, null, null, reason, at);
    }
}
