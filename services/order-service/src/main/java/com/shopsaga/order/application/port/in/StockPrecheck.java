package com.shopsaga.order.application.port.in;

/**
 * Phase 14: 주문 접수 직전에 해 보는 <b>재고 사전 확인</b> 결과.
 *
 * <h2>이 값이 "참고용"인 것이 핵심이다</h2>
 * 진짜 재고 판정은 여전히 Saga 안에서 inventory-service 가 <b>락을 걸고</b> 한다.
 * 사전 확인은 그저 "지금 화면에 보여 줄 힌트"일 뿐이며, 확인과 실제 예약 사이에 다른 주문이
 * 재고를 가져갈 수 있으므로 <b>맞다고 보장되지 않는다</b>(TOCTOU).
 *
 * <p>그래서 이 호출은 실패해도 된다 — 그것이 Phase 12에서 없앴던 동기 호출을
 * 이 한 군데에 <b>다시 허용한 이유</b>다. 동기 호출은 그 자체가 나쁜 게 아니라
 * <b>필수 경로에 있을 때</b> 나쁘다. 필수가 아니면 회로가 열려도 주문은 그대로 접수된다.
 */
public record StockPrecheck(Status status, String detail) {

    public enum Status {
        /** 확인했고 충분하다. */
        AVAILABLE,
        /** 확인했고 부족하다 — 다만 Saga 가 최종 판정한다. */
        INSUFFICIENT,
        /** 확인하지 못했다(회로 열림·타임아웃·과부하 차단 등). <b>주문은 그대로 진행</b>한다. */
        UNKNOWN
    }

    public static StockPrecheck available() {
        return new StockPrecheck(Status.AVAILABLE, null);
    }

    public static StockPrecheck insufficient(String detail) {
        return new StockPrecheck(Status.INSUFFICIENT, detail);
    }

    public static StockPrecheck unknown(String detail) {
        return new StockPrecheck(Status.UNKNOWN, detail);
    }

    /** 응답 헤더 {@code X-Stock-Precheck} 에 실을 한 줄 표현. */
    public String asHeader() {
        return detail == null ? status.name() : status.name() + " (" + detail + ")";
    }
}
