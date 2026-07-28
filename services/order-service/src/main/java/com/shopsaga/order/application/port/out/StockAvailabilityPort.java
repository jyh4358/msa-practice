package com.shopsaga.order.application.port.out;

import com.shopsaga.order.application.port.in.StockPrecheck;

import java.util.List;
import java.util.UUID;

/**
 * Phase 14: 아웃바운드 포트 — inventory-service 에 <b>동기</b>로 재고를 물어본다(사전 확인).
 *
 * <p>이 포트의 구현은 <b>절대 예외를 던지지 않는다</b>. 원격 호출이 어떤 이유로 실패하든
 * {@link StockPrecheck.Status#UNKNOWN} 을 돌려주고 주문은 계속 진행되어야 한다 —
 * 부가 기능이 본 기능을 끌어내리면 안 되기 때문이다(이것이 <b>graceful degradation</b>).
 */
public interface StockAvailabilityPort {

    StockPrecheck precheck(List<Line> lines);

    /** 확인에 필요한 최소 정보(가격 등은 재고와 무관하므로 넘기지 않는다). */
    record Line(UUID productId, int quantity) {}
}
