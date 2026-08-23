package com.tradeflow.alert;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    Page<Alert> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Alert> findByTenantIdAndReadAtIsNull(UUID tenantId, Pageable pageable);
}
