package com.tradeflow.gateway.report;

import com.tradeflow.common.context.TenantContext;
import com.tradeflow.reporting.PnlSnapshotRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** P&L reports (§10.8): read a date's snapshot; trigger the batch on demand (ADMIN, FR-PNL-04/01). */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final JobLauncher jobLauncher;
    private final Job pnlSnapshotJob;
    private final PnlSnapshotRepository pnlSnapshotRepository;

    public ReportController(JobLauncher jobLauncher, Job pnlSnapshotJob,
                            PnlSnapshotRepository pnlSnapshotRepository) {
        this.jobLauncher = jobLauncher;
        this.pnlSnapshotJob = pnlSnapshotJob;
        this.pnlSnapshotRepository = pnlSnapshotRepository;
    }

    @PostMapping("/pnl/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    public void run(@RequestParam String date) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("snapshotDate", date)
                .addLong("runAt", System.currentTimeMillis())   // new instance each run; the UPSERT dedupes
                .toJobParameters();
        jobLauncher.run(pnlSnapshotJob, params);
    }

    @GetMapping("/pnl")
    public List<PnlReportResponse> pnl(@RequestParam String date) {
        return pnlSnapshotRepository
                .findByTenantIdAndSnapshotDate(TenantContext.tenantId(), LocalDate.parse(date))
                .stream()
                .map(PnlReportResponse::from)
                .toList();
    }
}
