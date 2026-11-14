package com.billing.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/billing")
public class SubscriptionBillingController {

    private static final Set<String> VALID_TIERS = Set.of("BASIC", "STANDARD", "PREMIUM_4K");

    // Thread-safe stores simulating persistent idempotency and account state
    private final Map<String, Map<String, Object>> idempotencyCache = new ConcurrentHashMap<>();
    private final Map<String, List>> accountInvoices = new ConcurrentHashMap<>();
    private final Map<String, String> accountTiers = new ConcurrentHashMap<>();

    @PostMapping("/charge")
    public ResponseEntity> processCharge(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> payload) {

        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required Idempotency-Key header."));
        }

        // Return cached response if duplicate retry is detected
        if (idempotencyCache.containsKey(idempotencyKey)) {
            Map<String, Object> cached = new HashMap<>(idempotencyCache.get(idempotencyKey));
            cached.put("idempotentReplay", true);
            return ResponseEntity.ok(cached);
        }

        String accountId = (String) payload.get("accountId");
        Number amount = (Number) payload.get("amount");
        String currency = (String) payload.getOrDefault("currency", "USD");

        if (accountId == null || accountId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid accountId."));
        }
        if (amount == null || amount.doubleValue() <= 0 || Double.isNaN(amount.doubleValue())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Charge amount must be positive."));
        }

        Map<String, Object> invoice = new HashMap<>();
        invoice.put("transactionId", UUID.randomUUID().toString());
        invoice.put("accountId", accountId);
        invoice.put("amount", amount.doubleValue());
        invoice.put("currency", currency);
        invoice.put("status", "CAPTURED");
        invoice.put("timestamp", Instant.now().toString());
        invoice.put("idempotentReplay", false);

        idempotencyCache.put(idempotencyKey, invoice);
        accountInvoices.computeIfAbsent(accountId, k -> Collections.synchronizedList(new ArrayList<>())).add(invoice);

        return ResponseEntity.status(HttpStatus.CREATED).body(invoice);
    }

    @PutMapping("/subscriptions/{accountId}/tier")
    public ResponseEntity> updateSubscriptionTier(
            @PathVariable String accountId,
            @RequestBody Map<String, String> payload) {

        String newTier = payload.get("tier");
        if (newTier == null || !VALID_TIERS.contains(newTier.toUpperCase())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid tier. Allowed values: " + VALID_TIERS));
        }

        String normalizedTier = newTier.toUpperCase();
        String previousTier = accountTiers.put(accountId, normalizedTier);

        return ResponseEntity.ok(Map.of(
                "accountId", accountId,
                "previousTier", previousTier != null ? previousTier : "NONE",
                "currentTier", normalizedTier,
                "status", "UPDATED"
        ));
    }

    @GetMapping("/invoices/{accountId}")
    public ResponseEntity<List>> getAccountInvoices(@PathVariable String accountId) {
        List> invoices = accountInvoices.getOrDefault(accountId, Collections.emptyList());
        return ResponseEntity.ok(invoices);
    }
}
