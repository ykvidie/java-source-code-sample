package com.example.paul.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TransactionAmountValidator {

    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");
    private static final int SCALE = 2;

    private TransactionAmountValidator() {
    }

    public static ValidationResult validate(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return ValidationResult.invalid(Reason.INVALID_NUMBER, false);
        }

        BigDecimal normalized = BigDecimal.valueOf(amount).setScale(SCALE, RoundingMode.HALF_UP);
        boolean sanitized = normalized.doubleValue() != amount;

        if (normalized.compareTo(MIN_AMOUNT) < 0) {
            return ValidationResult.invalid(Reason.BELOW_MINIMUM, sanitized);
        }

        if (normalized.compareTo(MAX_AMOUNT) > 0) {
            return ValidationResult.invalid(Reason.ABOVE_MAXIMUM, sanitized);
        }

        return ValidationResult.valid(normalized, sanitized);
    }

    public enum Reason {
        INVALID_NUMBER,
        BELOW_MINIMUM,
        ABOVE_MAXIMUM
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final BigDecimal normalizedAmount;
        private final Reason reason;
        private final boolean sanitized;

        private ValidationResult(boolean valid, BigDecimal normalizedAmount, Reason reason, boolean sanitized) {
            this.valid = valid;
            this.normalizedAmount = normalizedAmount;
            this.reason = reason;
            this.sanitized = sanitized;
        }

        private static ValidationResult valid(BigDecimal normalized, boolean sanitized) {
            return new ValidationResult(true, normalized, null, sanitized);
        }

        private static ValidationResult invalid(Reason reason, boolean sanitized) {
            return new ValidationResult(false, null, reason, sanitized);
        }

        public boolean isValid() {
            return valid;
        }

        public BigDecimal getNormalizedAmount() {
            return normalizedAmount;
        }

        public Reason getReason() {
            return reason;
        }

        public boolean isSanitized() {
            return sanitized;
        }
    }
}

