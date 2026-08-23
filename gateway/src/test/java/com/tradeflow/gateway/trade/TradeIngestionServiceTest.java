package com.tradeflow.gateway.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.event.TradeSubmittedEvent;
import com.tradeflow.common.trade.Side;
import com.tradeflow.trade.OutboxEvent;
import com.tradeflow.trade.OutboxRepository;
import com.tradeflow.trade.OutboxStatus;
import com.tradeflow.trade.Trade;
import com.tradeflow.trade.TradeIngestionService;
import com.tradeflow.trade.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit test for the transactional-outbox write and idempotency (Concept 1), no DB. */
@ExtendWith(MockitoExtension.class)
class TradeIngestionServiceTest {

    @Mock
    private TradeRepository tradeRepository;
    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private TradeIngestionService service;

    @BeforeEach
    void setUp() {
        service = new TradeIngestionService(tradeRepository, outboxRepository, objectMapper);
    }

    @Test
    void persistsTradeAndOutboxEventTogether() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(tradeRepository.findByTenantIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());

        Trade trade = service.ingest(tenantId, userId, "CRUDE-OIL", Side.BUY, 100, new BigDecimal("72.55"), "key-1");

        verify(tradeRepository).save(trade);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getTopic()).isEqualTo("trade.submitted");
        assertThat(outbox.getAggregateId()).isEqualTo(trade.getId());

        TradeSubmittedEvent event = objectMapper.readValue(outbox.getPayload(), TradeSubmittedEvent.class);
        assertThat(event.tradeId()).isEqualTo(trade.getId());
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.side()).isEqualTo(Side.BUY);
        assertThat(event.quantity()).isEqualTo(100L);
    }

    @Test
    void returnsOriginalTradeForDuplicateIdempotencyKey() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Trade original = Trade.accepted(tenantId, userId, "CRUDE-OIL", Side.BUY, 100, new BigDecimal("72.55"), "key-1");
        when(tradeRepository.findByTenantIdAndIdempotencyKey(tenantId, "key-1")).thenReturn(Optional.of(original));

        Trade result = service.ingest(tenantId, userId, "CRUDE-OIL", Side.BUY, 100, new BigDecimal("72.55"), "key-1");

        assertThat(result).isSameAs(original);
        verify(tradeRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());   // no duplicate trade, no duplicate event
    }
}
