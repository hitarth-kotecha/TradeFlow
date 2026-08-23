package com.tradeflow.gateway.summary;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Composite account view fetched concurrently (§10.9, FR-SUM-01). */
@RestController
@RequestMapping("/api/v1/summary")
public class SummaryController {

    private final SummaryService summaryService;

    public SummaryController(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping
    public AccountSummary summary() {
        return summaryService.summary();
    }
}
