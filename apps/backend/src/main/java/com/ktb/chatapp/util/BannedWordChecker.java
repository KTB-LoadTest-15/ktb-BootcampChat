package com.ktb.chatapp.util;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final Set<String> bannedWords;
    private final TrieNode root = new TrieNode();

    public BannedWordChecker(Set<String> bannedWords) {
        this.bannedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(this.bannedWords, "Banned words set must not be empty");
        buildMatcher();
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        TrieNode current = root;

        for (int i = 0; i < normalizedMessage.length(); i++) {
            char character = normalizedMessage.charAt(i);

            while (current != root && !current.children.containsKey(character)) {
                current = current.failure;
            }

            current = current.children.getOrDefault(character, root);
            if (current.matches) {
                return true;
            }
        }

        return false;
    }

    private void buildMatcher() {
        for (String word : bannedWords) {
            TrieNode current = root;
            for (int i = 0; i < word.length(); i++) {
                current = current.children.computeIfAbsent(word.charAt(i), ignored -> new TrieNode());
            }
            current.matches = true;
        }

        root.failure = root;
        Queue<TrieNode> queue = new ArrayDeque<>();
        for (TrieNode child : root.children.values()) {
            child.failure = root;
            queue.add(child);
        }

        while (!queue.isEmpty()) {
            TrieNode parent = queue.remove();
            for (Map.Entry<Character, TrieNode> transition : parent.children.entrySet()) {
                char character = transition.getKey();
                TrieNode child = transition.getValue();
                TrieNode fallback = parent.failure;

                while (fallback != root && !fallback.children.containsKey(character)) {
                    fallback = fallback.failure;
                }

                TrieNode suffix = fallback.children.get(character);
                child.failure = suffix != null ? suffix : root;
                child.matches = child.matches || child.failure.matches;
                queue.add(child);
            }
        }
    }

    private static final class TrieNode {
        private final Map<Character, TrieNode> children = new HashMap<>();
        private TrieNode failure;
        private boolean matches;
    }
}
