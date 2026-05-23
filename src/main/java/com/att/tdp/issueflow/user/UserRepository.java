package com.att.tdp.issueflow.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Used for login lookup and mention resolution. Case-insensitive per spec. */
    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmail(String email);

    /** Used by the mention parser to resolve many usernames in one round trip. */
    List<User> findAllByUsernameIgnoreCaseIn(Collection<String> usernames);

    /** Used by auto-assignment; ordered by createdAt for the tie-breaker. */
    List<User> findAllByRoleOrderByCreatedAtAsc(UserRole role);
}