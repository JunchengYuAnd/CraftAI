package com.playstudio.bridgemod.pathfinding.moves;

import com.playstudio.bridgemod.pathfinding.ActionCosts;
import com.playstudio.bridgemod.pathfinding.CalculationContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * All possible single-step movements in the A* search.
 * Ported from Baritone's Moves enum.
 *
 * Phase 3B: 16 movement types (4 traverse + 4 ascend + 4 descend + 4 diagonal).
 * Phase 3C will add: DOWNWARD, PILLAR, PARKOUR.
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
                // This would be a bridge (place block below dest) - Phase 3C
                return ActionCosts.COST_INF;
            }
        }
    }

    /**
     * Ascend: jump up 1 block.
     * Ported from Baritone's MovementAscend.cost().
     * Omits: block placement, falling block check, bottom slab jump restriction.
     */
    static final class MovementAscend {
        static double cost(CalculationContext ctx, int x, int y, int z, int destX, int destZ) {
            BlockState toBreak1 = ctx.get(destX, y + 1, destZ);  // dest head
            BlockState toBreak2 = ctx.get(destX, y + 2, destZ);  // dest head+1
            BlockState srcUp2 = ctx.get(x, y + 2, z);             // src head+1 (need clearance to jump)
            BlockState destOn = ctx.get(destX, y, destZ);          // block we jump onto

            // Must be able to stand on the destination block
            if (!MovementHelper.canWalkOn(ctx, destX, y, destZ, destOn)) {
                return ActionCosts.COST_INF;
            }

            // Check mining costs for the 3 blocks that need to be clear
            double hardness1 = MovementHelper.getMiningDurationTicks(ctx, destX, y + 1, destZ, toBreak1, false);
            if (hardness1 >= ActionCosts.COST_INF) return ActionCosts.COST_INF;
            double hardness2 = MovementHelper.getMiningDurationTicks(ctx, destX, y + 2, destZ, toBreak2, true);
            if (hardness2 >= ActionCosts.COST_INF) return ActionCosts.COST_INF;
            double hardness3 = MovementHelper.getMiningDurationTicks(ctx, x, y + 2, z, srcUp2, true);
            if (hardness3 >= ActionCosts.COST_INF) return ActionCosts.COST_INF;

            double WC = ActionCosts.WALK_ONE_BLOCK_COST;
            BlockState srcDown = ctx.get(x, y - 1, z);

            if (srcDown.getBlock() == Blocks.SOUL_SAND) {
                WC += (ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST - ActionCosts.WALK_ONE_BLOCK_COST) / 2;
            }
            // Jump up 1 block (Baritone uses JUMP_ONE_BLOCK_COST derived from physics)
            WC += ActionCosts.JUMP_ONE_BLOCK_COST;

            if (srcDown.getBlock() instanceof LadderBlock || srcDown.getBlock() instanceof VineBlock) {
                hardness1 *= 5;
                hardness2 *= 5;
                hardness3 *= 5;
            }

            return WC + hardness1 + hardness2 + hardness3;
        }
    }

    /**
     * Descend: walk off an edge and fall 1+ blocks.
     * Ported from Baritone's MovementDescend.cost().
     * Uses MoveResult because the landing Y is dynamic.
     * Omits: vine/ladder catch during fall, water bucket landing.
     */
    static final class MovementDescend {
        static void cost(CalculationContext ctx, int x, int y, int z, int destX, int destZ, MoveResult result) {
            // Baritone: check 3 blocks in the forward column: y-1 (landing feet), y (step-off feet), y+1 (step-off head)
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

            // 1-block descent check
            if (MovementHelper.canWalkOn(ctx, destX, y - 2, destZ)) {
                // Check space at landing (y-1 feet, y head)
                if (hardness0 == 0 && hardness1 == 0 && hardness2 == 0) {
                    // Check for lava
                    if (MovementHelper.isLava(ctx, destX, y - 2, destZ)) return;

                    double totalCost = ActionCosts.WALK_OFF_BLOCK_COST
                            + Math.max(ActionCosts.FALL_N_BLOCKS_COST[1], ActionCosts.CENTER_AFTER_FALL_COST)
                            + hardness0 + hardness1 + hardness2;

                    // Soul sand at source
                    if (srcDownBlock == Blocks.SOUL_SAND) {
                        totalCost += (ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST - ActionCosts.WALK_ONE_BLOCK_COST) / 2;
                    }

                    result.x = destX;
                    result.y = y - 1;
                    result.z = destZ;
                    result.cost = totalCost;
                    return;
                }
                return;  // Can't mine in descent
            }

            // Check if (destX, y-2, destZ) blocks further falling
            if (!MovementHelper.canWalkThrough(ctx, destX, y - 2, destZ)) {
                return;  // Blocked, can't fall further
            }

            // Multi-block fall
            if (hardness0 != 0 || hardness1 != 0 || hardness2 != 0) {
                return;  // Can't mine during fall
            }

            // Scan downward
            int maxFall = ctx.maxFallHeightNoWater;
            int minY = Math.max(y - maxFall - 1, ctx.getLevel().getMinBuildHeight());
            for (int landY = y - 3; landY >= minY; landY--) {
                BlockState landOn = ctx.get(destX, landY, destZ);
                if (MovementHelper.canWalkOn(ctx, destX, landY, destZ, landOn)) {
                    // Found landing spot
                    // Check if space at landing is clear
                    if (!MovementHelper.canWalkThrough(ctx, destX, landY + 1, destZ)) return;
                    if (!MovementHelper.canWalkThrough(ctx, destX, landY + 2, destZ)) return;
                    // Check for lava
                    if (MovementHelper.isLava(ctx, destX, landY, destZ)) return;

                    int fallHeight = y - 1 - landY;
                    if (fallHeight > maxFall) return;  // Too high, would take damage

                    double totalCost = ActionCosts.WALK_OFF_BLOCK_COST
                            + ActionCosts.FALL_N_BLOCKS_COST[fallHeight]
                            + ActionCosts.CENTER_AFTER_FALL_COST;

                    if (srcDownBlock == Blocks.SOUL_SAND) {
                        totalCost += (ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST - ActionCosts.WALK_ONE_BLOCK_COST) / 2;
                    }

                    result.x = destX;
                    result.y = landY + 1;
                    result.z = destZ;
                    result.cost = totalCost;
                    return;
                }

                // Check if we can keep falling through this block
                if (!MovementHelper.canWalkThrough(ctx, destX, landY, destZ, landOn)) {
                    return;  // Blocked, can't fall further
                }
            }
            // Fell too far or into void - no valid landing
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
                result.cost = multiplier * ActionCosts.SQRT_2 + ActionCosts.JUMP_ONE_BLOCK_COST;
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
}
