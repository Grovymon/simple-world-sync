package ru.maronamo.simpleworldsync.service;

public record VersionSummary(
        int totalWorlds,
        int remoteNewerCount,
        int localNewerCount,
        int sameCount,
        int unknownCount
) {
}
