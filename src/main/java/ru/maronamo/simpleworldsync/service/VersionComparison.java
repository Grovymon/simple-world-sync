package ru.maronamo.simpleworldsync.service;

import ru.maronamo.simpleworldsync.metadata.WorldMetadata;

import java.util.Optional;

public record VersionComparison(
        String worldFolderName,
        Optional<WorldMetadata> localMetadata,
        Optional<WorldMetadata> remoteMetadata,
        VersionState state,
        String message
) {
}
