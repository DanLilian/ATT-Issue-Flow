package com.att.tdp.issueflow.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {
        GlobalExceptionHandler.class,
        GlobalExceptionHandlerTest.TestConfig.class
})
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @Autowired
    private GlobalExceptionHandler handler;

    @Autowired
    private TestController controller;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void notFoundException_returns404_withApiErrorShape() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("widget with id 99 not found"))
                .andExpect(jsonPath("$.path").value("/test/not-found"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void conflictException_returns409() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("rule violated"));
    }

    @Test
    void forbiddenAction_returns403() throws Exception {
        mockMvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void optimisticLock_mapsTo409() throws Exception {
        mockMvc.perform(get("/test/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Resource was modified by another user. Reload and retry."));
    }

    @Test
    void beanValidation_returns400_withFieldErrors() throws Exception {
        String invalidBody = """
                { "email": "not-an-email", "name": "" }
                """;
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        org.hamcrest.Matchers.containsInAnyOrder("email", "name")));
    }

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not even close to json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.startsWith("Malformed request body")));
    }

    @Test
    void unknownException_returns500() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
    }

    // ─── Test scaffolding ──────────────────────────────────────────────

    @TestConfiguration
    static class TestConfig {
        @Bean TestController testController() { return new TestController(); }
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/not-found")
        public void notFound() { throw NotFoundException.of("widget", 99); }

        @GetMapping("/conflict")
        public void conflict() { throw new ConflictException("rule violated"); }

        @GetMapping("/forbidden")
        public void forbidden() { throw new ForbiddenActionException("not allowed"); }

        @GetMapping("/lock")
        public void lock() { throw new OptimisticLockingFailureException("stale"); }

        @PostMapping("/validate")
        public void validate(@Valid @RequestBody TestPayload p) { /* no-op */ }

        @GetMapping("/boom")
        public void boom() { throw new RuntimeException("kaboom"); }
    }

    record TestPayload(
            @NotBlank String name,
            @Email String email
    ) {}
}