package com.shopsaga.order.application.service;

import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.application.port.out.SagaStarterPort;
import com.shopsaga.order.domain.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 12: <b>코레오그래피 모드</b>의 Saga 시작 — <b>할 일이 없다</b>.
 *
 * <p>이 "빈 구현"이 곧 코레오그래피의 정의다: 시작을 알리는 조정자가 없고,
 * {@code OrderPlaced} 라는 <b>사실</b>이 발행되면 관심 있는 서비스들이 알아서 반응한다.
 * (그 발행은 {@link OrderService} 가 이미 하고 있다.)
 *
 * <p>{@code saga.mode=choreography} 로 켠다 — 두 방식을 갈아 끼우며 비교하기 위한 장치다.
 */
@Component
@ConditionalOnProperty(name = "saga.mode", havingValue = "choreography")
@Slf4j
class ChoreographySagaStarter implements SagaStarterPort {

    @Override
    public void start(Order order, PlaceOrderCommand command) {
        // 의도적으로 아무것도 하지 않는다 — 조정자가 없다는 것이 이 모드의 핵심.
        log.debug("코레오그래피 모드 — OrderPlaced 이벤트가 Saga를 이끈다 orderId={}", order.getId());
    }
}
