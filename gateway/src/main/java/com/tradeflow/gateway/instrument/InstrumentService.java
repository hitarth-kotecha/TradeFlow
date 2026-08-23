package com.tradeflow.gateway.instrument;

import com.tradeflow.gateway.web.ConflictException;
import com.tradeflow.gateway.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Instrument configuration, always scoped to the given tenant. Because every query carries
 * {@code tenantId}, a symbol belonging to another tenant is simply "not found" (→ 404, never 403).
 */
@Service
public class InstrumentService {

    private final InstrumentRepository instrumentRepository;

    public InstrumentService(InstrumentRepository instrumentRepository) {
        this.instrumentRepository = instrumentRepository;
    }

    @Transactional
    public Instrument register(UUID tenantId, String symbol, long positionLimit, BigDecimal markPrice) {
        if (instrumentRepository.existsByTenantIdAndSymbol(tenantId, symbol)) {
            throw new ConflictException("Instrument already registered: " + symbol);
        }
        return instrumentRepository.save(Instrument.register(tenantId, symbol, positionLimit, markPrice));
    }

    @Transactional(readOnly = true)
    public List<Instrument> list(UUID tenantId) {
        return instrumentRepository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Instrument get(UUID tenantId, String symbol) {
        return instrumentRepository.findByTenantIdAndSymbol(tenantId, symbol)
                .orElseThrow(() -> new NotFoundException("Instrument not found: " + symbol));
    }

    @Transactional
    public Instrument update(UUID tenantId, String symbol, long positionLimit, BigDecimal markPrice) {
        Instrument instrument = get(tenantId, symbol);   // 404 if not in this tenant
        instrument.updateConfig(positionLimit, markPrice);
        return instrument;   // dirty-checked and flushed within the transaction
    }
}
