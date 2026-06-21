package com.shopsaga.order.adapter.in.web;

import com.shopsaga.order.application.port.in.GetOrderQuery;
import com.shopsaga.order.application.port.in.OrderView;
import com.shopsaga.order.application.port.in.PlaceOrderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderQuery getOrderQuery;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "주문 생성", description = "재고를 차감하고 결제를 캡처한 뒤 주문을 확정한다(단일 트랜잭션). "
            + "재고 부족 → 409, 결제 거절(합계가 .99로 끝남) → 402.")
    OrderView place(@Valid @RequestBody PlaceOrderRequest request) {
        return placeOrderUseCase.placeOrder(request.toCommand());
    }

    @GetMapping("/{id}")
    @Operation(summary = "주문 단건 조회")
    OrderView get(@PathVariable UUID id) {
        return getOrderQuery.getOrder(id);
    }

    @GetMapping
    @Operation(summary = "주문 목록 조회")
    List<OrderView> all() {
        return getOrderQuery.listOrders();
    }
}
