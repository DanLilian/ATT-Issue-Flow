package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts @mentions from comment content and resolves them to existing users.
 *
 * Regex: (?:^|\W)@([A-Za-z0-9_.-]+)
 * - (?:^|\W)            start of string OR a non-word character before the @
 *                       (so email@x.com does NOT match — char before @ is a word char)
 * - @                   literal at-sign
 * - ([A-Za-z0-9_.-]+)   capture group; matches our username allow-list from Phase 4
 *
 * Matching is case-insensitive per PDF 3.6 — usernames are lowercased before
 * the repository lookup, and UserRepository.findAllByUsernameIgnoreCaseIn
 * does the case-insensitive comparison server-side.
 *
 * Unknown usernames are silently ignored: parsing a comment that says
 * "@ghost" against a DB without a 'ghost' user produces an empty mention
 * set — no exception, no error to the caller.
 */
@Component
public class MentionParser {

    private static final Pattern MENTION_PATTERN =
            Pattern.compile("(?:^|\\W)@([A-Za-z0-9_.-]+)");

    private final UserRepository userRepository;

    public MentionParser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Extracts the distinct set of mentioned usernames from the content.
     * Case-insensitive: "@Alice" and "@alice" produce one entry, "alice".
     * Returns lowercase usernames for the repository lookup.
     */
    public Set<String> extractUsernames(String content) {
        if (content == null || content.isBlank()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        Matcher m = MENTION_PATTERN.matcher(content);
        while (m.find()) {
            names.add(m.group(1).toLowerCase());
        }
        return names;
    }

    /**
     * Parses content and returns the set of User entities corresponding
     * to mentioned usernames that actually exist. Unknown usernames are
     * silently dropped.
     *
     * Read-only: the call site (CommentService) decides what to do with
     * the resulting set.
     */
    @Transactional(readOnly = true)
    public Set<User> resolveMentions(String content) {
        Set<String> usernames = extractUsernames(content);
        if (usernames.isEmpty()) {
            return Set.of();
        }
        return userRepository.findAllByUsernameIgnoreCaseIn(usernames).stream()
                .collect(Collectors.toSet());
    }
}