package com.tradeflow.gateway.position;

import com.tradeflow.common.context.TenantContext;
import com.tradeflow.gateway.instrument.Instrument;
import com.tradeflow.gateway.instrument.InstrumentRepository;
import com.tradeflow.gateway.web.NotFoundException;
import com.tradeflow.position.PositionStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Current net positions for the tenant (FR-POS-04, §10.5). Positions live in Redis; instrument
 * limits live in Postgres — we join them, reading all positions in one Redis MGET (§12.6).
 */
@RestController
@RequestMapping("/api/v1/positions")
public class PositionController {

    private final PositionStore positionStore;
    private final InstrumentRepository instrumentRepository;

    public PositionController(PositionStore positionStore, InstrumentRepository instrumentRepository) {
        this.positionStore = positionStore;
        this.instrumentRepository = instrumentRepository;
    }

    @GetMapping
    public List<PositionResponse> list() {
        UUID tenantId = TenantContext.tenantId();
        List<Instrument> instruments = instrumentRepository.findByTenantId(tenantId);
        List<String> symbols = instruments.stream().map(Instrument::getSymbol).toList();
        Map<String, Long> positions = positionStore.positions(tenantId, symbols);

        return instruments.stream()
                .map(i -> PositionResponse.of(i, positions.getOrDefault(i.getSymbol(), 0L)))
                .toList();
    }

    @GetMapping("/{symbol}")
    public PositionResponse get(@PathVariable String symbol) {
        UUID tenantId = TenantContext.tenantId();
        Instrument instrument = instrumentRepository.findByTenantIdAndSymbol(tenantId, symbol)
                .orElseThrow(() -> new NotFoundException("Unknown instrument: " + symbol));
        return PositionResponse.of(instrument, positionStore.position(tenantId, symbol));
    }
}
