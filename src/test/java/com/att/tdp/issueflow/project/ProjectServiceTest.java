package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.project.dto.CreateProjectRequest;
import com.att.tdp.issueflow.project.dto.ProjectResponse;
import com.att.tdp.issueflow.project.dto.UpdateProjectRequest;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice test for ProjectService with a real (H2) repository.
 * Verifies soft-delete behavior, owner validation, restore semantics
 * (including the 'not deleted' edge case), and the @SQLRestriction
 * filtering on standard reads.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProjectService.class, JpaConfig.class})
class ProjectServiceTest {

    @Autowired ProjectService projectService;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;

    @PersistenceContext
    EntityManager entityManager;

    private Long ownerId;

    @BeforeEach
    void seedOwner() {
        User owner = userRepository.save(new User(
                "owner", "owner@x.com", "irrelevant-hash", "Owner", UserRole.DEVELOPER));
        ownerId = owner.getId();
    }

    private CreateProjectRequest validCreate(String name) {
        return new CreateProjectRequest(name, "description", ownerId);
    }

    @Test
    void create_persistsProject() {
        ProjectResponse created = projectService.create(validCreate("Apollo"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Apollo");
        assertThat(created.ownerId()).isEqualTo(ownerId);
    }

    @Test
    void create_throwsNotFound_whenOwnerDoesNotExist() {
        CreateProjectRequest req = new CreateProjectRequest("X", "y", 99_999L);

        assertThatThrownBy(() -> projectService.create(req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User with id 99999 not found");
    }

    @Test
    void findAll_excludesSoftDeleted() {
        ProjectResponse keep = projectService.create(validCreate("Keep"));
        ProjectResponse drop = projectService.create(validCreate("Drop"));

        projectService.softDelete(drop.id());
        entityManager.flush();
        entityManager.clear();

        List<ProjectResponse> all = projectService.findAll();
        assertThat(all).extracting(ProjectResponse::name).containsExactly("Keep");
        assertThat(all).extracting(ProjectResponse::id).doesNotContain(drop.id());
    }

    @Test
    void findById_returns404_forSoftDeletedProject() {
        ProjectResponse created = projectService.create(validCreate("Apollo"));
        projectService.softDelete(created.id());

        // Flush and clear so the next read goes to SQL (where @SQLRestriction
        // fires), not Hibernate's first-level cache (where it doesn't).
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> projectService.findById(created.id()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void update_partialUpdate_appliesOnlyProvidedFields() {
        ProjectResponse created = projectService.create(validCreate("Apollo"));

        projectService.update(created.id(), new UpdateProjectRequest("Apollo 11", null));

        ProjectResponse updated = projectService.findById(created.id());
        assertThat(updated.name()).isEqualTo("Apollo 11");
        assertThat(updated.description()).isEqualTo("description"); // unchanged
    }

    @Test
    void softDelete_marksAsDeleted_butRowStillExists() {
        ProjectResponse created = projectService.create(validCreate("Apollo"));

        projectService.softDelete(created.id());

        // Force SQL so @SQLRestriction filters the standard read.
        entityManager.flush();
        entityManager.clear();

        // Standard findById hidden by @SQLRestriction.
        assertThat(projectRepository.findById(created.id())).isEmpty();
        // Row is still in the DB, visible via the native query.
        assertThat(projectRepository.findDeletedById(created.id())).isPresent();
    }

    @Test
    void findAllDeleted_returnsOnlySoftDeleted() {
        ProjectResponse active  = projectService.create(validCreate("Active"));
        ProjectResponse deleted = projectService.create(validCreate("Deleted"));

        projectService.softDelete(deleted.id());
        entityManager.flush();
        entityManager.clear();

        List<ProjectResponse> result = projectService.findAllDeleted();
        assertThat(result).extracting(ProjectResponse::id).containsExactly(deleted.id());
        assertThat(result).extracting(ProjectResponse::id).doesNotContain(active.id());
    }

    @Test
    void restore_undeletesProject_andItReappearsInStandardLists() {
        ProjectResponse created = projectService.create(validCreate("Apollo"));
        projectService.softDelete(created.id());
        entityManager.flush();
        entityManager.clear();

        projectService.restore(created.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(projectService.findById(created.id()).name()).isEqualTo("Apollo");
        assertThat(projectService.findAll()).extracting(ProjectResponse::id)
                .containsExactly(created.id());
    }

    @Test
    void restore_throwsConflict_whenProjectIsNotDeleted() {
        ProjectResponse created = projectService.create(validCreate("Apollo"));

        assertThatThrownBy(() -> projectService.restore(created.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not deleted");
    }

    @Test
    void restore_throwsNotFound_whenProjectDoesNotExist() {
        assertThatThrownBy(() -> projectService.restore(99_999L))
                .isInstanceOf(NotFoundException.class);
    }
}