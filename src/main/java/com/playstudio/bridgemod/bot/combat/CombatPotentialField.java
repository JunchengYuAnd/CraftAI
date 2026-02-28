package com.playstudio.bridgemod.bot.combat;

import com.playstudio.bridgemod.bot.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes a 2D movement force vector from an artificial potential field.
 *
 * Forces summed on the XZ plane:
 *   - Target attractive ring: pulls bot toward optimal melee distance
 *   - Target tangential: creates orbital strafing motion
 *   - Threat repulsion: inverse-square push away from nearby hostiles
 *   - Wall repulsion: pushes away from solid blocks
 *   - Cliff repulsion: pushes away from drop-offs
 *   - Hazard repulsion: strong push from lava/fire, moderate from water
 *
 * The resulting world-space force is converted to bot-relative (forward, strafe)
 * via yaw projection for direct use with setMovementInput().
 */
public class CombatPotentialField {

    // Sampling offsets for cardinal wall checks (dx, dz)
    private static final double[][] CARDINAL_OFFSETS = {
            { 0, -1}, // North
            { 0,  1}, // South
            {-1,  0}, // West
            { 1,  0}, // East
    };

    // Sampling offsets for 8-directional hazard checks
    private static final double[][] OCTAL_OFFSETS = {
            { 0, -1}, { 0,  1}, {-1,  0}, { 1,  0},
            {-1, -1}, {-1,  1}, { 1, -1}, { 1,  1},
    };

    /**
     * Force breakdown for debug logging — shows contribution of each force component.
     */
    public static class ForceBreakdown {
        public double dist, optimalDist, radialError, radialForce;
        public int blockingThreats;
        public double blockingDamp;
        public float tangentDir;
        public double tangentForce, tangentDampening;
        public double leftThreatScore, rightThreatScore;
        public final List<String> threatDetails = new ArrayList<>();
        public double threatFx, threatFz;
        public double wallFx, wallFz;
        public double cliffFx, cliffFz;
        public double hazardFx, hazardFz;
        public double totalFx, totalFz;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("dist=%.2f optDist=%.2f radErr=%.2f radF=%.3f",
                    dist, optimalDist, radialError, radialForce));
            if (blockingThreats > 0)
                sb.append(String.format(" blockDamp=%.1f(%d)", blockingDamp, blockingThreats));
            sb.append(String.format(" | tangent: dir=%s str=%.3f damp=%.1f Lscore=%.2f Rscore=%.2f",
                    tangentDir > 0 ? "L" : "R", tangentForce, tangentDampening,
                    leftThreatScore, rightThreatScore));
            if (!threatDetails.isEmpty()) {
                sb.append(" | threats: ");
                sb.append(String.join("; ", threatDetails));
                sb.append(String.format(" sum=(%.2f,%.2f)", threatFx, threatFz));
            }
            if (wallFx != 0 || wallFz != 0)
                sb.append(String.format(" | wall=(%.2f,%.2f)", wallFx, wallFz));
            if (cliffFx != 0 || cliffFz != 0)
                sb.append(String.format(" | cliff=(%.2f,%.2f)", cliffFx, cliffFz));
            if (hazardFx != 0 || hazardFz != 0)
                sb.append(String.format(" | hazard=(%.2f,%.2f)", hazardFx, hazardFz));
            sb.append(String.format(" | TOTAL=(%.2f,%.2f)", totalFx, totalFz));
            return sb.toString();
        }
    }

    /**
     * Compute the total 2D force vector in world XZ space.
     *
     * @param bot         the bot entity
     * @param target      primary attack target (attractive ring)
     * @param threats     secondary hostile entities (repulsive)
     * @param optimalDist desired distance to maintain from target (ring center)
     * @param tangentStr  tangential (strafing) force strength
     * @param threatK     threat repulsion coefficient
     * @param threatRange max range for threat repulsion
     * @param strafeDir   +1 or -1, which side of the target to orbit
     * @return {forceX, forceZ} world-space force vector (NOT normalized)
     */
    public static double[] computeForceVector(
            FakePlayer bot,
            LivingEntity target,
            List<? extends ThreatSource> threats,
            double optimalDist,
            double tangentStr,
            double threatK,
            double threatRange,
            float strafeDir
    ) {
        return computeForceVector(bot, target, threats, optimalDist, tangentStr,
                threatK, threatRange, strafeDir, null);
    }

    /**
     * Compute the total 2D force vector, optionally populating a debug breakdown.
     */
    public static double[] computeForceVector(
            FakePlayer bot,
            LivingEntity target,
            List<? extends ThreatSource> threats,
            double optimalDist,
            double tangentStr,
            double threatK,
            double threatRange,
            float strafeDir,
            ForceBreakdown dbg
    ) {
        double fx = 0, fz = 0;
        double bx = bot.getX(), bz = bot.getZ();

        // === 1. Target attractive ring ===
        double dx = target.getX() - bx;
        double dz = target.getZ() - bz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double targetNx = 0, targetNz = 0;
        if (dist > 0.01) {
            targetNx = dx / dist;
            targetNz = dz / dist;
        }

        // === 2. Threat repulsion (inverse-square) ===
        // Count threats that block the path to target (between bot and target, in same direction)
        int blockingThreatCount = 0;
        double threatSumFx = 0, threatSumFz = 0;
        if (threats != null) {
            for (ThreatSource threat : threats) {
                double tdx = threat.getX() - bx;
                double tdz = threat.getZ() - bz;
                double tDist = Math.sqrt(tdx * tdx + tdz * tdz);
                if (tDist < 0.5) tDist = 0.5;
                if (tDist > threatRange) continue;

                // Check if this threat is "blocking" — closer than target AND roughly in target direction
                boolean blocking = false;
                if (dist > 0.01 && tDist < dist) {
                    double dot = (tdx * targetNx + tdz * targetNz) / tDist;
                    // dot > 0.5 means within ~60° cone toward target
                    if (dot > 0.5) {
                        blocking = true;
                        blockingThreatCount++;
                    }
                }

                // Inverse-square repulsion, boosted 2x for blocking threats
                double repulsionMult = blocking ? 2.0 : 1.0;
                double repulsion = (threatK * repulsionMult) / (tDist * tDist);
                repulsion = Math.min(repulsion, 4.0);
                double tnx = -tdx / tDist; // away from threat
                double tnz = -tdz / tDist;
                double tfx = tnx * repulsion;
                double tfz = tnz * repulsion;

                // Filter out "toward target" component of threat repulsion.
                // When a threat is behind the bot, its repulsion pushes bot toward target,
                // causing oscillation. Remove that component, keep only perpendicular.
                if (dist > 0.01) {
                    double dotToward = tfx * targetNx + tfz * targetNz;
                    if (dotToward > 0) {
                        // This repulsion has a "push toward target" component — remove it
                        tfx -= dotToward * targetNx;
                        tfz -= dotToward * targetNz;
                    }
                }

                fx += tfx;
                fz += tfz;
                threatSumFx += tfx;
                threatSumFz += tfz;

                if (dbg != null) {
                    dbg.threatDetails.add(String.format("d=%.1f rep=%.2f%s f=(%.2f,%.2f)",
                            tDist, repulsion, blocking ? "[BLOCK]" : "", tfx, tfz));
                }
            }
        }
        if (dbg != null) {
            dbg.threatFx = threatSumFx;
            dbg.threatFz = threatSumFz;
            dbg.blockingThreats = blockingThreatCount;
        }

        // === 1 (cont). Apply target attraction with blocking penalty ===
        if (dist > 0.01) {
            double radialError = dist - optimalDist;
            double radialStrength;

            if (radialError >= 0) {
                // Too far: gentle pull in (spring constant 0.4, cap 1.0)
                // Deliberately weak so bot decelerates before reaching optimal distance
                radialStrength = clamp(radialError * 0.4, 0.0, 1.0);
            } else {
                // Too close: STRONG push out (spring constant 3.0, cap -5.0)
                // Asymmetric: push-out is 7.5x stronger than pull-in
                // High cap ensures bot retreats fast even at large optimal distances (4.0+)
                radialStrength = clamp(radialError * 3.0, -5.0, 0.0);
            }

            // If threats are blocking the path, weaken forward pull to avoid charging through
            // 1 blocker → 40% pull, 2+ blockers → 20% pull (push-out force unaffected)
            double blockingDamp = 1.0;
            if (blockingThreatCount > 0 && radialStrength > 0) {
                blockingDamp = blockingThreatCount >= 2 ? 0.2 : 0.4;
                radialStrength *= blockingDamp;
            }

            fx += targetNx * radialStrength;
            fz += targetNz * radialStrength;

            if (dbg != null) {
                dbg.dist = dist;
                dbg.optimalDist = optimalDist;
                dbg.radialError = radialError;
                dbg.radialForce = radialStrength;
                dbg.blockingDamp = blockingDamp;
            }

            // Tangential: perpendicular to radial direction for orbital motion
            // Threat-aware: choose orbit direction that moves AWAY from threats
            float effectiveStrafeDir = strafeDir;
            double tangentDampening = 1.0;
            double leftScore = 0;   // threat weight on left orbit side
            double rightScore = 0;  // threat weight on right orbit side

            if (threats != null && !threats.isEmpty()) {
                // Project each threat onto the tangent axis to find which side has more threats
                // Left tangent direction = (-targetNz, targetNx)

                for (ThreatSource threat : threats) {
                    double tdx = threat.getX() - bx;
                    double tdz = threat.getZ() - bz;
                    double tDist = threat.getDistance();
                    if (tDist < 0.5) tDist = 0.5;
                    if (tDist > threatRange) continue;

                    // Project threat direction onto left tangent vector
                    double proj = tdx * (-targetNz) + tdz * targetNx;
                    double weight = 1.0 / (tDist * tDist);

                    if (proj > 0) {
                        leftScore += proj * weight;
                    } else {
                        rightScore += (-proj) * weight;
                    }
                }

                // Choose orbit direction away from threat concentration
                if (leftScore > rightScore * 1.5) {
                    // Threats mostly on left → orbit right
                    effectiveStrafeDir = -1.0f;
                } else if (rightScore > leftScore * 1.5) {
                    // Threats mostly on right → orbit left
                    effectiveStrafeDir = 1.0f;
                } else {
                    // Threats on both sides → suppress orbit, back away straight
                    tangentDampening = 0.2;
                }
            }

            // When too close: further suppress orbit to back away straight
            double effectiveTangent = tangentStr * tangentDampening;
            if (radialError < -0.3) {
                if (threats != null && !threats.isEmpty()) {
                    effectiveTangent *= 0.2;
                } else {
                    effectiveTangent *= 0.7;
                }
            }

            double tx = -targetNz * effectiveStrafeDir;
            double tz =  targetNx * effectiveStrafeDir;
            fx += tx * effectiveTangent;
            fz += tz * effectiveTangent;

            if (dbg != null) {
                dbg.tangentDir = effectiveStrafeDir;
                dbg.tangentForce = effectiveTangent;
                dbg.tangentDampening = tangentDampening;
                dbg.leftThreatScore = leftScore;
                dbg.rightThreatScore = rightScore;
            }
        }

        // === 3. Wall repulsion ===
        Level level = bot.level();
        int by = (int) Math.floor(bot.getY());
        double wallSumFx = 0, wallSumFz = 0;
        for (double[] off : CARDINAL_OFFSETS) {
            double checkX = bx + off[0] * 1.5;
            double checkZ = bz + off[1] * 1.5;
            BlockPos pos = new BlockPos((int) Math.floor(checkX), by, (int) Math.floor(checkZ));
            BlockState state = level.getBlockState(pos);
            if (state.isSolidRender(level, pos)) {
                // Also check one block above (full wall, not just a step)
                BlockPos posUp = pos.above();
                BlockState stateUp = level.getBlockState(posUp);
                if (stateUp.isSolidRender(level, posUp)) {
                    // Full wall — push away
                    double wallDist = Math.sqrt(off[0] * off[0] + off[1] * off[1]) * 1.5;
                    double actualDist = Math.max(0.3, distToBlockEdge(bx, bz, off[0], off[1]));
                    double wallForce = 0.5 / (actualDist * actualDist);
                    wallForce = Math.min(wallForce, 1.5);
                    fx -= off[0] * wallForce;
                    fz -= off[1] * wallForce;
                    wallSumFx -= off[0] * wallForce;
                    wallSumFz -= off[1] * wallForce;
                }
            }
        }
        if (dbg != null) { dbg.wallFx = wallSumFx; dbg.wallFz = wallSumFz; }

        // === 4. Cliff repulsion ===
        double cliffSumFx = 0, cliffSumFz = 0;
        for (double[] off : CARDINAL_OFFSETS) {
            double checkX = bx + off[0] * 1.5;
            double checkZ = bz + off[1] * 1.5;
            BlockPos edgeGround = new BlockPos((int) Math.floor(checkX), by - 1, (int) Math.floor(checkZ));

            if (level.getBlockState(edgeGround).isAir()) {
                // Check depth of the drop
                int dropDepth = 0;
                for (int y = by - 2; y >= by - 5; y--) {
                    if (level.getBlockState(new BlockPos((int) Math.floor(checkX), y, (int) Math.floor(checkZ))).isAir()) {
                        dropDepth++;
                    } else {
                        break;
                    }
                }
                if (dropDepth >= 2) {
                    // Significant drop — strong cliff repulsion
                    double cliffForce = 1.5 * (dropDepth / 4.0);
                    cliffForce = Math.min(cliffForce, 2.5);
                    fx -= off[0] * cliffForce;
                    fz -= off[1] * cliffForce;
                    cliffSumFx -= off[0] * cliffForce;
                    cliffSumFz -= off[1] * cliffForce;
                }
            }
        }
        if (dbg != null) { dbg.cliffFx = cliffSumFx; dbg.cliffFz = cliffSumFz; }

        // === 5. Hazard repulsion (lava, fire, water) ===
        double hazardSumFx = 0, hazardSumFz = 0;
        for (double[] off : OCTAL_OFFSETS) {
            double checkX = bx + off[0] * 2.0;
            double checkZ = bz + off[1] * 2.0;
            BlockPos hPos = new BlockPos((int) Math.floor(checkX), by, (int) Math.floor(checkZ));
            BlockPos hPosBelow = new BlockPos((int) Math.floor(checkX), by - 1, (int) Math.floor(checkZ));

            // Check lava at foot level and below
            FluidState fluid = level.getFluidState(hPos);
            FluidState fluidBelow = level.getFluidState(hPosBelow);
            BlockState blockState = level.getBlockState(hPos);

            if (fluid.is(FluidTags.LAVA) || fluidBelow.is(FluidTags.LAVA)
                    || blockState.is(Blocks.FIRE) || blockState.is(Blocks.SOUL_FIRE)) {
                // Very strong lava/fire repulsion
                double hazardDist = Math.sqrt(off[0] * off[0] + off[1] * off[1]) * 2.0;
                double hazardForce = 5.0 / (hazardDist * hazardDist);
                hazardForce = Math.min(hazardForce, 4.0);
                double norm = Math.sqrt(off[0] * off[0] + off[1] * off[1]);
                fx -= (off[0] / norm) * hazardForce;
                fz -= (off[1] / norm) * hazardForce;
                hazardSumFx -= (off[0] / norm) * hazardForce;
                hazardSumFz -= (off[1] / norm) * hazardForce;
            } else if (fluid.is(FluidTags.WATER) || fluidBelow.is(FluidTags.WATER)) {
                // Moderate water repulsion (slow zone)
                double hazardDist = Math.sqrt(off[0] * off[0] + off[1] * off[1]) * 2.0;
                double hazardForce = 1.0 / (hazardDist * hazardDist);
                hazardForce = Math.min(hazardForce, 1.0);
                double norm = Math.sqrt(off[0] * off[0] + off[1] * off[1]);
                fx -= (off[0] / norm) * hazardForce;
                fz -= (off[1] / norm) * hazardForce;
                hazardSumFx -= (off[0] / norm) * hazardForce;
                hazardSumFz -= (off[1] / norm) * hazardForce;
            }
        }
        if (dbg != null) {
            dbg.hazardFx = hazardSumFx;
            dbg.hazardFz = hazardSumFz;
            dbg.totalFx = fx;
            dbg.totalFz = fz;
        }

        return new double[]{fx, fz};
    }

    /**
     * Convert a world-space XZ force vector to bot-relative (forward, strafe) inputs.
     * Uses the bot's current yaw to project the force onto forward and right directions.
     *
     * @param forceX world-space X component
     * @param forceZ world-space Z component
     * @param yawDeg bot's current yaw in degrees
     * @return {forward, strafe} clamped to [-1, 1], suitable for setMovementInput()
     */
    public static float[] worldToRelativeInput(double forceX, double forceZ, float yawDeg) {
        // Bot forward direction from yaw (Minecraft: yaw 0 = +Z, yaw 90 = -X)
        double yawRad = Math.toRadians(yawDeg);
        double fwdX = -Math.sin(yawRad);
        double fwdZ =  Math.cos(yawRad);
        // Right direction (perpendicular, clockwise from above)
        double rightX =  fwdZ;  // cos(yaw)
        double rightZ = -fwdX;  // sin(yaw)

        // Project force onto forward and right
        double forward = forceX * fwdX + forceZ * fwdZ;
        double strafe  = -(forceX * rightX + forceZ * rightZ); // negative: MC strafe left = +1

        // Clamp to valid input range
        return new float[]{
                (float) clamp(forward, -1.0, 1.0),
                (float) clamp(strafe, -1.0, 1.0)
        };
    }

    /**
     * Compute force for pursuit mode (approaching target while avoiding threats).
     * Target has stronger attraction (we want to reach it), threats have weaker repulsion.
     */
    public static double[] computePursuitForceVector(
            FakePlayer bot,
            LivingEntity target,
            List<? extends ThreatSource> threats,
            double threatK,
            double threatRange
    ) {
        double fx = 0, fz = 0;
        double bx = bot.getX(), bz = bot.getZ();

        // === 1. Strong attraction toward target ===
        double dx = target.getX() - bx;
        double dz = target.getZ() - bz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.01) {
            double nx = dx / dist;
            double nz = dz / dist;
            // Strong pull: scale with distance but cap at 2.0
            double pullStrength = Math.min(dist * 0.5, 2.0);
            fx += nx * pullStrength;
            fz += nz * pullStrength;
        }

        // === 2. Weaker threat repulsion (still approach, but path around) ===
        if (threats != null) {
            for (ThreatSource threat : threats) {
                double tdx = threat.getX() - bx;
                double tdz = threat.getZ() - bz;
                double tDist = Math.sqrt(tdx * tdx + tdz * tdz);
                if (tDist < 0.5) tDist = 0.5;
                if (tDist > threatRange) continue;

                // Weaker repulsion for pursuit: k * 0.6
                double repulsion = (threatK * 0.6) / (tDist * tDist);
                repulsion = Math.min(repulsion, 2.0);
                fx -= (tdx / tDist) * repulsion;
                fz -= (tdz / tDist) * repulsion;
            }
        }

        // === 3. Wall repulsion (same as melee, prevent getting stuck) ===
        Level level = bot.level();
        int by = (int) Math.floor(bot.getY());
        for (double[] off : CARDINAL_OFFSETS) {
            double checkX = bx + off[0] * 1.5;
            double checkZ = bz + off[1] * 1.5;
            BlockPos pos = new BlockPos((int) Math.floor(checkX), by, (int) Math.floor(checkZ));
            BlockState state = level.getBlockState(pos);
            if (state.isSolidRender(level, pos)) {
                BlockPos posUp = pos.above();
                BlockState stateUp = level.getBlockState(posUp);
                if (stateUp.isSolidRender(level, posUp)) {
                    double actualDist = Math.max(0.3, distToBlockEdge(bx, bz, off[0], off[1]));
                    double wallForce = 0.5 / (actualDist * actualDist);
                    wallForce = Math.min(wallForce, 1.5);
                    fx -= off[0] * wallForce;
                    fz -= off[1] * wallForce;
                }
            }
        }

        // === 4. Hazard repulsion (same as melee) ===
        for (double[] off : OCTAL_OFFSETS) {
            double checkX = bx + off[0] * 2.0;
            double checkZ = bz + off[1] * 2.0;
            BlockPos hPos = new BlockPos((int) Math.floor(checkX), by, (int) Math.floor(checkZ));
            BlockPos hPosBelow = new BlockPos((int) Math.floor(checkX), by - 1, (int) Math.floor(checkZ));

            FluidState fluid = level.getFluidState(hPos);
            FluidState fluidBelow = level.getFluidState(hPosBelow);
            BlockState blockState = level.getBlockState(hPos);

            if (fluid.is(FluidTags.LAVA) || fluidBelow.is(FluidTags.LAVA)
                    || blockState.is(Blocks.FIRE) || blockState.is(Blocks.SOUL_FIRE)) {
                double hazardDist = Math.sqrt(off[0] * off[0] + off[1] * off[1]) * 2.0;
                double hazardForce = 5.0 / (hazardDist * hazardDist);
                hazardForce = Math.min(hazardForce, 4.0);
                double norm = Math.sqrt(off[0] * off[0] + off[1] * off[1]);
                fx -= (off[0] / norm) * hazardForce;
                fz -= (off[1] / norm) * hazardForce;
            }
        }

        return new double[]{fx, fz};
    }

    // ==================== Utility ====================

    /**
     * Approximate distance from bot position to nearest block edge in a given direction.
     */
    private static double distToBlockEdge(double bx, double bz, double dirX, double dirZ) {
        double edgeDist;
        if (dirX > 0) {
            edgeDist = Math.ceil(bx) - bx;
        } else if (dirX < 0) {
            edgeDist = bx - Math.floor(bx);
        } else if (dirZ > 0) {
            edgeDist = Math.ceil(bz) - bz;
        } else {
            edgeDist = bz - Math.floor(bz);
        }
        return Math.max(edgeDist, 0.1);
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    /**
     * Interface for threat sources, so CombatController can pass ThreatData
     * or raw LivingEntity positions without coupling.
     */
    public interface ThreatSource {
        double getX();
        double getZ();
        double getDistance();
    }
}
