package com.shopsaga.order.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * ★ 감사(2026-08-02) 수정: JWT subject(사용자명) → <b>결정적 고객 id</b> 유도.
 *
 * <p>왜 필요한가 — 토큰에는 사용자명(alice)만 있고 고객 UUID 가 없다. 클라이언트가 보내는
 * customerId 를 믿으면 IDOR(남의 주문 생성·조회)이 열리므로, 신뢰할 수 있는 유일한 출처인
 * <b>토큰 subject 에서 이름 기반 UUID(v3)를 유도</b>해 주문에 묶는다. 같은 사용자는 언제나
 * 같은 id가 나오므로 "내 주문" 판정이 성립한다.
 *
 * <p>실무라면 auth 서버가 사용자 UUID 클레임(uid)을 직접 실어 주는 것이 정석이다 — 여기서는
 * 토큰 스키마를 바꾸지 않는 최소 수정을 택했다(향후 하드닝 백로그).
 */
final class CustomerIds {

    private CustomerIds() {
    }

    static UUID fromSubject(String subject) {
        return UUID.nameUUIDFromBytes(("shopsaga-customer:" + subject).getBytes(StandardCharsets.UTF_8));
    }
}
