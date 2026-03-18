package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Company;
import com.cxalloy.integration.service.CompanyService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {
    private final CompanyService companyService;
    public CompanyController(CompanyService companyService) { this.companyService = companyService; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Company>>> getAll(@RequestParam(required=false) String projectId) {
        List<Company> list = projectId != null ? companyService.getByProject(projectId) : companyService.getAll();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(120, TimeUnit.SECONDS).mustRevalidate())
                .body(ApiResponse.success(list, list.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Company>> getById(@PathVariable Long id) {
        return companyService.getById(id).map(c -> ResponseEntity.ok(ApiResponse.success(c)))
               .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncResult>> sync(@RequestParam(required=false) String projectId) {
        try { return ResponseEntity.ok(ApiResponse.success(companyService.syncCompanies(projectId), "Synced")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }
}
