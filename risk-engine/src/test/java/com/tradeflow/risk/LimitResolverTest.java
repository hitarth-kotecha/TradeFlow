package com.tradeflow.risk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LimitResolverTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @InjectMocks
    private LimitResolver limitResolver;

    @Test
    void returnsConfiguredLimit() {
        UUID tenantId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(tenantId.toString()), eq("OIL")))
                .thenReturn(10_000L);

        assertThat(limitResolver.limitFor(tenantId, "OIL")).isEqualTo(10_000L);
    }

    @Test
    void failsOpenWhenInstrumentMissing() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(limitResolver.limitFor(UUID.randomUUID(), "NOPE")).isEqualTo(Long.MAX_VALUE);
    }
}
