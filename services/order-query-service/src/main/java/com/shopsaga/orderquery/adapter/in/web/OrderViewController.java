package com.shopsaga.orderquery.adapter.in.web;

import com.shopsaga.orderquery.application.port.in.GetOrderViewQuery;
import com.shopsaga.orderquery.application.port.in.OrderSummary;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11: 조회 전용 인바운드 웹 어댑터.
 *
 * <p>이 서비스에는 <b>쓰기 엔드포인트가 없다</b>(POST/PUT/DELETE 없음) — 그게 CQRS의 Q 쪽 경계다.
 * 주문 생성은 order-service(`POST /orders`)가 하고, 그 사실이 이벤트로 흘러와 여기에 투영된다.
 */
@RestController
@RequestMapping("/order-views")
@RequiredArgsConstructor
class OrderViewController {

    private final GetOrderViewQuery getOrderViewQuery;

    @GetMapping
    @Operation(summary = "고객의 주문 목록(읽기 모델) — 최근 주문 먼저")
    ResponseEntity<List<OrderSummary>> byCustomer(@RequestParam UUID customerId) {
        return ResponseEntity.ok(getOrderViewQuery.findByCustomer(customerId));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "주문 하나(읽기 모델). 방금 만든 주문은 아직 투영 전일 수 있다(결과적 일관성)")
    ResponseEntity<OrderSummary> byOrderId(@PathVariable UUID orderId) {
        return ResponseEntity.ok(getOrderViewQuery.getByOrderId(orderId));
    }
}
