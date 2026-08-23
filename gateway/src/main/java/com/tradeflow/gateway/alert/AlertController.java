package com.tradeflow.gateway.alert;

import com.tradeflow.alert.Alert;
import com.tradeflow.alert.AlertRepository;
import com.tradeflow.common.context.TenantContext;
import com.tradeflow.common.web.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Persisted alert history for the tenant (FR-ALERT-04, §10.7). */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertRepository alertRepository;

    public AlertController(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @GetMapping
    public PagedResponse<AlertResponse> list(
            @RequestParam(required = false) Boolean unread,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID tenantId = TenantContext.tenantId();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Alert> alerts = Boolean.TRUE.equals(unread)
                ? alertRepository.findByTenantIdAndReadAtIsNull(tenantId, pageable)
                : alertRepository.findByTenantId(tenantId, pageable);

        List<AlertResponse> content = alerts.getContent().stream().map(AlertResponse::from).toList();
        return new PagedResponse<>(content, alerts.getNumber(), alerts.getSize(),
                alerts.getTotalElements(), alerts.getTotalPages());
    }
}
