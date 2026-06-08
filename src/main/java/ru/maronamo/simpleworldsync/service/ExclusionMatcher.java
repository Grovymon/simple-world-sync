package ru.maronamo.simpleworldsync.service;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

public final class ExclusionMatcher {
    private final List<Rule> rules;

    public ExclusionMatcher(List<String> patterns) {
        this.rules = patterns.stream()
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .map(Rule::new)
                .toList();
    }

    public boolean isExcluded(Path relativePath) {
        String normalized = relativePath.toString().replace('\\', '/');
        return rules.stream().anyMatch(rule -> rule.matches(normalized));
    }

    private record Rule(String glob, Pattern pattern) {
        private Rule(String glob) {
            this(glob.replace('\\', '/'), Pattern.compile(toRegex(glob.replace('\\', '/'))));
        }

        private boolean matches(String relativePath) {
            if (!glob.contains("/") && relativePath.endsWith("/" + glob)) {
                return true;
            }

            return pattern.matcher(relativePath).matches();
        }

        private static String toRegex(String glob) {
            StringBuilder builder = new StringBuilder("^");

            for (int index = 0; index < glob.length(); index++) {
                char current = glob.charAt(index);
                if (current == '*') {
                    boolean doubleStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                    if (doubleStar) {
                        builder.append(".*");
                        index++;
                    } else {
                        builder.append("[^/]*");
                    }
                } else if (current == '?') {
                    builder.append("[^/]");
                } else {
                    if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                        builder.append('\\');
                    }
                    builder.append(current);
                }
            }

            builder.append('$');
            return builder.toString();
        }
    }
}
