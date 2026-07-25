package com.shopsaga.orderquery.application.port.out;

import com.shopsaga.orderquery.domain.OrderView;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 아웃바운드 포트: 읽기 모델 저장소. MongoDB라는 사실은 어댑터의 세부사항이다.
 */
public interface OrderViewRepositoryPort {

    /**
     * 주문 본문(고객·품목·금액·시각)을 orderId 기준으로 <b>덮어쓰되 status 는 건드리지 않는다</b>.
     * 문서가 없으면 새로 만들며 그때만 초기 상태를 넣는다.
     *
     * <p>왜 status 를 제외하나: {@code OrderPlaced} 가 <b>나중에</b> 재배달·리플레이될 수 있는데
     * 문서 전체를 덮어쓰면 이미 CONFIRMED 인 주문이 PENDING 으로 <b>되돌아가</b> 버린다.
     * 상태는 아래 {@link #applyStatusIfCurrentIn} 으로만 <b>전진</b>시킨다.
     */
    void upsertBase(OrderView view, String initialStatus);

    /**
     * 현재 상태가 {@code overwritable} 중 하나일 때만 {@code newStatus} 로 바꾼다(<b>원자적 조건부 갱신</b>).
     *
     * <p>읽기 모델은 세 토픽(order/inventory/payment)을 <b>각각 다른 스레드</b>로 소비하므로
     * 이벤트가 뒤바뀐 순서로 도착할 수 있다. "읽고 판단해서 쓰기"는 경합에 지므로,
     * 조건과 갱신을 DB 한 연산으로 묶어 <b>상태가 뒤로 가지 않게</b> 보장한다(단조 전이).
     *
     * @return 실제로 갱신됐으면 true, 조건 불충족(이미 더 진행된 상태)이면 false
     */
    boolean applyStatusIfCurrentIn(UUID orderId, String newStatus, Set<String> overwritable);

    Optional<OrderView> findByOrderId(UUID orderId);

    /** 최근 주문 먼저(placedAt 내림차순). */
    List<OrderView> findByCustomerId(UUID customerId);
}
