package com.tradeflow.risk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BreachRecorderTest {

    @Mock
    private RiskBreachRepository riskBreachRepository;
    @InjectMocks
    private BreachRecorder breachRecorder;

    @Test
    void recordsNewBreach() {
        when(riskBreachRepository.existsByTenantIdAndTradeId(any(), any())).thenReturn(false);

        boolean recorded = breachRecorder.record(
                UUID.randomUUID(), UUID.randomUUID(), "OIL", 11_000, 10_000);

        assertThat(recorded).isTrue();
        verify(riskBreachRepository).save(any(RiskBreach.class));
    }

    @Test
    void skipsAlreadyRecordedBreach() {
        when(riskBreachRepository.existsByTenantIdAndTradeId(any(), any())).thenReturn(true);

        boolean recorded = breachRecorder.record(
                UUID.randomUUID(), UUID.randomUUID(), "OIL", 11_000, 10_000);

        assertThat(recorded).isFalse();
        verify(riskBreachRepository, never()).save(any());
    }
}
