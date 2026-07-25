package com.shopsaga.orderquery.application.port.in;

import com.shopsaga.events.OrderPlacedEvent;

/**
 * 인바운드 포트(커맨드): {@code OrderPlaced} 이벤트를 읽기 모델에 <b>투영(project)</b>한다.
 *
 * <p>투영은 <b>멱등</b>해야 한다 — Kafka는 at-least-once 배달이고, 읽기 모델을 지우고 offset 0부터
 * 다시 재생(replay)하는 것이 정상 운영 절차이기 때문이다. 그래서 "orderId 기준 덮어쓰기(upsert)"로
 * 구현한다(중복 소비 = 같은 값으로 다시 쓰기 = 무해).
 */
public interface ProjectOrderPlacedUseCase {

    void project(OrderPlacedEvent event);
}
