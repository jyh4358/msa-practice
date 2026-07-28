package com.shopsaga.payment.adapter.out.persistence;

import com.shopsaga.payment.application.port.out.LoadPaymentPort;
import com.shopsaga.payment.application.port.out.SavePaymentPort;
import com.shopsaga.payment.application.port.out.UpdatePaymentPort;
import com.shopsaga.payment.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 아웃바운드 영속 어댑터. 신규 결제 INSERT(도메인 id=null → @GeneratedValue)와
 * Phase 14 환불 보상을 위한 조회·상태 변경을 담당한다.
 */
@Component
@RequiredArgsConstructor
class PaymentPersistenceAdapter implements SavePaymentPort, LoadPaymentPort, UpdatePaymentPort {

    private final PaymentJpaRepository repository;

    @Override
    public Payment save(Payment payment) {
        return PaymentMapper.toDomain(repository.save(PaymentMapper.toJpaEntity(payment)));
    }

    @Override
    public Optional<Payment> loadById(UUID paymentId) {
        return repository.findById(paymentId).map(PaymentMapper::toDomain);
    }

    /**
     * 도메인이 전이시킨 상태를 영속 엔티티에 반영한다(호출자 트랜잭션의 더티 체킹으로 UPDATE).
     * 도메인 객체를 그대로 {@code save} 하지 않는 이유: 그러면 id 가 있어도 매퍼가 새 엔티티를 만들어
     * 별개 row 가 생긴다(= 결제가 하나 더 늘어난다).
     */
    @Override
    public void update(Payment payment) {
        repository.findById(payment.getId())
                .ifPresent(entity -> entity.markRefunded(payment.getRefundedAt()));
    }
}
