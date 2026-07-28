package com.shopsaga.order.adapter.in.web;

import com.shopsaga.order.application.port.in.GetOrderQuery;
import com.shopsaga.order.application.port.in.OrderView;
import com.shopsaga.order.application.port.in.PlaceOrderResult;
import com.shopsaga.order.application.port.in.PlaceOrderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 인바운드 웹 어댑터. 인바운드 포트(인터페이스)에만 의존하고, 출력은 불변 뷰(OrderView)를 그대로 응답한다.
 * 도메인 타입은 이 어댑터에 들어오지 않는다.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "주문 생성·조회 (모놀리스: 재고 차감 + 결제 캡처 포함)")
class OrderController {

    /** Phase 14: 재고 사전 확인 결과를 응답에 실어 보내는 헤더(참고값). */
    static final String HEADER_STOCK_PRECHECK = "X-Stock-Precheck";

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderQuery getOrderQuery;

    @PostMapping
    @Operation(summary = "주문 생성(Saga 시작)",
            description = "주문을 PENDING 으로 접수하고 즉시 201을 반환한다. 재고·결제는 Saga 가 비동기로 진행하므로 "
                    + "최종 상태는 조회로 확인한다. 응답 헤더 X-Stock-Precheck 은 Phase 14의 재고 사전 확인 결과"
                    + "(AVAILABLE/INSUFFICIENT/UNKNOWN)이며 <b>참고값</b>이다 — UNKNOWN 이어도 주문은 정상 접수된다.")
    ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResult result = placeOrderUseCase.placeOrder(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HEADER_STOCK_PRECHECK, result.precheck().asHeader())
                .body(result.order());
    }

    @GetMapping("/{id}")
    @Operation(summary = "주문 단건 조회")
    OrderView get(@PathVariable UUID id) {
        return getOrderQuery.getOrder(id);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")   // ROLE_ADMIN 필요 — USER 토큰이면 403
    @Operation(summary = "주문 목록 조회 (ADMIN 전용)")
    List<OrderView> all() {
        return getOrderQuery.listOrders();
    }
}
