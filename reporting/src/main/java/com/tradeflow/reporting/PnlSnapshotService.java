package com.tradeflow.reporting;

import com.tradeflow.common.concurrent.Scopes;
import com.tradeflow.trade.TradeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Computes P&L snapshots for a date (§14.1). Partitioned by tenant; within a tenant the per-instrument
 * computations are independent, so they fan out on virtual threads via {@link Scopes#fanOut} — the
 * structured-concurrency showcase. Writes are idempotent: an existing (tenant, date, symbol) row is
 * updated, not duplicated (FR-PNL-05).
 */
@Service
public class PnlSnapshotService {

    private final JdbcTemplate jdbcTemplate;
    private final TradeRepository tradeRepository;
    private final PnlSnapshotRepository pnlSnapshotRepository;
    private final PnlCalculator pnlCalculator;

    public PnlSnapshotService(JdbcTemplate jdbcTemplate, TradeRepository tradeRepository,
                              PnlSnapshotRepository pnlSnapshotRepository, PnlCalculator pnlCalculator) {
        this.jdbcTemplate = jdbcTemplate;
        this.tradeRepository = tradeRepository;
        this.pnlSnapshotRepository = pnlSnapshotRepository;
        this.pnlCalculator = pnlCalculator;
    }

    public void computeForDate(LocalDate snapshotDate) {
        List<UUID> tenantIds = jdbcTemplate.queryForList("SELECT id FROM tenants", UUID.class);
        for (UUID tenantId : tenantIds) {
            try {
                computeTenant(tenantId, snapshotDate);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted computing P&L for tenant " + tenantId, e);
            }
        }
    }

    private void computeTenant(UUID tenantId, LocalDate snapshotDate) throws InterruptedException {
        var cutoff = snapshotDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<InstrumentRow> instruments = jdbcTemplate.query(
                "SELECT symbol, mark_price FROM instruments WHERE tenant_id = ?::uuid",
                (rs, i) -> new InstrumentRow(rs.getString("symbol"), rs.getBigDecimal("mark_price")),
                tenantId.toString());
        if (instruments.isEmpty()) {
            return;
        }

        // One subtask per instrument — independent, fail-fast, on virtual threads (§14.1).
        List<Callable<InstrumentPnl>> tasks = instruments.stream()
                .map(inst -> (Callable<InstrumentPnl>) () -> computeInstrument(tenantId, inst, cutoff))
                .toList();
        List<InstrumentPnl> results = Scopes.fanOut(tasks);

        results.forEach(result -> upsert(tenantId, snapshotDate, result));
    }

    private InstrumentPnl computeInstrument(UUID tenantId, InstrumentRow instrument,
                                            java.time.OffsetDateTime cutoff) {
        List<TradeInput> trades = tradeRepository
                .findByTenantIdAndInstrumentSymbolAndSubmittedAtLessThanOrderBySubmittedAtAsc(
                        tenantId, instrument.symbol(), cutoff)
                .stream()
                .map(t -> new TradeInput(t.getSide(), t.getQuantity(), t.getPrice()))
                .toList();
        return new InstrumentPnl(instrument.symbol(), pnlCalculator.compute(trades, instrument.markPrice()));
    }

    private void upsert(UUID tenantId, LocalDate snapshotDate, InstrumentPnl pnl) {
        PnlResult r = pnl.result();
        pnlSnapshotRepository.findByTenantIdAndSnapshotDateAndInstrumentSymbol(tenantId, snapshotDate, pnl.symbol())
                .ifPresentOrElse(
                        existing -> existing.update(r.realizedPnl(), r.unrealizedPnl(), r.netPosition(), r.avgCost()),
                        () -> pnlSnapshotRepository.save(PnlSnapshot.of(tenantId, snapshotDate, pnl.symbol(),
                                r.realizedPnl(), r.unrealizedPnl(), r.netPosition(), r.avgCost())));
    }

    private record InstrumentRow(String symbol, BigDecimal markPrice) {
    }

    private record InstrumentPnl(String symbol, PnlResult result) {
    }
}
