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

    private static final Logger logger = LoggerFactory.getLogger(TransactionRestController.class);

    private final AccountService accountService;
    private final TransactionService transactionService;
    private static final double MAX_TRANSACTION_AMOUNT = 1000000.0;

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
        logger.debug("TransactionRestController.makeTransfer triggered");

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
        logger.debug("TransactionRestController.withdraw triggered");

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
        logger.debug("TransactionRestController.deposit triggered");

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
        if (logger.isDebugEnabled()) {
            logger.debug("Outcome for {} -> type={}, status={}, trail={}",
                    context, outcome.getType(), outcome.getStatus(), outcome.getDecisionTrail());
        }
    }

    private OperationOutcome<Boolean, DecisionPath> evaluateTransfer(TransactionInput transactionInput) {
        OutcomeBuilder<Boolean, DecisionPath> builder = OutcomeBuilder.<Boolean, DecisionPath>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!validateAndNormalizeAmount(transactionInput::setAmount, transactionInput.getAmount(), builder)) {
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION_AMOUNT);
        }

        if (transactionInput.getAmount() > MAX_TRANSACTION_AMOUNT) {
            builder.record(DecisionPath.AMOUNT_INVALID);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION_AMOUNT);
        }

        if (!InputValidator.isSearchTransactionValid(transactionInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION);
        }

        logger.info("Processing transfer with amount: {}", transactionInput.getAmount());

        builder.record(DecisionPath.TRANSFER_ATTEMPT);
        boolean transferCompleted = transactionService.makeTransfer(transactionInput);

        if (!transferCompleted) {
            builder.record(DecisionPath.TRANSFER_FAILED);
            return builder.buildFailure(Boolean.FALSE, HttpStatus.UNPROCESSABLE_ENTITY, INVALID_TRANSACTION);
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

        if (withdrawInput.getAmount() > MAX_TRANSACTION_AMOUNT) {
            builder.record(DecisionPath.AMOUNT_INVALID);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION_AMOUNT);
        }

        if (!InputValidator.isSearchCriteriaValid(withdrawInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        builder.record(DecisionPath.ACCOUNT_LOOKUP);
        Account targetAccount = accountService.getAccount(withdrawInput.getSortCode(), withdrawInput.getAccountNumber());

        if (targetAccount == null) {
            builder.record(DecisionPath.RESULT_EMPTY);
            return builder.buildEmpty(HttpStatus.NOT_FOUND, NO_ACCOUNT_FOUND);
        }

        if (!isAccountValidForTransaction(targetAccount)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        logger.info("Processing withdrawal of {} for account {}", withdrawInput.getAmount(), targetAccount.getAccountNumber());

        builder.record(DecisionPath.BALANCE_CHECK);
        if (!transactionService.isAmountAvailable(withdrawInput.getAmount(), targetAccount.getCurrentBalance())) {
            builder.record(DecisionPath.INSUFFICIENT_FUNDS);
            return builder.buildFailure(HttpStatus.UNPROCESSABLE_ENTITY, INSUFFICIENT_ACCOUNT_BALANCE);
        }

        builder.record(DecisionPath.BALANCE_UPDATE);
        transactionService.updateAccountBalance(targetAccount, withdrawInput.getAmount(), ACTION.WITHDRAW);

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(SUCCESS, HttpStatus.OK);
    }

    private OperationOutcome<String, DecisionPath> evaluateDeposit(DepositInput depositInput) {
        OutcomeBuilder<String, DecisionPath> builder = OutcomeBuilder.<String, DecisionPath>begin()
                .record(DecisionPath.PRE_VALIDATION);

        if (!validateAndNormalizeAmount(depositInput::setAmount, depositInput.getAmount(), builder)) {
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION_AMOUNT);
        }

        if (depositInput.getAmount() > MAX_TRANSACTION_AMOUNT) {
            builder.record(DecisionPath.AMOUNT_INVALID);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_TRANSACTION_AMOUNT);
        }

        if (!InputValidator.isAccountNoValid(depositInput.getTargetAccountNo())) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        builder.record(DecisionPath.ACCOUNT_LOOKUP);
        Account depositAccount = accountService.getAccount(depositInput.getTargetAccountNo());

        if (depositAccount == null) {
            builder.record(DecisionPath.RESULT_EMPTY);
            return builder.buildEmpty(HttpStatus.NOT_FOUND, NO_ACCOUNT_FOUND);
        }

        if (!isAccountValidForTransaction(depositAccount)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, INVALID_SEARCH_CRITERIA);
        }

        logger.info("Processing deposit of {} to account {}", depositInput.getAmount(), depositAccount.getAccountNumber());

        builder.record(DecisionPath.BALANCE_UPDATE);
        transactionService.updateAccountBalance(depositAccount, depositInput.getAmount(), ACTION.DEPOSIT);

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(SUCCESS, HttpStatus.OK);
    }

    private boolean validateAndNormalizeAmount(java.util.function.DoubleConsumer sanitizer,
                                               double amount,
                                               OutcomeBuilder<?, DecisionPath> builder) {
        builder.record(DecisionPath.AMOUNT_VALIDATION);
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            builder.record(DecisionPath.AMOUNT_INVALID);
            return false;
        }

        double normalizedAmount = BigDecimal.valueOf(amount)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();

        if (normalizedAmount <= 0) {
            builder.record(DecisionPath.AMOUNT_INVALID);
            return false;
        }

        sanitizer.accept(normalizedAmount);
        if (normalizedAmount != amount) {
            builder.record(DecisionPath.AMOUNT_SANITIZED);
        }

        return true;
    }

    private boolean isAccountValidForTransaction(Account account) {
        if (account == null) {
            return false;
        }
        boolean hasOwner = account.getOwnerName() != null && !account.getOwnerName().trim().isEmpty();
        boolean hasSortCode = account.getSortCode() != null && !account.getSortCode().trim().isEmpty();
        boolean hasAccountNumber = account.getAccountNumber() != null && !account.getAccountNumber().trim().isEmpty();
        return hasOwner && hasSortCode && hasAccountNumber;
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
