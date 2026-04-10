package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.FacilityGridEndpointTestRequest;
import com.cxalloy.integration.dto.FacilityGridTokenTestRequest;
import com.cxalloy.integration.dto.LeadCaptureRequest;
import com.cxalloy.integration.dto.LoginRequest;
import com.cxalloy.integration.dto.RefreshTokenRequest;
import com.cxalloy.integration.dto.TokenResponse;
import com.cxalloy.integration.model.DataProvider;
import com.cxalloy.integration.security.JwtTokenProvider;
import com.cxalloy.integration.service.AuthService;
import com.cxalloy.integration.service.FacilityGridAuthService;
import com.cxalloy.integration.service.LeadCaptureService;
import com.cxalloy.integration.service.ProjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final FacilityGridAuthService facilityGridAuthService;
    private final LeadCaptureService leadCaptureService;
    private final ProjectAccessService projectAccessService;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(AuthService authService,
                          FacilityGridAuthService facilityGridAuthService,
                          LeadCaptureService leadCaptureService,
                          ProjectAccessService projectAccessService,
                          JwtTokenProvider jwtTokenProvider) {
        this.authService = authService;
        this.facilityGridAuthService = facilityGridAuthService;
        this.leadCaptureService = leadCaptureService;
        this.projectAccessService = projectAccessService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * POST /api/auth/login
     * Body: { "username": "admin", "password": "admin123" }
     * Returns: access token + refresh token
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        logger.info("POST /api/auth/login - user: {} provider: {}", request.getUsername(), request.getProvider());
        try {
            TokenResponse tokens = authService.login(request);
            return ResponseEntity.ok(ApiResponse.success(tokens, "Login successful"));
        } catch (BadCredentialsException e) {
            logger.warn("Login failed for user '{}': {}", request.getUsername(), e.getMessage());
            String message = e.getMessage();
            if (message != null && message.startsWith("Use the ")) {
                return ResponseEntity.status(401).body(ApiResponse.error(message));
            }
            return ResponseEntity.status(401).body(ApiResponse.error("Invalid username or password"));
        } catch (Exception e) {
            logger.error("Login error: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponse.error("Login failed: " + e.getMessage()));
        }
    }

    @PostMapping("/lead")
    public ResponseEntity<ApiResponse<Map<String, Object>>> captureLead(
            @Valid @RequestBody LeadCaptureRequest request,
            HttpServletRequest servletRequest) {
        logger.info("POST /api/auth/lead - email: {}", request.getEmail());
        try {
            Map<String, Object> result = leadCaptureService.captureLead(request, servletRequest);
            boolean notificationSent = Boolean.TRUE.equals(result.get("notificationSent"));
            String message = notificationSent
                    ? "Lead captured successfully. Notification email sent."
                    : "Lead captured successfully. Notification email is pending mail configuration.";
            return ResponseEntity.ok(ApiResponse.success(result, message));
        } catch (Exception e) {
            logger.error("Lead capture error: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponse.error("Lead capture failed: " + e.getMessage()));
        }
    }

    @PostMapping("/facility-grid/test-token")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testFacilityGridToken(
            @Valid @RequestBody FacilityGridTokenTestRequest request) {
        try {
            Map<String, Object> result = facilityGridAuthService.testClientCredentials(
                    request.getClientId(),
                    request.getClientSecret()
            );
            return ResponseEntity.ok(ApiResponse.success(result, "Facility Grid token request succeeded"));
        } catch (Exception e) {
            logger.error("Facility Grid token test failed: {}", e.getMessage());
            return ResponseEntity.status(400).body(ApiResponse.error("Facility Grid token test failed: " + e.getMessage()));
        }
    }

    @PostMapping("/facility-grid/test-endpoint")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testFacilityGridEndpoint(
            @Valid @RequestBody FacilityGridEndpointTestRequest request) {
        try {
            Map<String, Object> result = facilityGridAuthService.testEndpoint(
                    request.getClientId(),
                    request.getClientSecret(),
                    request.getPath()
            );
            return ResponseEntity.ok(ApiResponse.success(result, "Facility Grid endpoint request succeeded"));
        } catch (Exception e) {
            logger.error("Facility Grid endpoint test failed: {}", e.getMessage());
            return ResponseEntity.status(400).body(ApiResponse.error("Facility Grid endpoint test failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/auth/refresh
     * Body: { "refreshToken": "<your-refresh-token>" }
     * Returns: new access token + new refresh token (old refresh token is rotated/invalidated)
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        logger.info("POST /api/auth/refresh");
        try {
            TokenResponse tokens = authService.refreshToken(request);
            return ResponseEntity.ok(ApiResponse.success(tokens, "Token refreshed successfully"));
        } catch (BadCredentialsException e) {
            logger.warn("Refresh failed: {}", e.getMessage());
            return ResponseEntity.status(401).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("Refresh error: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponse.error("Token refresh failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/auth/logout
     * Header: Authorization: Bearer <access-token>
     * Body (optional): { "refreshToken": "<refresh-token>" }
     * Blacklists both tokens so they can no longer be used
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Map<String, String>>> logout(
            HttpServletRequest request,
            @RequestBody(required = false) RefreshTokenRequest body) {

        logger.info("POST /api/auth/logout");

        String accessToken = extractBearerToken(request);
        String refreshToken = (body != null) ? body.getRefreshToken() : null;

        authService.logout(accessToken, refreshToken);

        return ResponseEntity.ok(ApiResponse.success(
            Map.of("message", "Logged out successfully. Tokens have been invalidated."),
            "Logout successful"
        ));
    }

    /**
     * GET /api/auth/me
     * Header: Authorization: Bearer <access-token>
     * Returns info about the currently authenticated user
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCurrentUser(HttpServletRequest request) {
        String accessToken = extractBearerToken(request);
        if (accessToken == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("No token provided"));
        }
        try {
            // Get current user from security context
            org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();

            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body(ApiResponse.error("Not authenticated"));
            }

            DataProvider provider = jwtTokenProvider.extractProvider(accessToken);
            Map<String, Object> userInfo = Map.of(
                "username", auth.getName(),
                "provider", provider.getKey(),
                "providerLabel", provider.getLabel(),
                "roles", auth.getAuthorities().toString(),
                "authenticated", true,
                "isAdmin", projectAccessService.isAdmin(auth.getName()),
                "accessibleProjectIds", projectAccessService.getAccessibleProjectIdsForUser(auth.getName())
            );
            return ResponseEntity.ok(ApiResponse.success(userInfo));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(ApiResponse.error("Could not retrieve user info"));
        }
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
