package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "users",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
        @UniqueConstraint(name = "uk_users_email",    columnNames = "email")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA requires no-arg ctor; not for callers
public class User extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 254)
    private String email;

    /** BCrypt hash of the user's password. Never returned via API. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserRole role;

    public User(String username, String email, String passwordHash, String fullName, UserRole role) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
    }

    /** Per the spec, only fullName and role are mutable via PATCH /users. */
    public void updateProfile(String fullName, UserRole role) {
        if (fullName != null) this.fullName = fullName;
        if (role != null) this.role = role;
    }

    /** Used by future password-reset flow; not exposed via REST yet. */
    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }
}