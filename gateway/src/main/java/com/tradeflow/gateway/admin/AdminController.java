package com.tradeflow.gateway.admin;

import com.tradeflow.gateway.auth.InvalidCredentialsException;
import com.tradeflow.gateway.tenant.Tenant;
import com.tradeflow.gateway.tenant.TenantService;
import com.tradeflow.gateway.user.User;
import com.tradeflow.gateway.user.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Platform-admin endpoints (spec §10.2). These are outside tenant/JWT scope and are guarded by a
 * platform-admin API key header, not a JWT — so {@code /admin/**} is permitted in the security chain
 * and authorized here instead.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final String ADMIN_KEY_HEADER = "X-Platform-Admin-Key";

    private final String platformAdminKey;
    private final TenantService tenantService;
    private final UserService userService;

    public AdminController(AdminProperties adminProperties, TenantService tenantService, UserService userService) {
        this.platformAdminKey = adminProperties.apiKey();
        this.tenantService = tenantService;
        this.userService = userService;
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
                                       @Valid @RequestBody CreateTenantRequest request) {
        requirePlatformAdmin(adminKey);
        Tenant tenant = tenantService.onboard(request.name(), request.riskMode());
        return TenantResponse.from(tenant);
    }

    @PostMapping("/tenants/{tenantId}/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@RequestHeader(value = ADMIN_KEY_HEADER, required = false) String adminKey,
                                   @PathVariable UUID tenantId,
                                   @Valid @RequestBody CreateUserRequest request) {
        requirePlatformAdmin(adminKey);
        User user = userService.create(tenantId, request.email(), request.password(), request.role());
        return UserResponse.from(user);
    }

    private void requirePlatformAdmin(String providedKey) {
        if (providedKey == null || !platformAdminKey.equals(providedKey)) {
            throw new InvalidCredentialsException();
        }
    }
}
