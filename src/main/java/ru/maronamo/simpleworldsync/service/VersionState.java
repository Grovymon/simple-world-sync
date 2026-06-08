package ru.maronamo.simpleworldsync.service;

public enum VersionState {
    SAME,
    REMOTE_NEWER,
    LOCAL_NEWER,
    NO_REMOTE,
    UNKNOWN,
    NOT_CONFIGURED
}
