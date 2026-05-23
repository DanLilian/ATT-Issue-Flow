package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.project.dto.CreateProjectRequest;
import com.att.tdp.issueflow.project.dto.ProjectResponse;
import com.att.tdp.issueflow.project.dto.UpdateProjectRequest;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business operations for {@link Project}.
 *
 * Read-only transactional default; mutating methods opt in explicitly.
 * Soft-delete and restore use entity domain methods (markDeleted /
 * restore); the underlying SQL is rewritten by @SQLDelete on the entity
 * so any repository.delete(...) call also performs a soft delete.
 *
 * Audit-log writes are intentionally absent — added in Phase 9.
 */
@Service
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public ProjectService(ProjectRepository projectRepository,
                          UserRepository userRepository) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    public List<ProjectResponse> findAll() {
        return projectRepository.findAll().stream()
                .map(ProjectResponse::from)
                .toList();
    }

    public ProjectResponse findById(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Project", id));
        return ProjectResponse.from(project);
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest req) {
        // Verify the owner exists before constructing the entity. Without
        // this the FK constraint catches it, but the error message is much
        // less clear ("data integrity violation").
        User owner = userRepository.findById(req.ownerId())
                .orElseThrow(() -> NotFoundException.of("User", req.ownerId()));

        Project project = new Project(req.name(), req.description(), owner);
        Project saved = projectRepository.save(project);

        // TODO Phase 9: auditService.record(PROJECT_CREATE, PROJECT, saved.getId(), ...);

        return ProjectResponse.from(saved);
    }

    @Transactional
    public void update(Long id, UpdateProjectRequest req) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Project", id));

        project.updateDetails(req.name(), req.description());

        // No explicit save() — managed entity, dirty checking on commit.

        // TODO Phase 9: auditService.record(PROJECT_UPDATE, PROJECT, id, ...);
    }

    @Transactional
    public void softDelete(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Project", id));

        // markDeleted() sets deleted_at on the entity. The @SQLRestriction
        // annotation will hide it from subsequent reads via the standard
        // repository methods.
        //
        // Alternative: projectRepository.delete(project) would also work
        // (@SQLDelete rewrites it to an UPDATE), but calling markDeleted()
        // explicitly makes the intent visible in the diff.
        project.markDeleted();

        // TODO Phase 9: auditService.record(PROJECT_DELETE, PROJECT, id, ...);
    }

    /**
     * Lists soft-deleted projects. ADMIN-only — enforced at the controller
     * via @PreAuthorize. Uses a native query in the repository that
     * bypasses @SQLRestriction.
     */
    public List<ProjectResponse> findAllDeleted() {
        return projectRepository.findAllDeleted().stream()
                .map(ProjectResponse::from)
                .toList();
    }

    /**
     * Restores a soft-deleted project. ADMIN-only.
     *
     * Fails with 409 if the project is not actually deleted — restoring
     * an active project is meaningless and almost certainly a client bug.
     */
    @Transactional
    public void restore(Long id) {
        // Use the deleted-finder to locate the soft-deleted row. The
        // standard findById would not see it (@SQLRestriction filter).
        Project project = projectRepository.findDeletedById(id)
                .orElseThrow(() -> {
                    // Distinguish "no such id" from "exists but not deleted"
                    // for a clearer error message.
                    if (projectRepository.findById(id).isPresent()) {
                        return new ConflictException(
                            "Project with id %d is not deleted".formatted(id));
                    }
                    return NotFoundException.of("Project", id);
                });

        project.restore();

        // TODO Phase 9: auditService.record(PROJECT_RESTORE, PROJECT, id, ...);
    }
}