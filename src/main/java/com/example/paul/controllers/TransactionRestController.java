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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

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
        LOGGER.debug("TransactionRestController.makeTransfer called");

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
        LOGGER.debug("TransactionRestController.withdraw called");

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
        LOGGER.debug("TransactionRestController.deposit called");

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

        if (!validateAndNormalizeAmount(transactionInput::setAmount, transactionInput.getAmount(), builder)) {
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION_AMOUNT);
        }

        if (!InputValidator.isSearchTransactionValid(transactionInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION);
        }

        builder.record(DecisionPath.TRANSFER_ATTEMPT);
        boolean transferResult = transactionService.makeTransfer(transactionInput);

        if (!transferResult) {
            builder.record(DecisionPath.TRANSFER_FAILED);
            return builder.buildFailure(Boolean.FALSE, HttpStatus.OK, INVALID_TRANSACTION);
        }

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(Boolean.TRUE, HttpStatus.OK);
    }

    private OperationOutcome<String, DecisionPath> evaluateWithdrawal(WithdrawInput withdrawInput) {
        OutcomeBuilder<String, DecisionPath> builder = OutcomeBuilder.<String, DecisionPath>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!validateAndNormalizeAmount(withdrawInput::setAmount, withdrawInput.getAmount(), builder)) {
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION_AMOUNT);
        }

        if (!InputValidator.isSearchCriteriaValid(withdrawInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        builder.record(DecisionPath.ACCOUNT_LOOKUP);
        Account withdrawAccount = accountService.getAccount(withdrawInput.getSortCode(), withdrawInput.getAccountNumber());

        if (withdrawAccount == null) {
            builder.record(DecisionPath.RESULT_EMPTY);
            return builder.buildEmpty(HttpStatus.OK, NO_ACCOUNT_FOUND);
        }

        builder.record(DecisionPath.BALANCE_CHECK);
        double withdrawalAmount = withdrawInput.getAmount();
        if (!transactionService.isAmountAvailable(withdrawalAmount, withdrawAccount.getCurrentBalance())) {
            builder.record(DecisionPath.INSUFFICIENT_FUNDS);
            return builder.buildFailure(HttpStatus.OK, INSUFFICIENT_ACCOUNT_BALANCE);
        }

        builder.record(DecisionPath.BALANCE_UPDATE);
        transactionService.updateAccountBalance(withdrawAccount, withdrawalAmount, ACTION.WITHDRAW);

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(SUCCESS, HttpStatus.OK);
    }

    private OperationOutcome<String, DecisionPath> evaluateDeposit(DepositInput depositInput) {
        OutcomeBuilder<String, DecisionPath> builder = OutcomeBuilder.<String, DecisionPath>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!validateAndNormalizeAmount(depositInput::setAmount, depositInput.getAmount(), builder)) {
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION_AMOUNT);
        }

        if (!InputValidator.isAccountNoValid(depositInput.getTargetAccountNo())) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        builder.record(DecisionPath.ACCOUNT_LOOKUP);
        Account targetAccount = accountService.getAccount(depositInput.getTargetAccountNo());

        if (targetAccount == null) {
            builder.record(DecisionPath.RESULT_EMPTY);
            return builder.buildEmpty(HttpStatus.OK, NO_ACCOUNT_FOUND);
        }

        builder.record(DecisionPath.BALANCE_UPDATE);
        double depositAmount = depositInput.getAmount();
        transactionService.updateAccountBalance(targetAccount, depositAmount, ACTION.DEPOSIT);

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(SUCCESS, HttpStatus.OK);
    }

    private boolean validateAndNormalizeAmount(java.util.function.DoubleConsumer sanitizer,
                                               double amount,
                                               OutcomeBuilder<?, DecisionPath> builder) {
        builder.record(DecisionPath.AMOUNT_VALIDATION);
        
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            builder.record(DecisionPath.AMOUNT_INVALID);
            return false;
        }

        double roundedAmount = BigDecimal.valueOf(amount)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();

        if (roundedAmount <= 0) {
            builder.record(DecisionPath.AMOUNT_INVALID);
            return false;
        }

        boolean wasSanitized = roundedAmount != amount;
        sanitizer.accept(roundedAmount);
        if (wasSanitized) {
            builder.record(DecisionPath.AMOUNT_SANITIZED);
        }

        return true;
    }

    private enum DecisionPath {
        PRE_VALIDATION,
        VALIDATION_FAILED_GENERIC,
        AMOUNT_VALIDATION,
        AMOUNT_SANITIZED,
        AMOUNT_INVALID,
        TRANSFER_ATTEMPT,
        TRANSFER_FAILED,
        ACCOUNT_LOOKUP,
        RESULT_EMPTY,
        BALANCE_CHECK,
        INSUFFICIENT_FUNDS,
        BALANCE_UPDATE,
        RESULT_SUCCESS
    }

}
