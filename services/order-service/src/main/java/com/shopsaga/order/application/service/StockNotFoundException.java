package com.shopsaga.order.application.service;

import java.util.UUID;

public class StockNotFoundException extends RuntimeException {

    public StockNotFoundException(UUID productId) {
        super("Stock not found for product: " + productId);
    }
}
