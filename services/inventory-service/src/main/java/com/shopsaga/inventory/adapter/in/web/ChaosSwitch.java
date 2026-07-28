package com.shopsaga.inventory.adapter.in.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 14: <b>장애 주입 스위치</b>(chaos). 복원력 패턴은 장애가 있어야 관찰할 수 있으므로,
 * 재시작 없이 런타임에 켜고 끌 수 있는 손잡이를 둔다.
 *
 * <ul>
 *   <li>{@code failRate} — 이 확률로 500 을 던진다. <b>Retry</b> 시연용(일시적 결함).</li>
 *   <li>{@code delayMs} — 이 시간만큼 늦게 응답한다. <b>TimeLimiter</b> 시연용(느린 의존성).</li>
 * </ul>
 *
 * <p>⚠️ 학습 전용이다. 운영에 이런 엔드포인트가 있으면 그 자체가 장애 원인이 된다 —
 * 그래서 {@code chaos.enabled} 로 게이팅하고 기본값은 꺼짐이다(이 프로젝트는 config 에서 켠다).
 */
@Component
@ConditionalOnProperty(name = "chaos.enabled", havingValue = "true")
@Slf4j
public class ChaosSwitch {

    /** 0~100 (%). 0 = 정상. */
    private final AtomicInteger failRate = new AtomicInteger(0);
    private final AtomicLong delayMs = new AtomicLong(0);

    public void configure(int failRatePercent, long delayMillis) {
        this.failRate.set(Math.clamp(failRatePercent, 0, 100));
        this.delayMs.set(Math.max(0, delayMillis));
        log.warn("★ chaos 설정 변경 failRate={}% delay={}ms", this.failRate.get(), this.delayMs.get());
    }

    public int failRate() {
        return failRate.get();
    }

    public long delayMs() {
        return delayMs.get();
    }

    /** 설정된 대로 지연시키고, 확률에 걸리면 예외를 던진다. */
    public void maybeDisrupt() {
        long delay = delayMs.get();
        if (delay > 0) {
            sleep(delay);
        }
        int rate = failRate.get();
        if (rate > 0 && ThreadLocalRandom.current().nextInt(100) < rate) {
            throw new ChaosInjectedException("주입된 장애(failRate=" + rate + "%)");
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 호출자(TimeLimiter)가 취소했을 수 있다.
            throw new ChaosInjectedException("주입된 지연 중 인터럽트");
        }
    }

    /** 주입된 장애임을 명확히 하는 예외 — 진짜 버그와 구분되어야 한다. */
    public static class ChaosInjectedException extends RuntimeException {
        public ChaosInjectedException(String message) {
            super(message);
        }
    }
}
