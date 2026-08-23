/**
 * Alert service: consumes {@code risk.breached} and pushes alerts to the affected tenant's
 * subscribers over WebSocket/STOMP (FR-ALERT-*), persisting them for later fetch.
 */
package com.tradeflow.alert;
