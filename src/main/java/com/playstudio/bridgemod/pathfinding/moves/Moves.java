package com.playstudio.bridgemod.pathfinding.moves;

import com.playstudio.bridgemod.pathfinding.ActionCosts;
import com.playstudio.bridgemod.pathfinding.CalculationContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * All possible single-step movements in the A* search.
 * Ported from Baritone's Moves enum.
 *
 * Phase 3B: 16 movement types (4 traverse + 4 ascend + 4 descend + 4 diagonal).
 * Phase 3C: +1 PILLAR_UP = 17 total. TODO: PARKOUR.
 *
 * Omissions vs Baritone (Phase 3B): no block placing (bridge/pillar), no mining,
 * no parkour, no frost walker. These are all Phase 3C.
 *
 * dynamicXZ: destination XZ is computed by cost function (not fixed offset)
 * dynamicY: destination Y is computed by cost function (not fixed offset)
 */
public enum Moves {

    // --- Traverse (flat, 1 block cardinal) ---
    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementTraverse.cost(ctx, x, y, z, x, z - 1);
        }
    },
    TRAVERSE_SOUTH(0, 0, 1) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementTraverse.cost(ctx, x, y, z, x, z + 1);
        }
    },
    TRAVERSE_EAST(1, 0, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementTraverse.cost(ctx, x, y, z, x + 1, z);
        }
    },
    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementTraverse.cost(ctx, x, y, z, x - 1, z);
        }
    },

    // --- Ascend (up 1 block, cardinal) ---
    ASCEND_NORTH(0, 1, -1) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementAscend.cost(ctx, x, y, z, x, z - 1);
        }
    },
    ASCEND_SOUTH(0, 1, 1) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementAscend.cost(ctx, x, y, z, x, z + 1);
        }
    },
    ASCEND_EAST(1, 1, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementAscend.cost(ctx, x, y, z, x + 1, z);
        }
    },
    ASCEND_WEST(-1, 1, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementAscend.cost(ctx, x, y, z, x - 1, z);
        }
    },

    // --- Descend (down 1+ blocks, cardinal) - dynamicY ---
    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MoveResult result) {
            MovementDescend.cost(ctx, x, y, z, x, z - 1, result);
        }
    },
    DESCEND_SOUTH(0, -1, 1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MoveResult result) {
            MovementDescend.cost(ctx, x, y, z, x, z + 1, result);
        }
    },
    DESCEND_EAST(1, -1, 0, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MoveResult result) {
            MovementDescend.cost(ctx, x, y, z, x + 1, z, result);
        }
    },
    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MoveResult result) {
            MovementDescend.cost(ctx, x, y, z, x - 1, z, result);
        }
    },

    // --- Diagonal (1 block diagonal, possibly +/-1 Y) - dynamicY ---
    DIAGONAL_NORTHEAST(1, 0, -1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MoveResult result) {
            MovementDiagonal.cost(ctx, x, y, z, x + 1, z - 1, result);
        }
    },
    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MoveResult result) {
            MovementDiagonal.cost(ctx, x, y, z, x - 1, z - 1, result);
        }
    },
    DIAGONAL_SOUTHEAST(1, 0, 1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MoveResult result) {
            MovementDiagonal.cost(ctx, x, y, z, x + 1, z + 1, result);
        }
    },
    DIAGONAL_SOUTHWEST(-1, 0, 1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MoveResult result) {
            MovementDiagonal.cost(ctx, x, y, z, x - 1, z + 1, result);
        }
    },

    // --- Pillar (jump straight up, place block under feet) ---
    PILLAR_UP(0, 1, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementPillar.cost(ctx, x, y, z);
        }
    };

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;
    public final boolean dynamicXZ;
    public final boolean dynamicY;

    Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    Moves(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    /**
     * For non-dynamic moves: compute cost and return it.
     * Override this for TRAVERSE and ASCEND.
     */
    public double cost(CalculationContext ctx, int x, int y, int z) {
        throw new UnsupportedOperationException();
    }

    /**
     * For dynamic moves: compute cost and write result into MoveResult.
     * Override this for DESCEND and DIAGONAL.
     * Default impl delegates to cost() for non-dynamic moves.
     */
    public void apply(CalculationContext ctx, int x, int y, int z, MoveResult result) {
        if (dynamicXZ || dynamicY) {
            throw new UnsupportedOperationException();
        }
        result.x = x + xOffset;
        result.y = y + yOffset;
        result.z = z + zOffset;
        result.cost = cost(ctx, x, y, z);
    }

    // ==================== Movement Cost Implementations ====================

    /**
     * Traverse: walk 1 block on flat ground.
     * Ported from Baritone's MovementTraverse.cost().
     * Omits: bridge/backplace logic (Phase 3C).
     */
    static final class MovementTraverse {
        static double cost(CalculationContext ctx, int x, int y, int z, int destX, int destZ) {
            BlockState pb0 = ctx.get(destX, y + 1, destZ);  // head at dest
            BlockState pb1 = ctx.get(destX, y, destZ);       // feet at dest
            BlockState destOn = ctx.get(destX, y - 1, destZ); // ground at dest
            BlockState srcDown = ctx.get(x, y - 1, z);        // ground at src
            Block srcDownBlock = srcDown.getBlock();

            if (MovementHelper.canWalkOn(ctx, destX, y - 1, destZ, destOn)) {
                // This is a walk, not a bridge
                double WC = ActionCosts.WALK_ONE_BLOCK_COST;
                boolean water = false;
                if (MovementHelper.isWater(pb0.getBlock()) || MovementHelper.isWater(pb1.getBlock())) {
                    WC = ctx.waterWalkSpeed;
                    water = true;
                } else {
                    if (destOn.getBlock() == Blocks.SOUL_SAND) {
                        WC += (ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST - ActionCosts.WALK_ONE_BLOCK_COST) / 2;
                    } else if (ctx.assumeWalkOnWater && MovementHelper.isWater(destOn)) {
                        WC += ctx.walkOnWaterOnePenalty; // Jesus mode: extra cost for walking on water surface
                    }
                    if (srcDownBlock == Blocks.SOUL_SAND) {
                        WC += (ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST - ActionCosts.WALK_ONE_BLOCK_COST) / 2;
                    }
                }
                double hardness1 = MovementHelper.getMiningDurationTicks(ctx, destX, y, destZ, pb1, false);
                if (hardness1 >= ActionCosts.COST_INF) {
                    return ActionCosts.COST_INF;
                }
                double hardness2 = MovementHelper.getMiningDurationTicks(ctx, destX, y + 1, destZ, pb0, true);
                if (hardness1 == 0 && hardness2 == 0) {
                    if (!water && ctx.canSprint) {
                        // Nothing in the way, not water, can sprint
                        WC *= ActionCosts.SPRINT_MULTIPLIER;
                    }
                    return WC;
                }
                if (srcDownBlock instanceof LadderBlock || srcDownBlock instanceof VineBlock) {
                    hardness1 *= 5;
                    hardness2 *= 5;
                }
                return WC + hardness1 + hardness2;
            } else {
                // Bridge: no ground at dest — place block below dest
                if (!ctx.hasThrowawayBlock) return ActionCosts.COST_INF;

                // Must have source ground to place against (backplace)
                if (!MovementHelper.canWalkOn(ctx, x, y - 1, z, srcDown)) {
                    return ActionCosts.COST_INF;
                }

                // Dest feet and head must be passable (or mineable)
                double h1 = MovementHelper.getMiningDurationTicks(ctx, destX, y, destZ, pb1, false);
                if (h1 >= ActionCosts.COST_INF) return ActionCosts.COST_INF;
                double h2 = MovementHelper.getMiningDurationTicks(ctx, destX, y + 1, destZ, pb0, true);
                if (h2 >= ActionCosts.COST_INF) return ActionCosts.COST_INF;

                // Cost = sneak to edge + place block + mining
                return ActionCosts.SNEAK_ONE_BLOCK_COST + ActionCosts.PLACE_ONE_BLOCK_COST + h1 + h2;
            }
        }
    }

    /**
     * Ascend: jump up 1 block.
     * Ported from Baritone's MovementAscend.cost().
     * Omits: block placement (Phase 3C).
     */
    static final class MovementAscend {
        static double cost(CalculationContext ctx, int x, int y, int z, int destX, int destZ) {
            BlockState destOn = ctx.get(destX, y, destZ);  // block we jump onto

            // Must be able to stand on the destination block
            boolean needsPlacement = false;
            if (!MovementHelper.canWalkOn(ctx, destX, y, destZ, destOn)) {
                // Pillar-ascend: place block at dest feet position
                if (!ctx.hasThrowawayBlock) return ActionCosts.COST_INF;

                // Must have ground below dest to place against (face UP)
                BlockState belowDest = ctx.get(destX, y - 1, destZ);
                if (!MovementHelper.canWalkOn(ctx, destX, y - 1, destZ, belowDest)) {
                    return ActionCosts.COST_INF;
                }

                // Dest feet must be passable (can place block into it)
                if (!MovementHelper.canWalkThrough(ctx, destX, y, destZ, destOn)) {
                    return ActionCosts.COST_INF;
                }

                needsPlacement = true;
            }

            // Baritone: falling block check - sand/gravel at y+3 would fall when y+2 cleared
            BlockState srcUp2 = ctx.get(x, y + 2, z);
            if (ctx.get(x, y + 3, z).getBlock() instanceof FallingBlock
                    && (MovementHelper.canWalkThrough(ctx, x, y + 1, z)
                        || !(srcUp2.getBlock() instanceof FallingBlock))) {
                return ActionCosts.COST_INF;
            }

            BlockState srcDown = ctx.get(x, y - 1, z);
            Block srcDownBlock = srcDown.getBlock();

            // Baritone: can't ascend from ladder/vine
            if (srcDownBlock instanceof LadderBlock || srcDownBlock instanceof VineBlock) {
                return ActionCosts.COST_INF;
            }

            // Baritone: bottom slab jump restriction
            // Standing on bottom slab (feet at y+0.5) can't reach full block at y+1 (1.5 block jump)
            // When placing a block, the placed block is always a full block, so skip slab-to-slab checks.
            boolean jumpingFromBottomSlab = MovementHelper.isBottomSlab(srcDown);
            boolean jumpingToBottomSlab = !needsPlacement && MovementHelper.isBottomSlab(destOn);
            if (jumpingFromBottomSlab && !jumpingToBottomSlab) {
                return ActionCosts.COST_INF;
            }

            // Baritone cost formula: walking and jumping happen simultaneously → Math.max
            double walk;
            if (jumpingToBottomSlab) {
                if (jumpingFromBottomSlab) {
                    // Slab-to-slab: normal jump
                    walk = Math.max(ActionCosts.JUMP_ONE_BLOCK_COST, ActionCosts.WALK_ONE_BLOCK_COST);
                    walk += ctx.jumpPenalty;
                } else {
                    // Full-block-to-slab: step-up (0.5 block rise), no jump needed
                    walk = ActionCosts.WALK_ONE_BLOCK_COST;
                }
            } else {
                if (!needsPlacement && destOn.getBlock() == Blocks.SOUL_SAND) {
                    walk = ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST;
                } else {
                    walk = Math.max(ActionCosts.JUMP_ONE_BLOCK_COST, ActionCosts.WALK_ONE_BLOCK_COST);
                }
                walk += ctx.jumpPenalty;
            }

            // Mining costs for 3 blocks (Baritone order: srcUp2, dest feet, dest head)
            double totalCost = walk;
            if (needsPlacement) {
                totalCost += ActionCosts.PLACE_ONE_BLOCK_COST;
            }
            totalCost += MovementHelper.getMiningDurationTicks(ctx, x, y + 2, z, srcUp2, false);
            if (totalCost >= ActionCosts.COST_INF) return ActionCosts.COST_INF;
            totalCost += MovementHelper.getMiningDurationTicks(ctx, destX, y + 1, destZ,
                    ctx.get(destX, y + 1, destZ), false);
            if (totalCost >= ActionCosts.COST_INF) return ActionCosts.COST_INF;
            totalCost += MovementHelper.getMiningDurationTicks(ctx, destX, y + 2, destZ,
                    ctx.get(destX, y + 2, destZ), true);
            return totalCost;
        }
    }

    /**
     * Descend: walk off an edge and fall 1+ blocks.
     * Ported from Baritone's MovementDescend.cost() + dynamicFallCost().
     * Uses MoveResult because the landing Y is dynamic.
     * Omits: water bucket landing, frost walker.
     */
    static final class MovementDescend {
        static void cost(CalculationContext ctx, int x, int y, int z, int destX, int destZ, MoveResult result) {
            // Baritone: check 3 blocks in the forward column
            BlockState destFeet = ctx.get(destX, y - 1, destZ);   // landing feet position
            BlockState pb0 = ctx.get(destX, y, destZ);            // step-off feet level
            BlockState pb1 = ctx.get(destX, y + 1, destZ);        // step-off head level

            double hardness0 = MovementHelper.getMiningDurationTicks(ctx, destX, y - 1, destZ, destFeet, false);
            if (hardness0 >= ActionCosts.COST_INF) return;
            double hardness1 = MovementHelper.getMiningDurationTicks(ctx, destX, y, destZ, pb0, false);
            if (hardness1 >= ActionCosts.COST_INF) return;
            double hardness2 = MovementHelper.getMiningDurationTicks(ctx, destX, y + 1, destZ, pb1, true);
            if (hardness2 >= ActionCosts.COST_INF) return;

            BlockState srcDown = ctx.get(x, y - 1, z);
            Block srcDownBlock = srcDown.getBlock();

            // Baritone: can't descend from ladder/vine
            if (srcDownBlock instanceof LadderBlock || srcDownBlock instanceof VineBlock) {
                return;
            }

            BlockState below = ctx.get(destX, y - 2, destZ);

            // 1-block descent check
            if (MovementHelper.canWalkOn(ctx, destX, y - 2, destZ, below)) {
                // Baritone: can't land at ladder/vine position
                if (destFeet.getBlock() instanceof LadderBlock || destFeet.getBlock() instanceof VineBlock) {
                    return;
                }
                if (hardness0 == 0 && hardness1 == 0 && hardness2 == 0) {
                    if (MovementHelper.isLava(ctx, destX, y - 2, destZ)) return;

                    // Baritone: soul sand multiplies walk-off cost
                    double walk = ActionCosts.WALK_OFF_BLOCK_COST;
                    if (srcDownBlock == Blocks.SOUL_SAND) {
                        walk *= ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST / ActionCosts.WALK_ONE_BLOCK_COST;
                    }
                    double totalCost = walk
                            + Math.max(ActionCosts.FALL_N_BLOCKS_COST[1], ActionCosts.CENTER_AFTER_FALL_COST)
                            + hardness0 + hardness1 + hardness2;

                    result.x = destX;
                    result.y = y - 1;
                    result.z = destZ;
                    result.cost = totalCost;
                    return;
                }
                return;  // Can't mine in descent
            }

            // Not a 1-block descend: check if we can fall further
            if (!MovementHelper.canWalkThrough(ctx, destX, y - 2, destZ, below)) {
                return;
            }

            // Multi-block fall (Baritone's dynamicFallCost)
            if (hardness0 != 0 || hardness1 != 0 || hardness2 != 0) {
                return;  // Can't mine during fall
            }

            double frontBreak = hardness0 + hardness1 + hardness2;
            int effectiveStartHeight = y;
            double costSoFar = 0;
            int maxFall = ctx.maxFallHeightNoWater;

            for (int fallHeight = 3; ; fallHeight++) {
                int newY = y - fallHeight;
                if (newY < ctx.getLevel().getMinBuildHeight()) return;

                BlockState ontoBlock = ctx.get(destX, newY, destZ);
                int unprotectedFallHeight = fallHeight - (y - effectiveStartHeight);

                // Bounds check for FALL_N_BLOCKS_COST array
                if (unprotectedFallHeight >= ActionCosts.FALL_N_BLOCKS_COST.length) return;

                // Baritone: vine/ladder catch - player grabs on if unprotected fall <= 11 blocks
                Block ontoBlockType = ontoBlock.getBlock();
                if (unprotectedFallHeight <= 11
                        && (ontoBlockType instanceof LadderBlock || ontoBlockType instanceof VineBlock)) {
                    costSoFar += ActionCosts.FALL_N_BLOCKS_COST[Math.max(unprotectedFallHeight - 1, 0)];
                    costSoFar += ActionCosts.LADDER_DOWN_ONE_COST;
                    effectiveStartHeight = newY;
                    continue;
                }

                // Passable: keep falling
                if (MovementHelper.canWalkThrough(ctx, destX, newY, destZ, ontoBlock)) {
                    continue;
                }

                // Not walkable-on: blocked
                if (!MovementHelper.canWalkOn(ctx, destX, newY, destZ, ontoBlock)) {
                    return;
                }

                // Baritone: avoid bottom slabs (glitchy landing)
                if (MovementHelper.isBottomSlab(ontoBlock)) {
                    return;
                }

                // Check safe fall height
                // Baritone: water landing negates fall damage (4-guard check)
                if (unprotectedFallHeight > maxFall + 1) {
                    if (!MovementHelper.isWater(ontoBlock)) {
                        return;  // Would take fatal fall damage on non-water
                    }
                    // Baritone 4-guard for safe water landing:
                    if (!MovementHelper.canWalkThrough(ctx, destX, newY, destZ, ontoBlock)) return;
                    if (ctx.assumeWalkOnWater) return; // Jesus mode: can't fall into water
                    if (MovementHelper.isFlowing(ctx, destX, newY, destZ, ontoBlock)) return;
                    if (!MovementHelper.canWalkOn(ctx, destX, newY - 1, destZ)) return; // water bottom must exist
                }

                // Check for lava at landing
                if (MovementHelper.isLava(ctx, destX, newY, destZ)) return;

                // Baritone: soul sand multiplies walk-off cost
                double walk = ActionCosts.WALK_OFF_BLOCK_COST;
                if (srcDownBlock == Blocks.SOUL_SAND) {
                    walk *= ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST / ActionCosts.WALK_ONE_BLOCK_COST;
                }
                double totalCost = walk
                        + ActionCosts.FALL_N_BLOCKS_COST[unprotectedFallHeight]
                        + frontBreak + costSoFar;

                result.x = destX;
                result.y = newY + 1;
                result.z = destZ;
                result.cost = totalCost;
                return;
            }
        }
    }

    /**
     * Diagonal: move 1 block diagonally, possibly ascending/descending 1 block.
     * Ported from Baritone's MovementDiagonal.cost().
     * Uses MoveResult because Y may change.
     * Omits: frost walker support.
     */
    static final class MovementDiagonal {
        static void cost(CalculationContext ctx, int x, int y, int z, int destX, int destZ, MoveResult result) {
            // Baritone: first check - dest head level (y+1) must be passable for ANY diagonal variant.
            // For flat diagonal: this is the head position.
            // For diagonal ascend: this is the feet position (critical - prevents ascending into solid blocks).
            if (!MovementHelper.canWalkThrough(ctx, destX, y + 1, destZ)) {
                return;
            }

            BlockState destInto = ctx.get(destX, y, destZ);

            boolean ascend = false;
            boolean descend = false;
            BlockState destWalkOn;
            BlockState fromDown;

            // Baritone: ascend triggered by !canWalkThrough (not canWalkOn)
            // !canWalkThrough catches ALL blocking blocks (solid, fence, wall, etc.)
            // Then canWalkOn inside verifies it's actually standable.
            if (!MovementHelper.canWalkThrough(ctx, destX, y, destZ, destInto)) {
                ascend = true;
                if (!ctx.allowDiagonalAscend
                        || !MovementHelper.canWalkThrough(ctx, x, y + 2, z)
                        || !MovementHelper.canWalkOn(ctx, destX, y, destZ, destInto)
                        || !MovementHelper.canWalkThrough(ctx, destX, y + 2, destZ)) {
                    return;
                }
                destWalkOn = destInto;
                fromDown = ctx.get(x, y - 1, z);
            } else {
                destWalkOn = ctx.get(destX, y - 1, destZ);
                fromDown = ctx.get(x, y - 1, z);
                if (!MovementHelper.canWalkOn(ctx, destX, y - 1, destZ, destWalkOn)) {
                    descend = true;
                    if (!ctx.allowDiagonalDescend
                            || !MovementHelper.canWalkOn(ctx, destX, y - 2, destZ)
                            || !MovementHelper.canWalkThrough(ctx, destX, y - 1, destZ, destWalkOn)) {
                        return;
                    }
                }
            }

            double multiplier = ActionCosts.WALK_ONE_BLOCK_COST;

            // Soul sand at destination (affects half of walk)
            if (destWalkOn.getBlock() == Blocks.SOUL_SAND) {
                multiplier += (ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST - ActionCosts.WALK_ONE_BLOCK_COST) / 2;
            }

            // Soul sand at source (affects other half)
            Block fromDownBlock = fromDown.getBlock();
            if (fromDownBlock instanceof LadderBlock || fromDownBlock instanceof VineBlock) {
                return;  // Can't diagonal from ladder/vine
            }
            if (fromDownBlock == Blocks.SOUL_SAND) {
                multiplier += (ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST - ActionCosts.WALK_ONE_BLOCK_COST) / 2;
            }

            // Check intermediate blocks for lava/magma
            Block cuttingOver1 = ctx.get(x, y - 1, destZ).getBlock();
            if (cuttingOver1 == Blocks.MAGMA_BLOCK || MovementHelper.isLava(cuttingOver1)) return;
            Block cuttingOver2 = ctx.get(destX, y - 1, z).getBlock();
            if (cuttingOver2 == Blocks.MAGMA_BLOCK || MovementHelper.isLava(cuttingOver2)) return;

            // Check for water (affects cost)
            Block startIn = ctx.get(x, y, z).getBlock();
            boolean water = false;
            if (MovementHelper.isWater(startIn) || MovementHelper.isWater(destInto.getBlock())) {
                if (ascend) return;  // Can't diagonal-ascend in water
                multiplier = ctx.waterWalkSpeed;
                water = true;
            }

            // Check intermediate paths (two cardinal directions to reach diagonal)
            BlockState pb0 = ctx.get(x, y, destZ);
            BlockState pb2 = ctx.get(destX, y, z);

            if (ascend) {
                // Need 3-block clearance for ascend path
                boolean ATop = MovementHelper.canWalkThrough(ctx, x, y + 2, destZ);
                boolean AMid = MovementHelper.canWalkThrough(ctx, x, y + 1, destZ);
                boolean ALow = MovementHelper.canWalkThrough(ctx, x, y, destZ, pb0);
                boolean BTop = MovementHelper.canWalkThrough(ctx, destX, y + 2, z);
                boolean BMid = MovementHelper.canWalkThrough(ctx, destX, y + 1, z);
                boolean BLow = MovementHelper.canWalkThrough(ctx, destX, y, z, pb2);
                if ((!(ATop && AMid && ALow) && !(BTop && BMid && BLow))
                        || MovementHelper.avoidWalkingInto(pb0.getBlock())
                        || MovementHelper.avoidWalkingInto(pb2.getBlock())
                        || (ATop && AMid && MovementHelper.canWalkOn(ctx, x, y, destZ, pb0))
                        || (BTop && BMid && MovementHelper.canWalkOn(ctx, destX, y, z, pb2))
                        || (!ATop && AMid && ALow)
                        || (!BTop && BMid && BLow)) {
                    return;
                }
                result.cost = multiplier * ActionCosts.SQRT_2 + ctx.jumpPenalty;
                result.x = destX;
                result.z = destZ;
                result.y = y + 1;
                return;
            }

            // Non-ascend: check 2-block clearance
            double optionA = MovementHelper.getMiningDurationTicks(ctx, x, y, destZ, pb0, false);
            double optionB = MovementHelper.getMiningDurationTicks(ctx, destX, y, z, pb2, false);
            if (optionA != 0 && optionB != 0) return;

            BlockState pb1 = ctx.get(x, y + 1, destZ);
            optionA += MovementHelper.getMiningDurationTicks(ctx, x, y + 1, destZ, pb1, true);
            if (optionA != 0 && optionB != 0) return;

            BlockState pb3 = ctx.get(destX, y + 1, z);
            if (optionA == 0 && ((MovementHelper.avoidWalkingInto(pb2.getBlock()) && pb2.getBlock() != Blocks.WATER)
                    || MovementHelper.avoidWalkingInto(pb3.getBlock()))) {
                return;
            }
            optionB += MovementHelper.getMiningDurationTicks(ctx, destX, y + 1, z, pb3, true);
            if (optionA != 0 && optionB != 0) return;

            if (optionB == 0 && ((MovementHelper.avoidWalkingInto(pb0.getBlock()) && pb0.getBlock() != Blocks.WATER)
                    || MovementHelper.avoidWalkingInto(pb1.getBlock()))) {
                return;
            }

            // Check dest head clearance
            BlockState destHead = ctx.get(destX, y + 1, destZ);
            if (MovementHelper.getMiningDurationTicks(ctx, destX, y + 1, destZ, destHead, true) >= ActionCosts.COST_INF) {
                return;
            }
            if (MovementHelper.getMiningDurationTicks(ctx, destX, y, destZ, destInto, false) >= ActionCosts.COST_INF) {
                return;
            }

            if (optionA != 0 || optionB != 0) {
                multiplier *= ActionCosts.SQRT_2 - 0.001;  // Baritone: TODO tune
                if (startIn instanceof LadderBlock || startIn instanceof VineBlock) {
                    return;  // Edging around doesn't work on ladder/vine
                }
            } else {
                // Only can sprint if not edging around
                if (ctx.canSprint && !water) {
                    multiplier *= ActionCosts.SPRINT_MULTIPLIER;
                }
            }

            result.cost = multiplier * ActionCosts.SQRT_2;
            if (descend) {
                result.cost += Math.max(ActionCosts.FALL_N_BLOCKS_COST[1], ActionCosts.CENTER_AFTER_FALL_COST);
                result.y = y - 1;
            } else {
                result.y = y;
            }
            result.x = destX;
            result.z = destZ;
        }
    }

    /**
     * Pillar: jump straight up and place block under feet.
     * Same XZ, Y+1. 100% ported from Baritone's MovementPillar.cost().
     *
     * Process: jump → place block at (x,y,z) against (x,y-1,z) face UP → land on new block.
     *
     * Key Baritone design: does NOT check canWalkOn(y-1). For consecutive pillars, the block
     * at y-1 was placed by the prior pillar movement and doesn't exist in the world snapshot.
     * Instead, costOfPlacingAt() only checks hasThrowawayBlock. A small +0.1 penalty is added
     * when y-1 is currently air, as a tiebreaker to prefer pillaring on real ground.
     */
    static final class MovementPillar {
        static double cost(CalculationContext ctx, int x, int y, int z) {
            BlockState fromState = ctx.get(x, y, z);      // block at feet (where we place)
            Block from = fromState.getBlock();
            BlockState fromDown = ctx.get(x, y - 1, z);   // block below feet
            Block fromDownBlock = fromDown.getBlock();

            // Baritone: can't pillar from ladder/vine below
            if (fromDownBlock instanceof LadderBlock || fromDownBlock instanceof VineBlock) {
                return ActionCosts.COST_INF;
            }

            // Baritone: can't pillar from bottom slab (can't jump high enough)
            if (MovementHelper.isBottomSlab(fromDown)) {
                return ActionCosts.COST_INF;
            }

            // Baritone: can't pillar from liquid
            if (MovementHelper.isLiquid(fromState) || MovementHelper.isLiquid(fromDown)) {
                return ActionCosts.COST_INF;
            }

            // Baritone: costOfPlacingAt — only checks inventory, NOT whether position is solid
            if (!ctx.hasThrowawayBlock) return ActionCosts.COST_INF;
            double placeCost = ActionCosts.PLACE_ONE_BLOCK_COST;
            // Baritone: +0.1 tiebreaker penalty when pillar-on-air (consecutive pillars)
            if (fromDown.isAir()) {
                placeCost += 0.1;
            }

            // Baritone: check block at y+2 (new head space)
            BlockState toBreak = ctx.get(x, y + 2, z);
            double hardness = MovementHelper.getMiningDurationTicks(ctx, x, y + 2, z, toBreak, true);
            if (hardness >= ActionCosts.COST_INF) return ActionCosts.COST_INF;

            // Baritone: falling block check at y+3 when y+2 needs mining
            if (hardness != 0) {
                BlockState above = ctx.get(x, y + 3, z);
                if (above.getBlock() instanceof FallingBlock) {
                    BlockState srcUp = ctx.get(x, y + 1, z);
                    if (!(toBreak.getBlock() instanceof FallingBlock)
                            || !(srcUp.getBlock() instanceof FallingBlock)) {
                        return ActionCosts.COST_INF;
                    }
                }
            }

            return ActionCosts.JUMP_ONE_BLOCK_COST + placeCost + ctx.jumpPenalty + hardness;
        }
    }
}
