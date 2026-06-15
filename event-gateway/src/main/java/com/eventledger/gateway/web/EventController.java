package com.eventledger.gateway.web;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.dto.ProcessResult;
import com.eventledger.gateway.service.EventGatewayService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventGatewayService service;

    public EventController(EventGatewayService service) {
        this.service = service;
    }

    /**
     * Submit a transaction event. Returns 201 for a newly applied event, 200 for an idempotent
     * duplicate. If the Account Service is unavailable, the exception handler returns 503.
     */
    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        ProcessResult result = service.process(request);
        if (result.created()) {
            return ResponseEntity
                    .created(URI.create("/events/" + result.event().eventId()))
                    .body(result.event());
        }
        return ResponseEntity.status(HttpStatus.OK).body(result.event());
    }

    /** Retrieve a single event by id. Served entirely from local data (works even if downstream is down). */
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getById(@PathVariable String id) {
        return service.getEvent(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * List events for an account, ordered chronologically by event timestamp. Local data only.
     * Paginated (default 50 per page) so a high-volume account can't return an unbounded payload;
     * override with {@code ?page=&size=}.
     */
    @GetMapping(params = "account")
    public List<EventResponse> listByAccount(@RequestParam("account") String accountId,
                                             @PageableDefault(size = 50) Pageable pageable) {
        return service.getEventsForAccount(accountId, pageable);
    }
}
