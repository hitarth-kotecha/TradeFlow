package com.tradeflow.gateway.summary;

import com.tradeflow.common.context.TenantContext;
import com.tradeflow.gateway.instrument.InstrumentRepository;
import com.tradeflow.position.PositionStore;
import com.tradeflow.reporting.PnlSnapshotRepository;
import com.tradeflow.risk.RiskBreachRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-SC-01 (fail-fast) + scoped-value inheritance at the unit level. */
@ExtendWith(MockitoExtension.class)
class SummaryServiceTest {

    @Mock private InstrumentRepository instrumentRepository;
    @Mock private PositionStore positionStore;
    @Mock private RiskBreachRepository riskBreachRepository;
    @Mock private PnlSnapshotRepository pnlSnapshotRepository;

    private SummaryService service;

    @BeforeEach
    void setUp() {
        service = new SummaryService(instrumentRepository, positionStore, riskBreachRepository, pnlSnapshotRepository);
    }

    @Test
    void failsFastWhenASubReadThrows() {
        UUID tenantId = UUID.randomUUID();
        lenient().when(instrumentRepository.findByTenantId(any())).thenReturn(List.of());
        lenient().when(positionStore.positions(any(), any())).thenReturn(Map.of());
        lenient().when(pnlSnapshotRepository.findTopByTenantIdOrderBySnapshotDateDesc(any()))
                .thenReturn(Optional.empty());
        when(riskBreachRepository.findByTenantId(any(), any())).thenThrow(new RuntimeException("boom"));

        ScopedValue.where(TenantContext.TENANT_ID, tenantId).run(() ->
                assertThatThrownBy(service::summary).isInstanceOf(SummaryFailedException.class));
    }

    @Test
    void eachSubtaskReadsTheInheritedTenantId() {
        UUID tenantId = UUID.randomUUID();
        when(instrumentRepository.findByTenantId(tenantId)).thenReturn(List.of());
        when(positionStore.positions(eq(tenantId), any())).thenReturn(Map.of());
        when(riskBreachRepository.findByTenantId(eq(tenantId), any())).thenReturn(Page.empty());
        when(pnlSnapshotRepository.findTopByTenantIdOrderBySnapshotDateDesc(tenantId)).thenReturn(Optional.empty());

        AccountSummary[] result = new AccountSummary[1];
        ScopedValue.where(TenantContext.TENANT_ID, tenantId).run(() -> result[0] = service.summary());

        assertThat(result[0].positions()).isEmpty();
        assertThat(result[0].recentBreaches()).isEmpty();
        assertThat(result[0].latestPnl()).isNull();
        // Each forked subtask passed the INHERITED tenantId to its repository — no id was passed in.
        verify(instrumentRepository).findByTenantId(tenantId);
        verify(riskBreachRepository).findByTenantId(eq(tenantId), any());
        verify(pnlSnapshotRepository).findTopByTenantIdOrderBySnapshotDateDesc(tenantId);
    }
}
