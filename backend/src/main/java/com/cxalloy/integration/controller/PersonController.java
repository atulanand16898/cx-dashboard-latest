package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Person;
import com.cxalloy.integration.service.PersonService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/persons")
public class PersonController {
    private final PersonService personService;
    public PersonController(PersonService personService) { this.personService = personService; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Person>>> getAll(@RequestParam(required=false) String projectId) {
        List<Person> list = projectId != null ? personService.getByProject(projectId) : personService.getAll();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(120, TimeUnit.SECONDS).mustRevalidate())
                .body(ApiResponse.success(list, list.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Person>> getById(@PathVariable Long id) {
        return personService.getById(id).map(p -> ResponseEntity.ok(ApiResponse.success(p)))
               .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncResult>> sync(@RequestParam(required=false) String projectId) {
        try { return ResponseEntity.ok(ApiResponse.success(personService.syncPersons(projectId), "Synced")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }
}
