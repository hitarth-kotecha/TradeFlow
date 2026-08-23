package com.tradeflow.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.trade.Side;
import com.tradeflow.common.event.TradeSubmittedEvent;
import com.tradeflow.position.PositionStore;
import com.tradeflow.position.PositionUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskEngineConsumerTest {

    @Mock private PositionStore positionStore;
    @Mock private LimitResolver limitResolver;
    @Mock private BreachRecorder breachRecorder;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RiskEngineConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RiskEngineConsumer(positionStore, limitResolver, breachRecorder, kafkaTemplate, objectMapper);
        when(limitResolver.limitFor(any(), any())).thenReturn(10_000L);
    }

    @Test
    void appliesBuyDeltaAndPublishesPositionUpdated() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        when(positionStore.applyAndEvaluate(any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new PositionUpdate(100, false, false));

        consumer.onTradeSubmitted(event(tenantId, tradeId, Side.BUY, 100));

        verify(positionStore).applyAndEvaluate(tenantId, "OIL", tradeId, 100L, 10_000L);
        verify(kafkaTemplate).send(eq("position.updated"), eq(tenantId.toString()), anyString());
        verify(breachRecorder, never()).record(any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void sellAppliesNegativeDelta() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        when(positionStore.applyAndEvaluate(any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new PositionUpdate(-40, false, false));

        consumer.onTradeSubmitted(event(tenantId, tradeId, Side.SELL, 40));

        verify(positionStore).applyAndEvaluate(tenantId, "OIL", tradeId, -40L, 10_000L);
    }

    @Test
    void recordsBreachAndPublishesRiskBreached() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        when(positionStore.applyAndEvaluate(any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new PositionUpdate(11_000, true, false));
        when(breachRecorder.record(any(), any(), any(), anyLong(), anyLong())).thenReturn(true);

        consumer.onTradeSubmitted(event(tenantId, tradeId, Side.BUY, 11_000));

        verify(breachRecorder).record(tenantId, tradeId, "OIL", 11_000L, 10_000L);
        verify(kafkaTemplate).send(eq("risk.breached"), eq(tenantId.toString()), anyString());
    }

    @Test
    void skipsAlreadyAppliedTrade() throws Exception {
        when(positionStore.applyAndEvaluate(any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new PositionUpdate(100, false, true));   // alreadyApplied

        consumer.onTradeSubmitted(event(UUID.randomUUID(), UUID.randomUUID(), Side.BUY, 100));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(breachRecorder, never()).record(any(), any(), any(), anyLong(), anyLong());
    }

    private String event(UUID tenantId, UUID tradeId, Side side, long quantity) throws Exception {
        return objectMapper.writeValueAsString(
                TradeSubmittedEvent.of(tenantId, tradeId, "OIL", side, quantity, new BigDecimal("1.0")));
    }
}
