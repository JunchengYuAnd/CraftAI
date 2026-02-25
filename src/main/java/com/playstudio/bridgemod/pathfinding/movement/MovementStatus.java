package com.playstudio.bridgemod.pathfinding.movement;

/**
 * Status of a movement's execution.
 * Ported from Baritone's MovementStatus enum.
 */
public enum MovementStatus {
    RUNNING(false),
    SUCCESS(true),
    UNREACHABLE(true);

    private final boolean complete;

    MovementStatus(boolean complete) {
        this.complete = complete;
    }

    public boolean isComplete() {
        return complete;
    }
}
