package com.tradeflow.gateway.instrument;

import com.tradeflow.common.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Instrument config API (spec §10.3). Reads/writes are scoped to the caller's tenant via the
 * {@code TenantContext} ScopedValue; writes require RISK_MANAGER or ADMIN (§11.2 role matrix).
 */
@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final InstrumentService instrumentService;

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('RISK_MANAGER', 'ADMIN')")
    public InstrumentResponse register(@Valid @RequestBody RegisterInstrumentRequest request) {
        var instrument = instrumentService.register(
                TenantContext.tenantId(), request.symbol(), request.positionLimit(), request.markPrice());
        return InstrumentResponse.from(instrument);
    }

    @GetMapping
    public List<InstrumentResponse> list() {
        return instrumentService.list(TenantContext.tenantId()).stream()
                .map(InstrumentResponse::from)
                .toList();
    }

    @GetMapping("/{symbol}")
    public InstrumentResponse get(@PathVariable String symbol) {
        return InstrumentResponse.from(instrumentService.get(TenantContext.tenantId(), symbol));
    }

    @PatchMapping("/{symbol}")
    @PreAuthorize("hasAnyRole('RISK_MANAGER', 'ADMIN')")
    public InstrumentResponse update(@PathVariable String symbol,
                                     @Valid @RequestBody UpdateInstrumentRequest request) {
        var instrument = instrumentService.update(
                TenantContext.tenantId(), symbol, request.positionLimit(), request.markPrice());
        return InstrumentResponse.from(instrument);
    }
}
