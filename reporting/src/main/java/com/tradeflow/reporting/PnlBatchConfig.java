package com.tradeflow.reporting;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;

/**
 * The P&L snapshot job (FR-PNL-01): a single tasklet step that computes and upserts snapshots for the
 * {@code snapshotDate} job parameter. Restartability + run metadata come from the Spring Batch
 * JobRepository (Flyway-owned tables); the per-instrument concurrency lives inside the service.
 */
@Configuration
public class PnlBatchConfig {

    @Bean
    public Job pnlSnapshotJob(JobRepository jobRepository, Step pnlComputeStep) {
        return new JobBuilder("pnlSnapshotJob", jobRepository)
                .start(pnlComputeStep)
                .build();
    }

    @Bean
    public Step pnlComputeStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                               PnlSnapshotService pnlSnapshotService) {
        return new StepBuilder("pnlComputeStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String date = (String) chunkContext.getStepContext().getJobParameters().get("snapshotDate");
                    pnlSnapshotService.computeForDate(LocalDate.parse(date));
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }
}
