package com.shopsaga.inventory.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Phase 14: 장애 주입 제어 API(학습 전용). {@link ChaosSwitch} 가 있을 때만 노출된다.
 *
 * <pre>
 * POST /inventory/chaos?failRate=50        → 조회 요청의 50%가 500 (Retry 시연)
 * POST /inventory/chaos?delayMs=3000       → 3초 지연     (TimeLimiter 시연)
 * DELETE /inventory/chaos                  → 정상 복귀
 * </pre>
 *
 * <p>경로를 {@code /inventory} 아래에 둔 이유: 게이트웨이 라우트가 {@code /inventory/**} 라
 * 새 라우트를 추가하지 않아도 그대로 도달한다.
 */
@RestController
@RequestMapping("/inventory/chaos")
@ConditionalOnBean(ChaosSwitch.class)
@RequiredArgsConstructor
@Tag(name = "Chaos", description = "Phase 14 장애 주입(학습 전용)")
class ChaosController {

    private final ChaosSwitch chaosSwitch;

    @PostMapping
    @Operation(summary = "장애 주입 설정", description = "failRate(0~100%)와 delayMs 를 런타임에 바꾼다.")
    Map<String, Object> configure(@RequestParam(defaultValue = "0") int failRate,
                                  @RequestParam(defaultValue = "0") long delayMs) {
        chaosSwitch.configure(failRate, delayMs);
        return current();
    }

    @GetMapping
    @Operation(summary = "현재 장애 주입 상태")
    Map<String, Object> current() {
        return Map.of("failRate", chaosSwitch.failRate(), "delayMs", chaosSwitch.delayMs());
    }

    @DeleteMapping
    @Operation(summary = "장애 주입 해제(정상 복귀)")
    Map<String, Object> reset() {
        chaosSwitch.configure(0, 0);
        return current();
    }
}
