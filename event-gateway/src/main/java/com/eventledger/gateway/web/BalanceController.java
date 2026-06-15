package com.eventledger.gateway.web;

import com.eventledger.gateway.client.AccountBalance;
import com.eventledger.gateway.service.EventGatewayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proxies balance queries to the Account Service. Because balances are owned by the Account
 * Service, this endpoint returns 503 (via the exception handler) when that service is unreachable.
 */
@RestController
public class BalanceController {

    private final EventGatewayService service;

    public BalanceController(EventGatewayService service) {
        this.service = service;
    }

    @GetMapping("/accounts/{accountId}/balance")
    public AccountBalance getBalance(@PathVariable String accountId) {
        return service.getBalance(accountId);
    }
}
