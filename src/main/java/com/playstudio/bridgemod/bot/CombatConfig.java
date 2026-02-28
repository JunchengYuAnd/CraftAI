package com.playstudio.bridgemod.bot;

/**
 * Configuration for advanced PvP combat techniques.
 * Passed to CombatController.startAttack() from WebSocket params.
 * All fields have safe defaults (= current behavior).
 *
 * Movement uses Artificial Potential Fields exclusively.
 */
public class CombatConfig {

    public enum AttackMode {
        NORMAL,     // walk + attack when cooldown ready (default)
        CRIT,       // jump + fall + attack (1.5x damage, no sprint knockback)
        WTAP,       // sprint reset between attacks (extra knockback each hit)
        STAP        // backward tap after hit (sprint reset + spacing)
    }

    public enum KBCancelMode {
        NONE,       // no knockback cancellation (default)
        JUMP,       // sprint + jump forward on hit to counter KB
        SHIFT       // sneak for N ticks on hit to reduce KB
    }

    // Attack technique
    public AttackMode attackMode = AttackMode.NORMAL;

    // Shield handling
    public boolean shieldBreaking = false;   // auto-switch to axe vs blocking targets
    public boolean autoShield = false;       // raise own shield between attacks

    // Crit tuning
    public int critJumpLeadTicks = 6;  // ticks before cooldown ready to initiate jump

    // KB Cancel
    public KBCancelMode kbCancelMode = KBCancelMode.NONE;
    public int kbCancelShiftTicks = 5;  // ticks to sneak in SHIFT mode

    // Reactionary Crit: opportunistic crit when knocked airborne
    public boolean reactionaryCrit = false;

    // S-tap tuning
    public int stapBackTicks = 4;  // ticks to walk backward after hitting

    // Multi-target threat awareness
    public boolean threatAwareness = false;     // scan nearby threats while fighting primary target
    public double threatScanRadius = 8.0;       // radius to scan for secondary threats

    // Mob learning: observe mob behavior and auto-adapt combat parameters
    public boolean mobLearning = false;         // enable observation-based parameter adaptation

    // Potential field movement
    public double optimalMeleeDistance = 3.0;     // ring field center distance from target (= ATTACK_RANGE)
    public double tangentStrength = 0.4;          // orbital strafing force strength
    public double threatRepulsionK = 3.0;         // threat push strength coefficient
    public double threatRepulsionRange = 6.0;     // ignore threats beyond this range
    public int orbitFlipIntervalTicks = 40;       // ticks between orbit direction flips
}
