package com.playstudio.bridgemod.bot;

/**
 * Configuration for advanced PvP combat techniques.
 * Passed to CombatController.startAttack() from WebSocket params.
 * All fields have safe defaults (= current behavior).
 */
public class CombatConfig {

    public enum AttackMode {
        NORMAL,     // walk + attack when cooldown ready (default)
        CRIT,       // jump + fall + attack (1.5x damage, no sprint knockback)
        WTAP        // sprint reset between attacks (extra knockback each hit)
    }

    public enum StrafeMode {
        NONE,           // walk straight at target (default)
        CIRCLE,         // consistent strafe direction, periodic switch
        RANDOM,         // random strafe direction switches
        INTELLIGENT     // react to incoming hits + periodic random
    }

    // Attack technique (crit and wtap are mutually exclusive)
    public AttackMode attackMode = AttackMode.NORMAL;

    // Strafe mode during melee (compatible with all attack modes)
    public StrafeMode strafeMode = StrafeMode.NONE;

    // Shield handling
    public boolean shieldBreaking = false;   // auto-switch to axe vs blocking targets
    public boolean autoShield = false;       // raise own shield between attacks

    // Strafe tuning
    public int strafeChangeIntervalTicks = 40;  // ticks between strafe direction changes
    public float strafeIntensity = 0.8f;        // 0.0-1.0, mapped to strafe input

    // Crit tuning
    public int critJumpLeadTicks = 6;  // ticks before cooldown ready to initiate jump
}
