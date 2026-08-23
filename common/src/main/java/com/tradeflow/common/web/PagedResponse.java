package com.tradeflow.common.web;

import java.util.List;

/** The standard pagination envelope for all list endpoints (spec §10.10). */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
