package com.eventledger.account.web;

import com.eventledger.account.dto.AccountDetailsResponse;
import com.eventledger.account.dto.ApplyResult;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Applies a transaction to an account. Idempotent on {@code eventId}:
     * a new transaction returns 201; a replay of an existing one returns 200.
     */
    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<?> applyTransaction(@PathVariable String accountId,
                                              @Valid @RequestBody TransactionRequest request) {
        ApplyResult result = accountService.applyTransaction(accountId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.transaction());
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return accountService.getBalance(accountId);
    }

    @GetMapping("/{accountId}")
    public AccountDetailsResponse getAccount(@PathVariable String accountId) {
        return accountService.getAccountDetails(accountId);
    }
}
