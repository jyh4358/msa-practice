package com.shopsaga.payment.adapter.in.web;

import com.shopsaga.payment.application.port.in.CapturePaymentCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record CapturePaymentRequest(
        @NotNull UUID orderId,
        @NotNull @Positive BigDecimal amount
) {
    public CapturePaymentCommand toCommand() {
        return new CapturePaymentCommand(orderId, amount);
    }
}
