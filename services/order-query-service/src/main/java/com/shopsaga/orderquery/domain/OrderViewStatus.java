package com.shopsaga.orderquery.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 12: 읽기 모델의 주문 상태와 그 <b>진행 순위(rank)</b>.
 *
 * <p>읽기 모델은 여러 토픽을 각각 다른 스레드로 소비하므로 이벤트가 <b>뒤바뀐 순서</b>로 올 수 있다.
 * 예를 들어 {@code OrderConfirmed}(order-events)가 {@code InventoryReserved}(inventory-events)보다
 * 먼저 처리되면, 순진하게 덮어쓰면 CONFIRMED → INVENTORY_RESERVED 로 <b>거꾸로</b> 간다.
 *
 * <p>그래서 상태를 순위가 있는 값으로 보고 <b>더 진행된 상태로만</b> 전이시킨다(단조 전이).
 * 이러면 도착 순서와 무관하게 최종 상태가 같아지고, 리플레이해도 같은 결과가 나온다(결정성 유지).
 */
public enum OrderViewStatus {

    PENDING(0),
    INVENTORY_RESERVED(1),
    /** 종료 상태 — Saga는 확정/취소 중 하나만 만들어내므로 둘은 서로를 덮어쓰지 않는다. */
    CONFIRMED(2),
    CANCELLED(2);

    private final int rank;

    OrderViewStatus(int rank) {
        this.rank = rank;
    }

    /** 이 상태로 전이할 때 <b>덮어써도 되는</b> 상태들(자기보다 순위가 낮은 것). */
    public Set<String> overwritableStatuses() {
        return Arrays.stream(values())
                .filter(s -> s.rank < this.rank)
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
