package com.example.paul.controllers;

import com.example.paul.constants.constants;
import com.example.paul.controllers.workflow.OperationOutcome;
import com.example.paul.controllers.workflow.OutcomeBuilder;
import com.example.paul.models.Account;
import com.example.paul.services.AccountService;
import com.example.paul.utils.AccountInput;
import com.example.paul.utils.CreateAccountInput;
import com.example.paul.utils.InputValidator;
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

@RestController
@RequestMapping("api/v1")
public class AccountRestController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountRestController.class);

    private final AccountService accountService;
    private static final int MIN_ACCOUNT_NAME_LENGTH = 3;

    @Autowired
    public AccountRestController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping(value = "/accounts",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> checkAccountBalance(
            // TODO In the future support searching by card number in addition to sort code and account number
            @Valid @RequestBody AccountInput accountInput) {
        LOGGER.debug("Triggered AccountRestController.accountInput");

        OperationOutcome<Account, DecisionPath> outcome = evaluateAccountLookup(accountInput);
        logOutcome(outcome, "account lookup");
        return convertOutcome(outcome,
                constants.NO_ACCOUNT_FOUND,
                constants.INVALID_SEARCH_CRITERIA,
                constants.NO_ACCOUNT_FOUND);
    }


    @PutMapping(value = "/accounts",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createAccount(
            @Valid @RequestBody CreateAccountInput createAccountInput) {
        LOGGER.debug("Triggered AccountRestController.createAccountInput");

        OperationOutcome<Account, DecisionPath> outcome = evaluateAccountCreation(createAccountInput);
        logOutcome(outcome, "account creation");
        return convertOutcome(outcome,
                constants.CREATE_ACCOUNT_FAILED,
                constants.INVALID_SEARCH_CRITERIA,
                constants.CREATE_ACCOUNT_FAILED);
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
                return new ResponseEntity<>(resolveMessage(outcome.getMessage(), invalidMessage), outcome.getStatus());
            case EMPTY_RESULT:
                return new ResponseEntity<>(resolveMessage(outcome.getMessage(), emptyResultMessage), outcome.getStatus());
            case FAILURE:
                return new ResponseEntity<>(resolveMessage(outcome.getMessage(), failureMessage), outcome.getStatus());
            default:
                throw new IllegalStateException("Unhandled outcome type: " + outcome.getType());
        }
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

    private OperationOutcome<Account, DecisionPath> evaluateAccountLookup(AccountInput accountInput) {
        OutcomeBuilder<Account, DecisionPath> builder = OutcomeBuilder.<Account, DecisionPath>begin()
                .record(DecisionPath.PRE_VALIDATION);

        String sanitizedSortCode = sanitizeAndRecord(accountInput.getSortCode(),
                DecisionPath.SORT_CODE_SANITIZED, builder);
        String sanitizedAccountNumber = sanitizeAndRecord(accountInput.getAccountNumber(),
                DecisionPath.ACCOUNT_NUMBER_SANITIZED, builder);

        if (isBlank(sanitizedSortCode) || isBlank(sanitizedAccountNumber)) {
            builder.record(DecisionPath.VALIDATION_FAILED_MISSING_FIELDS);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.INVALID_SEARCH_CRITERIA);
        }

        if (!InputValidator.isSearchCriteriaValid(accountInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.INVALID_SEARCH_CRITERIA);
        }

        builder.record(DecisionPath.SERVICE_INVOCATION);
        Account account = accountService.getAccount(sanitizedSortCode, sanitizedAccountNumber);

        if (account == null) {
            builder.record(DecisionPath.RESULT_EMPTY);
            return builder.buildEmpty(HttpStatus.OK, constants.NO_ACCOUNT_FOUND);
        }

        builder.record(DecisionPath.ACCOUNT_VERIFICATION_STARTED);
        if (!isAccountVerified(account)) {
            builder.record(DecisionPath.ACCOUNT_VERIFICATION_FAILED);
            return builder.buildFailure(HttpStatus.OK, constants.ACCOUNT_VERIFICATION_FAILED);
        }
        builder.record(DecisionPath.ACCOUNT_VERIFICATION_PASSED);

        builder.record(DecisionPath.RESULT_SUCCESS);
        return builder.buildSuccess(account, HttpStatus.OK);
    }

    private OperationOutcome<Account, DecisionPath> evaluateAccountCreation(CreateAccountInput createAccountInput) {
        OutcomeBuilder<Account, DecisionPath> builder = OutcomeBuilder.<Account, DecisionPath>begin()
                .record(DecisionPath.PRE_VALIDATION);

        String sanitizedBankName = sanitizeAndRecord(createAccountInput.getBankName(),
                DecisionPath.BANK_NAME_SANITIZED, builder);
        String sanitizedOwnerName = sanitizeAndRecord(createAccountInput.getOwnerName(),
                DecisionPath.OWNER_NAME_SANITIZED, builder);

        if (isBlank(sanitizedBankName) || isBlank(sanitizedOwnerName)) {
            builder.record(DecisionPath.VALIDATION_FAILED_MISSING_FIELDS);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.INVALID_SEARCH_CRITERIA);
        }

        if (!InputValidator.isCreateAccountCriteriaValid(createAccountInput)) {
            builder.record(DecisionPath.VALIDATION_FAILED_GENERIC);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.INVALID_SEARCH_CRITERIA);
        }

        if (!hasMinimumLength(sanitizedBankName, MIN_ACCOUNT_NAME_LENGTH)) {
            builder.record(DecisionPath.BANK_NAME_TOO_SHORT);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.BANK_NAME_TOO_SHORT);
        }

        if (!hasMinimumLength(sanitizedOwnerName, MIN_ACCOUNT_NAME_LENGTH)) {
            builder.record(DecisionPath.OWNER_NAME_TOO_SHORT);
            return builder.buildInvalid(HttpStatus.BAD_REQUEST, constants.OWNER_NAME_TOO_SHORT);
        }

        builder.record(DecisionPath.CREATION_ATTEMPT);
        Account account = accountService.createAccount(sanitizedBankName, sanitizedOwnerName);

        if (account == null) {
            builder.record(DecisionPath.CREATION_FAILURE);
            return builder.buildEmpty(HttpStatus.OK, constants.CREATE_ACCOUNT_FAILED);
        }

        builder.record(DecisionPath.ACCOUNT_VERIFICATION_STARTED);
        if (!isAccountVerified(account)) {
            builder.record(DecisionPath.ACCOUNT_VERIFICATION_FAILED);
            return builder.buildFailure(HttpStatus.OK, constants.ACCOUNT_VERIFICATION_FAILED);
        }
        builder.record(DecisionPath.ACCOUNT_VERIFICATION_PASSED);

        builder.record(DecisionPath.CREATION_SUCCESS);
        return builder.buildSuccess(account, HttpStatus.OK);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private <T> String sanitizeAndRecord(String value, DecisionPath path, OutcomeBuilder<T, DecisionPath> builder) {
        if (value == null) {
            return null;
        }
        String sanitized = value.trim();
        if (!sanitized.equals(value)) {
            builder.record(path);
        }
        return sanitized;
    }

    private boolean hasMinimumLength(String value, int minLength) {
        return value != null && value.length() >= minLength;
    }

    private enum DecisionPath {
        PRE_VALIDATION,
        VALIDATION_FAILED_MISSING_FIELDS,
        VALIDATION_FAILED_GENERIC,
        SORT_CODE_SANITIZED,
        ACCOUNT_NUMBER_SANITIZED,
        BANK_NAME_SANITIZED,
        OWNER_NAME_SANITIZED,
        SERVICE_INVOCATION,
        RESULT_EMPTY,
        RESULT_SUCCESS,
        CREATION_ATTEMPT,
        CREATION_FAILURE,
        CREATION_SUCCESS,
        BANK_NAME_TOO_SHORT,
        OWNER_NAME_TOO_SHORT,
        ACCOUNT_VERIFICATION_STARTED,
        ACCOUNT_VERIFICATION_FAILED,
        ACCOUNT_VERIFICATION_PASSED
    }

    private boolean isAccountVerified(Account account) {
        if (account == null) {
            return false;
        }
        boolean hasOwner = !isBlank(account.getOwnerName());
        boolean hasIdentifiers = !isBlank(account.getSortCode()) && !isBlank(account.getAccountNumber());
        return hasOwner && hasIdentifiers;
    }
}
