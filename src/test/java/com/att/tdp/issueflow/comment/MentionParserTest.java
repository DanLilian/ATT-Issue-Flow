package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for the mention parser. Covers regex correctness, case
 * insensitivity, edge cases (emails, duplicates, no mentions), and
 * the user-resolution path against a real repository.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MentionParser.class, JpaConfig.class})
class MentionParserTest {

    @Autowired MentionParser parser;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void seedUsers() {
        userRepository.save(new User("alice", "alice@x.com", "h", "Alice", UserRole.DEVELOPER));
        userRepository.save(new User("bob", "bob@x.com", "h", "Bob", UserRole.DEVELOPER));
    }

    // ─── Regex extraction (no DB) ───────────────────────────────────────

    @Test
    void extractUsernames_atStartOfString() {
        assertThat(parser.extractUsernames("@alice hello"))
                .containsExactly("alice");
    }

    @Test
    void extractUsernames_afterWhitespace() {
        assertThat(parser.extractUsernames("hello @alice"))
                .containsExactly("alice");
    }

    @Test
    void extractUsernames_multipleDistinct() {
        assertThat(parser.extractUsernames("@alice and @bob"))
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void extractUsernames_duplicatesCollapsed() {
        assertThat(parser.extractUsernames("@alice @alice @alice"))
                .containsExactly("alice");
    }

    @Test
    void extractUsernames_caseInsensitive_lowercased() {
        assertThat(parser.extractUsernames("@Alice @ALICE @alice"))
                .containsExactly("alice");
    }

    @Test
    void extractUsernames_excludesEmailsAndCodeIdentifiers() {
        // 'l@x.com' — char before @ is 'l' (word char), no match.
        assertThat(parser.extractUsernames("send to alice@x.com or arr[i]"))
                .isEmpty();
    }

    @Test
    void extractUsernames_emptyOrNullContent() {
        assertThat(parser.extractUsernames("")).isEmpty();
        assertThat(parser.extractUsernames(null)).isEmpty();
        assertThat(parser.extractUsernames("   ")).isEmpty();
    }

    @Test
    void extractUsernames_punctuationAfterMention() {
        // The capture is greedy on [A-Za-z0-9_.-] so trailing apostrophes,
        // commas, exclamations are NOT included in the captured username.
        assertThat(parser.extractUsernames("Hey @alice, @bob! and @alice's idea"))
                .containsExactlyInAnyOrder("alice", "bob");
    }

    // ─── Resolution against the DB ──────────────────────────────────────

    @Test
    void resolveMentions_returnsExistingUsers_silentlyDropsUnknown() {
        Set<User> mentions = parser.resolveMentions("Hi @alice and @ghost");

        Set<String> usernames = mentions.stream()
                .map(User::getUsername)
                .collect(Collectors.toSet());

        assertThat(usernames).containsExactly("alice");
    }

    @Test
    void resolveMentions_caseInsensitive_resolvesCorrectly() {
        Set<User> mentions = parser.resolveMentions("Hi @ALICE and @Bob");

        Set<String> usernames = mentions.stream()
                .map(User::getUsername)
                .collect(Collectors.toSet());

        assertThat(usernames).containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void resolveMentions_noMentions_returnsEmpty() {
        assertThat(parser.resolveMentions("plain comment, no mentions")).isEmpty();
    }
}