package com.example.paul.controllers;

import com.example.paul.constants.ACTION;
import com.example.paul.controllers.workflow.OperationOutcome;
import com.example.paul.controllers.workflow.OutcomeBuilder;
import com.example.paul.models.Account;
import com.example.paul.services.AccountService;
import com.example.paul.services.TransactionService;
import com.example.paul.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static com.example.paul.constants.constants.*;

@RestController
@RequestMapping("api/v1")
public class TransactionRestController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionRestController.class);

    private final AccountService accountService;
    private final TransactionService transactionService;

    @Autowired
    public TransactionRestController(AccountService accountService, TransactionService transactionService) {
        this.accountService = accountService;
        this.transactionService = transactionService;
    }

    @PostMapping(value = "/transactions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> makeTransfer(
            @Valid @RequestBody TransactionInput transactionInput) {
        LOGGER.debug("Triggered TransactionRestController.makeTransfer");

        OperationOutcome<Boolean, DecisionPath> outcome = evaluateTransfer(transactionInput);
        logOutcome(outcome, "transaction transfer");
        return convertOutcome(outcome,
                INVALID_TRANSACTION,
                INVALID_TRANSACTION,
                INVALID_TRANSACTION);
    }

    @PostMapping(value = "/withdraw",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> withdraw(
            @Valid @RequestBody WithdrawInput withdrawInput) {
        LOGGER.debug("Triggered TransactionRestController.withdraw");

        OperationOutcome<String, DecisionPath> outcome = evaluateWithdrawal(withdrawInput);
        logOutcome(outcome, "withdrawal");
        return convertOutcome(outcome,
                NO_ACCOUNT_FOUND,
                INVALID_SEARCH_CRITERIA,
                INSUFFICIENT_ACCOUNT_BALANCE);
    }


    @PostMapping(value = "/deposit",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deposit(
            @Valid @RequestBody DepositInput depositInput) {
        LOGGER.debug("Triggered TransactionRestController.deposit");

        OperationOutcome<String, DecisionPath> outcome = evaluateDeposit(depositInput);
        logOutcome(outcome, "deposit");
        return convertOutcome(outcome,
                NO_ACCOUNT_FOUND,
                INVALID_SEARCH_CRITERIA,
                INVALID_SEARCH_CRITERIA);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Map<String, String> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        return errors;
    }

    private ResponseEntity<?> convertOutcome(OperationOutcome<?, DecisionPath> outcome,
                                             String emptyResultMessage,
                                             String invalidMessage,
                                             String failureMessage) {
        switch (outcome.getType()) {
            case SUCCESS:
                return new ResponseEntity<>(outcome.getPayload(), outcome.getStatus());
            case INVALID_INPUT:
                return new ResponseEntity<>(resolveBody(outcome, invalidMessage), outcome.getStatus());
            case EMPTY_RESULT:
                return new ResponseEntity<>(resolveBody(outcome, emptyResultMessage), outcome.getStatus());
            case FAILURE:
                return new ResponseEntity<>(resolveBody(outcome, failureMessage), outcome.getStatus());
            default:
                throw new IllegalStateException("Unhandled outcome type: " + outcome.getType());
        }
    }

    private Object resolveBody(OperationOutcome<?, DecisionPath> outcome, String fallbackMessage) {
        Object payload = outcome.getPayload();
        return payload != null ? payload : resolveMessage(outcome.getMessage(), fallbackMessage);
    }

    private String resolveMessage(String preferredMessage, String fallbackMessage) {
        return preferredMessage == null ? fallbackMessage : preferredMessage;
    }

    private void logOutcome(OperationOutcome<?, DecisionPath> outcome, String context) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Outcome for {} -> type={}, status={}, trail={}",
                    context, outcome.getType(), outcome.getStatus(), outcome.getDecisionTrail());
        }
    }

    private OperationOutcome<Boolean, DecisionPath> evaluateTransfer(TransactionInput transactionInput) {
        OutcomeBuilder<Boolean, DecisionPath> builder = OutcomeBuilder.<Boolean, DecisionPath>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!InputValidator.isSearchTransactionValid(transactionInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION);
        }

        AmountValidationOutcome amountOutcome = applyAdvancedAmountValidation(transactionInput.getAmount(), builder);
        if (!amountOutcome.isValid()) {
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, amountOutcome.getMessage());
        }
        transactionInput.setAmount(amountOutcome.getNormalizedAmount());

        builder.record(DecisionPath.TRANSFER_ATTEMPT);
        boolean completed = transactionService.makeTransfer(transactionInput);

        if (!completed) {
            builder.record(DecisionPath.TRANSFER_FAILED);
            return builder.buildFailure(Boolean.FALSE, HttpStatus.OK, INVALID_TRANSACTION);
        }

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(Boolean.TRUE, HttpStatus.OK);
    }

    private OperationOutcome<String, DecisionPath> evaluateWithdrawal(WithdrawInput withdrawInput) {
        OutcomeBuilder<String, DecisionPath> builder = OutcomeBuilder.<String, DecisionPath>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!InputValidator.isSearchCriteriaValid(withdrawInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        AmountValidationOutcome amountOutcome = applyAdvancedAmountValidation(withdrawInput.getAmount(), builder);
        if (!amountOutcome.isValid()) {
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, amountOutcome.getMessage());
        }
        withdrawInput.setAmount(amountOutcome.getNormalizedAmount());

        return executeBalanceMutation(builder,
                () -> accountService.getAccount(withdrawInput.getSortCode(), withdrawInput.getAccountNumber()),
                ACTION.WITHDRAW,
                withdrawInput.getAmount(),
                true,
                NO_ACCOUNT_FOUND,
                INSUFFICIENT_ACCOUNT_BALANCE);
    }

    private OperationOutcome<String, DecisionPath> evaluateDeposit(DepositInput depositInput) {
        OutcomeBuilder<String, DecisionPath> builder = OutcomeBuilder.<String, DecisionPath>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!InputValidator.isAccountNoValid(depositInput.getTargetAccountNo())) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        AmountValidationOutcome amountOutcome = applyAdvancedAmountValidation(depositInput.getAmount(), builder);
        if (!amountOutcome.isValid()) {
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, amountOutcome.getMessage());
        }
        depositInput.setAmount(amountOutcome.getNormalizedAmount());

        return executeBalanceMutation(builder,
                () -> accountService.getAccount(depositInput.getTargetAccountNo()),
                ACTION.DEPOSIT,
                depositInput.getAmount(),
                false,
                NO_ACCOUNT_FOUND,
                INVALID_TRANSACTION_AMOUNT);
    }

    private AmountValidationOutcome applyAdvancedAmountValidation(double amount,
                                                                  OutcomeBuilder<?, DecisionPath> builder) {
        builder.record(DecisionPath.AMOUNT_VALIDATION);
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            builder.record(DecisionPath.AMOUNT_INVALID_FORMAT);
            return AmountValidationOutcome.invalid(INVALID_TRANSACTION_AMOUNT_FORMAT);
        }

        TransactionAmountValidator.ValidationResult result = TransactionAmountValidator.validate(amount);
        if (!result.isValid()) {
            DecisionPath reasonPath = mapReasonToDecisionPath(result.getReason());
            builder.record(reasonPath);
            return AmountValidationOutcome.invalid(mapReasonToMessage(result.getReason()));
        }

        double normalized = result.getNormalizedAmount().doubleValue();
        if (result.isSanitized()) {
            builder.record(DecisionPath.AMOUNT_SANITIZED);
        }

        return AmountValidationOutcome.valid(normalized);
    }

    private DecisionPath mapReasonToDecisionPath(TransactionAmountValidator.Reason reason) {
        switch (reason) {
            case INVALID_NUMBER:
                return DecisionPath.AMOUNT_INVALID_FORMAT;
            case BELOW_MINIMUM:
                return DecisionPath.AMOUNT_TOO_SMALL;
            case ABOVE_MAXIMUM:
                return DecisionPath.AMOUNT_TOO_LARGE;
            default:
                throw new IllegalStateException("Unhandled reason " + reason);
        }
    }

    private String mapReasonToMessage(TransactionAmountValidator.Reason reason) {
        switch (reason) {
            case INVALID_NUMBER:
                return INVALID_TRANSACTION_AMOUNT_FORMAT;
            case BELOW_MINIMUM:
                return TRANSACTION_AMOUNT_TOO_SMALL;
            case ABOVE_MAXIMUM:
                return TRANSACTION_AMOUNT_TOO_LARGE;
            default:
                return INVALID_TRANSACTION_AMOUNT;
        }
    }

    private OperationOutcome<String, DecisionPath> executeBalanceMutation(
            OutcomeBuilder<String, DecisionPath> builder,
            Supplier<Account> accountSupplier,
            ACTION action,
            double amount,
            boolean ensureFunds,
            String emptyMessage,
            String insufficientMessage) {

        builder.record(DecisionPath.ACCOUNT_LOOKUP);
        Account account = accountSupplier.get();

        if (account == null) {
            builder.record(DecisionPath.RESULT_EMPTY);
            return builder.buildEmpty(HttpStatus.OK, emptyMessage);
        }

        if (ensureFunds) {
            builder.record(DecisionPath.BALANCE_CHECK);
            if (!transactionService.isAmountAvailable(amount, account.getCurrentBalance())) {
                builder.record(DecisionPath.INSUFFICIENT_FUNDS);
                return builder.buildFailure(HttpStatus.OK, insufficientMessage);
            }
        }

        builder.record(DecisionPath.BALANCE_UPDATE);
        transactionService.updateAccountBalance(account, amount, action);

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(SUCCESS, HttpStatus.OK);
    }

    private enum DecisionPath {
        PRE_VALIDATION,
        VALIDATION_FAILED_GENERIC,
        AMOUNT_VALIDATION,
        AMOUNT_SANITIZED,
        AMOUNT_INVALID_FORMAT,
        AMOUNT_TOO_SMALL,
        AMOUNT_TOO_LARGE,
        TRANSFER_ATTEMPT,
        TRANSFER_FAILED,
        ACCOUNT_LOOKUP,
        RESULT_EMPTY,
        BALANCE_CHECK,
        INSUFFICIENT_FUNDS,
        BALANCE_UPDATE,
        RESULT_SUCCESS
    }

    private static final class AmountValidationOutcome {
        private final boolean valid;
        private final double normalizedAmount;
        private final String message;

        private AmountValidationOutcome(boolean valid, double normalizedAmount, String message) {
            this.valid = valid;
            this.normalizedAmount = normalizedAmount;
            this.message = message;
        }

        static AmountValidationOutcome valid(double normalizedAmount) {
            return new AmountValidationOutcome(true, normalizedAmount, null);
        }

        static AmountValidationOutcome invalid(String message) {
            return new AmountValidationOutcome(false, 0d, message);
        }

        boolean isValid() {
            return valid;
        }

        double getNormalizedAmount() {
            return normalizedAmount;
        }

        String getMessage() {
            return message;
        }
    }
}
