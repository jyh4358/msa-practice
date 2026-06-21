package com.shopsaga.payment.adapter.in.web;

import com.shopsaga.payment.application.port.in.CapturePaymentUseCase;
import com.shopsaga.payment.application.port.in.PaymentView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인바운드 웹 어댑터: 결제 캡처 API. 거절 시 402. */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "결제 캡처 (가짜 게이트웨이: 합계가 .99로 끝나면 거절)")
class PaymentController {

    private final CapturePaymentUseCase capturePaymentUseCase;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "결제 캡처", description = "주문 금액을 결제한다. 거절 → 402 Payment Required.")
    PaymentView capture(@Valid @RequestBody CapturePaymentRequest request) {
        return capturePaymentUseCase.capture(request.toCommand());
    }
}
