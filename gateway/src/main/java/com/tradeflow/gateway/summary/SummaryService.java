package com.tradeflow.gateway.summary;

import com.tradeflow.common.context.TenantContext;
import com.tradeflow.gateway.instrument.Instrument;
import com.tradeflow.gateway.instrument.InstrumentRepository;
import com.tradeflow.gateway.position.PositionResponse;
import com.tradeflow.gateway.risk.BreachResponse;
import com.tradeflow.position.PositionStore;
import com.tradeflow.reporting.PnlSnapshotRepository;
import com.tradeflow.risk.RiskBreachRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * Builds the account summary by fanning out three independent reads with a {@link StructuredTaskScope}
 * (Concept 1, FR-SUM-*). Fail-fast (a failing sub-read cancels the others, FR-SUM-03) and leak-free
 * (the scope can't exit with a subtask still running). Each forked subtask reads {@code tenantId}
 * from the inherited {@code ScopedValue} — no tenant id is passed in (FR-SUM-04).
 */
@Service
public class SummaryService {

    private final InstrumentRepository instrumentRepository;
    private final PositionStore positionStore;
    private final RiskBreachRepository riskBreachRepository;
    private final PnlSnapshotRepository pnlSnapshotRepository;

    public SummaryService(InstrumentRepository instrumentRepository, PositionStore positionStore,
                          RiskBreachRepository riskBreachRepository, PnlSnapshotRepository pnlSnapshotRepository) {
        this.instrumentRepository = instrumentRepository;
        this.positionStore = positionStore;
        this.riskBreachRepository = riskBreachRepository;
        this.pnlSnapshotRepository = pnlSnapshotRepository;
    }

    public AccountSummary summary() {
        try (var scope = StructuredTaskScope.open()) {
            Subtask<List<PositionResponse>> positions = scope.fork(this::loadPositions);
            Subtask<List<BreachResponse>> breaches = scope.fork(this::loadRecentBreaches);
            Subtask<PnlSnapshotResponse> pnl = scope.fork(this::loadLatestPnl);

            scope.join();   // waits for all; fail-fast cancels siblings and throws on first failure

            return new AccountSummary(positions.get(), breaches.get(), pnl.get());
        } catch (StructuredTaskScope.FailedException e) {
            throw new SummaryFailedException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SummaryFailedException(e);
        }
    }

    private List<PositionResponse> loadPositions() {
        UUID tenantId = TenantContext.tenantId();   // inherited scoped value
        List<Instrument> instruments = instrumentRepository.findByTenantId(tenantId);
        List<String> symbols = instruments.stream().map(Instrument::getSymbol).toList();
        Map<String, Long> positions = positionStore.positions(tenantId, symbols);
        return instruments.stream()
                .map(i -> PositionResponse.of(i, positions.getOrDefault(i.getSymbol(), 0L)))
                .toList();
    }

    private List<BreachResponse> loadRecentBreaches() {
        return riskBreachRepository
                .findByTenantId(TenantContext.tenantId(),
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "detectedAt")))
                .getContent().stream()
                .map(BreachResponse::from)
                .toList();
    }

    private PnlSnapshotResponse loadLatestPnl() {
        return pnlSnapshotRepository.findTopByTenantIdOrderBySnapshotDateDesc(TenantContext.tenantId())
                .map(PnlSnapshotResponse::from)
                .orElse(null);
    }
}
