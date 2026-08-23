package com.tradeflow.alert;

import com.tradeflow.common.event.RiskBreachedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists breach alerts (FR-ALERT-04). */
@Service
public class AlertService {

    private final AlertRepository alertRepository;

    public AlertService(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Transactional
    public Alert recordBreachAlert(RiskBreachedEvent event, String payloadJson) {
        String message = "Position limit breached on " + event.instrumentSymbol()
                + ": net " + event.netPosition() + " exceeds limit " + event.positionLimit();
        Alert alert = Alert.of(event.tenantId(), Alert.TYPE_RISK_BREACH, message, payloadJson);
        return alertRepository.save(alert);
    }
}
