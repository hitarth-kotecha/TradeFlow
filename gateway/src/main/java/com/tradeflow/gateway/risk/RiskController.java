package com.tradeflow.gateway.risk;

import com.tradeflow.common.context.TenantContext;
import com.tradeflow.common.web.PagedResponse;
import com.tradeflow.risk.RiskBreach;
import com.tradeflow.risk.RiskBreachRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Breach history for the tenant (FR-RISK-05, §10.6). RISK_MANAGER/ADMIN only. */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    private final RiskBreachRepository riskBreachRepository;

    public RiskController(RiskBreachRepository riskBreachRepository) {
        this.riskBreachRepository = riskBreachRepository;
    }

    @GetMapping("/breaches")
    @PreAuthorize("hasAnyRole('RISK_MANAGER', 'ADMIN')")
    public PagedResponse<BreachResponse> breaches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "detectedAt"));
        Page<RiskBreach> breaches = riskBreachRepository.findByTenantId(TenantContext.tenantId(), pageable);

        List<BreachResponse> content = breaches.getContent().stream().map(BreachResponse::from).toList();
        return new PagedResponse<>(content, breaches.getNumber(), breaches.getSize(),
                breaches.getTotalElements(), breaches.getTotalPages());
    }
}
