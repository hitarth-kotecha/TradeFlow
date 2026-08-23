package com.tradeflow.gateway.trade;

import com.tradeflow.common.context.TenantContext;
import com.tradeflow.common.web.PagedResponse;
import com.tradeflow.gateway.instrument.InstrumentRepository;
import com.tradeflow.gateway.web.NotFoundException;
import com.tradeflow.trade.Trade;
import com.tradeflow.trade.TradeIngestionService;
import com.tradeflow.trade.TradeRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Trades API (§10.4). Any authenticated role may submit (§11.2). Tenant scoping comes from the
 * ScopedValue context; V4 (instrument exists) is checked here because this module owns instruments.
 */
@RestController
@RequestMapping("/api/v1/trades")
public class TradeController {

    private final TradeIngestionService ingestionService;
    private final InstrumentRepository instrumentRepository;
    private final TradeRepository tradeRepository;

    public TradeController(TradeIngestionService ingestionService, InstrumentRepository instrumentRepository,
                           TradeRepository tradeRepository) {
        this.ingestionService = ingestionService;
        this.instrumentRepository = instrumentRepository;
        this.tradeRepository = tradeRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse submit(@Valid @RequestBody SubmitTradeRequest request,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID tenantId = TenantContext.tenantId();

        // V4: reject a trade on an instrument this tenant hasn't registered (404, per §10.4).
        if (!instrumentRepository.existsByTenantIdAndSymbol(tenantId, request.instrumentSymbol())) {
            throw new NotFoundException("Unknown instrument: " + request.instrumentSymbol());
        }

        Trade trade = ingestionService.ingest(tenantId, TenantContext.userId(),
                request.instrumentSymbol(), request.side(), request.quantity(), request.price(), idempotencyKey);
        return TradeResponse.from(trade);
    }

    @GetMapping
    public PagedResponse<TradeResponse> list(
            @RequestParam(required = false) String instrument,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID tenantId = TenantContext.tenantId();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "submittedAt"));

        Page<Trade> trades = (instrument == null)
                ? tradeRepository.findByTenantId(tenantId, pageable)
                : tradeRepository.findByTenantIdAndInstrumentSymbol(tenantId, instrument, pageable);

        List<TradeResponse> content = trades.getContent().stream().map(TradeResponse::from).toList();
        return new PagedResponse<>(content, trades.getNumber(), trades.getSize(),
                trades.getTotalElements(), trades.getTotalPages());
    }
}
