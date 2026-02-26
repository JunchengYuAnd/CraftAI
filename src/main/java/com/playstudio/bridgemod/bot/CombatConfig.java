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
        WTAP,       // sprint reset between attacks (extra knockback each hit)
        STAP        // backward tap after hit (sprint reset + spacing)
    }

    public enum StrafeMode {
        NONE,           // walk straight at target (default)
        CIRCLE,         // consistent strafe direction, periodic switch
        RANDOM,         // random strafe direction switches
        INTELLIGENT     // react to incoming hits + periodic random
    }

    public enum KBCancelMode {
        NONE,       // no knockback cancellation (default)
        JUMP,       // sprint + jump forward on hit to counter KB
        SHIFT       // sneak for N ticks on hit to reduce KB
    }

    // Attack technique
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

    // KB Cancel
    public KBCancelMode kbCancelMode = KBCancelMode.NONE;
    public int kbCancelShiftTicks = 5;  // ticks to sneak in SHIFT mode

    // Reactionary Crit: opportunistic crit when knocked airborne
    public boolean reactionaryCrit = false;

    // S-tap tuning
    public int stapBackTicks = 4;  // ticks to walk backward after hitting

    // Distance management
    public float tooCloseRange = 0.0f;    // 0 = disabled; stop forward when closer than this
    public int backoffOnHitTicks = 0;     // 0 = disabled; stop approaching for N ticks after hit
}
