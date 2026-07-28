package com.shopsaga.events;

/**
 * Phase 12: Kafka 토픽 이름 <b>계약</b>. 발행자와 소비자가 같은 상수를 봐야 오타로 어긋나지 않는다.
 *
 * <p>토픽은 <b>발행 서비스(애그리거트) 단위</b>로 묶는다 — 한 토픽에 그 서비스의 여러 이벤트 타입이 흐른다
 * (예: inventory-events 에 예약 성공/실패/해제). 소비자는 {@code @KafkaHandler} 로 타입별로 분기한다.
 * 이렇게 하면 서비스가 새 이벤트를 추가할 때 토픽을 늘리지 않아도 된다.
 */
public final class Topics {

    /** order-service 발행: OrderPlaced / OrderConfirmed / OrderCancelled */
    public static final String ORDER_EVENTS = "order-events";

    /** inventory-service 발행: InventoryReserved / InventoryFailed / InventoryReleased(보상) */
    public static final String INVENTORY_EVENTS = "inventory-events";

    /** payment-service 발행: PaymentCharged / PaymentDeclined */
    public static final String PAYMENT_EVENTS = "payment-events";

    /**
     * Phase 13(오케스트레이션): 조정자 → 참여 서비스 <b>커맨드</b>(ReserveStock / ReleaseStock / ChargePayment).
     *
     * <p>이벤트 토픽과 분리하는 이유: 이벤트는 "누구든 들으라"는 방송이지만, 커맨드는 <b>특정 수신자에게 시키는 일</b>이다.
     * 섞으면 "사실"과 "지시"의 구분이 흐려지고, 소비자가 남의 지시까지 보게 된다.
     */
    public static final String SAGA_COMMANDS = "saga-commands";

    /** Phase 13: 참여 서비스 → 조정자 <b>리플라이</b>(모든 결과가 한 토픽·한 타입으로 모인다). */
    public static final String SAGA_REPLIES = "saga-replies";

    private Topics() {
    }
}
