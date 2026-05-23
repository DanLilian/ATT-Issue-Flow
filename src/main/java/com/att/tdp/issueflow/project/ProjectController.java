package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.project.dto.CreateProjectRequest;
import com.att.tdp.issueflow.project.dto.ProjectResponse;
import com.att.tdp.issueflow.project.dto.UpdateProjectRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for /projects.
 *
 * Standard CRUD is open to any authenticated user. The deleted-listing
 * and restore endpoints are ADMIN-only, gated by @PreAuthorize — when a
 * DEVELOPER hits them, Spring throws AccessDeniedException which the
 * AccessDeniedHandler from Phase 5 maps to 403 with the ApiError body.
 *
 * Endpoint shapes follow the README contract: POST /projects returns 200
 * (not 201), PATCH returns 200 with empty body. Soft delete is the only
 * delete path exposed — no hard-delete endpoint.
 */
@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public List<ProjectResponse> getAll() {
        return projectService.findAll();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getById(@PathVariable Long projectId) {
        return projectService.findById(projectId);
    }

    @PostMapping
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest req) {
        return projectService.create(req);
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<Void> update(@PathVariable Long projectId,
                                       @Valid @RequestBody UpdateProjectRequest req) {
        projectService.update(projectId, req);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> softDelete(@PathVariable Long projectId) {
        projectService.softDelete(projectId);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/deleted")
    public List<ProjectResponse> getDeleted() {
        return projectService.findAllDeleted();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{projectId}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long projectId) {
        projectService.restore(projectId);
        return ResponseEntity.ok().build();
    }
}