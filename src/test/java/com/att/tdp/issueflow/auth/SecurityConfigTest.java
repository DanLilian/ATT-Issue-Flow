package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.common.error.GlobalExceptionHandler;
import com.att.tdp.issueflow.config.TestSecurityConfig;
import com.att.tdp.issueflow.user.UserController;
import com.att.tdp.issueflow.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the slice-level test security configuration. Production
 * security is verified end-to-end in AuthIntegrationTest (Phase 5c).
 */
@WebMvcTest(
    controllers = UserController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
            com.att.tdp.issueflow.config.SecurityConfig.class,
            com.att.tdp.issueflow.config.SecurityProblemHandlers.class,
            com.att.tdp.issueflow.auth.jwt.JwtAuthenticationFilter.class
        }
    )
)
@Import({GlobalExceptionHandler.class, TestSecurityConfig.class})
class SecurityConfigTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean UserService userService;

    @Test
    void protectedEndpoint_returns401_whenNoAuth() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.path").value("/users"));
    }

    @Test
    void protectedEndpoint_returns200_whenAuthenticated() throws Exception {
        mockMvc.perform(get("/users").with(user("alice").roles("DEVELOPER")))
                .andExpect(status().isOk());
    }
}