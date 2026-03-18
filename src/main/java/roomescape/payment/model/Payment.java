package roomescape.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import roomescape.common.model.BaseEntity;
import roomescape.reservation.model.Reservation;

@Entity
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String paymentKey;

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false)
    private Long amount;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    public static Payment inProgress(final String paymentKey, final String orderId, final Long amount,
                                     final Reservation reservation) {
        return new Payment(paymentKey, orderId, amount, reservation, PaymentStatus.IN_PROGRESS);
    }

    public Payment(final String paymentKey, final String orderId, final Long amount, final Reservation reservation,
                   final PaymentStatus status) {
        this.paymentKey = paymentKey;
        this.orderId = orderId;
        this.amount = amount;
        this.reservation = reservation;
        this.status = status;
    }

    protected Payment() {
    }

    public void complete() {
        this.status = PaymentStatus.COMPLETED;
    }

    public String getPaymentKey() {
        return paymentKey;
    }

    public String getOrderId() {
        return orderId;
    }

    public Long getAmount() {
        return amount;
    }
}
