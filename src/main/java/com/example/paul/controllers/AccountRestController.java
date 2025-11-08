package com.example.paul.controllers;

import com.example.paul.constants.constants;
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

        sanitizeAccountInput(accountInput);

        if (hasMissingAccountFields(accountInput) || !InputValidator.isSearchCriteriaValid(accountInput)) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(constants.INVALID_SEARCH_CRITERIA);
        }

        Account account = accountService.getAccount(
                accountInput.getSortCode(), accountInput.getAccountNumber());

        if (account == null) {
            return ResponseEntity.ok(constants.NO_ACCOUNT_FOUND);
        }

        return ResponseEntity.ok(account);
    }


    @PutMapping(value = "/accounts",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createAccount(
            @Valid @RequestBody CreateAccountInput createAccountInput) {
        LOGGER.debug("Triggered AccountRestController.createAccountInput");

        sanitizeCreateAccountInput(createAccountInput);

        if (hasMissingCreateFields(createAccountInput) ||
                !InputValidator.isCreateAccountCriteriaValid(createAccountInput)) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(constants.INVALID_SEARCH_CRITERIA);
        }

        if (!hasMinimumLength(createAccountInput.getBankName(), MIN_ACCOUNT_NAME_LENGTH)) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(constants.BANK_NAME_TOO_SHORT);
        }

        if (!hasMinimumLength(createAccountInput.getOwnerName(), MIN_ACCOUNT_NAME_LENGTH)) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(constants.OWNER_NAME_TOO_SHORT);
        }

        Account createdAccount = accountService.createAccount(
                createAccountInput.getBankName(), createAccountInput.getOwnerName());

        if (createdAccount == null) {
            return ResponseEntity
                    .status(HttpStatus.OK)
                    .body(constants.CREATE_ACCOUNT_FAILED);
        }

        return ResponseEntity.ok(createdAccount);
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasMinimumLength(String value, int minLength) {
        return value != null && value.length() >= minLength;
    }

    private void sanitizeAccountInput(AccountInput accountInput) {
        accountInput.setSortCode(trimToNull(accountInput.getSortCode()));
        accountInput.setAccountNumber(trimToNull(accountInput.getAccountNumber()));
    }

    private void sanitizeCreateAccountInput(CreateAccountInput createAccountInput) {
        createAccountInput.setBankName(trimToNull(createAccountInput.getBankName()));
        createAccountInput.setOwnerName(trimToNull(createAccountInput.getOwnerName()));
    }

    private boolean hasMissingAccountFields(AccountInput accountInput) {
        return isBlank(accountInput.getSortCode()) || isBlank(accountInput.getAccountNumber());
    }

    private boolean hasMissingCreateFields(CreateAccountInput createAccountInput) {
        return isBlank(createAccountInput.getBankName()) || isBlank(createAccountInput.getOwnerName());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
