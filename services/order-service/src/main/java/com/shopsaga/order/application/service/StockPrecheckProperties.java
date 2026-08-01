package com.shopsaga.order.application.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 15: 재고 사전 확인 설정을 <b>{@code @Value} 에서 {@code @ConfigurationProperties} 로 옮긴 것</b>.
 *
 * <h2>왜 옮겼나 — 이게 Spring Cloud Bus 의 전제다</h2>
 * {@code @Value} 로 주입한 값은 <b>빈이 만들어질 때 한 번</b> 박히고 끝이다.
 * 설정 서버의 값을 바꾸고 {@code /actuator/refresh} 를 호출해도 그 필드는 <b>그대로다</b>
 * (그 빈이 {@code @RefreshScope} 로 다시 만들어지지 않는 한).
 *
 * <p>반면 {@code @ConfigurationProperties} 빈은 Boot 의 {@code ConfigurationPropertiesRebinder} 가
 * 리프레시 때 <b>자동으로 다시 바인딩</b>한다 — 빈을 새로 만들지 않고 필드만 갈아 끼운다.
 * 그래서 "설정을 런타임에 바꾸고 싶다"면 값을 이 형태로 들고 있어야 한다.
 *
 * <p>⚠️ 단, 빈의 <b>존재 여부</b>를 결정하는 값({@code enabled} 로 거는 {@code @ConditionalOnProperty})은
 * 리프레시로 바뀌지 않는다 — 조건 평가는 컨텍스트 기동 시 한 번뿐이다. 그건 재시작이 필요하다.
 */
@ConfigurationProperties(prefix = "order.stock-precheck")
@Getter
@Setter
public class StockPrecheckProperties {

    /** 사전 확인 어댑터 자체를 켤지. ⚠️ 이 값은 리프레시로 바뀌지 않는다(빈 존재 여부라서). */
    private boolean enabled = true;

    /**
     * 사전 확인이 "부족"이라고 할 때 409로 빠르게 거절할지.
     * <b>이 값은 리프레시로 즉시 바뀐다</b> — Phase 15의 bus 시연 대상.
     */
    private boolean rejectOnInsufficient = false;
}
