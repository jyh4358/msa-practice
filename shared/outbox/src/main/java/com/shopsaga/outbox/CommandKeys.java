package com.shopsaga.outbox;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Phase 13: 커맨드의 <b>결정적 dedup 키</b>를 만든다.
 *
 * <p>같은 (sagaId, 커맨드 타입)이면 <b>몇 번을 재전송해도 같은 키</b>가 나온다.
 * 덕분에 타임아웃 sweep이 커맨드를 다시 보내도 참여 서비스가 "이미 한 일"임을 알아본다
 * (메시지 id로는 불가능하다 — 재전송하면 새 id가 생기므로).
 *
 * <p>같은 Saga가 같은 단계를 두 번 <b>정당하게</b> 수행할 일은 없다는 것이 이 키의 전제다.
 */
public final class CommandKeys {

    public static UUID of(UUID sagaId, String commandType) {
        return UUID.nameUUIDFromBytes((sagaId + ":" + commandType).getBytes(StandardCharsets.UTF_8));
    }

    private CommandKeys() {
    }
}
