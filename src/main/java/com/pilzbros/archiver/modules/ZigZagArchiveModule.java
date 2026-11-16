package com.pilzbros.archiver.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.border.WorldBorder;

/**
 * A lawnmower/boustrophedon coverage pattern, as an alternative to {@link ArchiveModule}'s outward spiral: fly a
 * straight line until hitting the world border, hop a short configurable distance perpendicular to it, then fly
 * back the opposite way until the far border, hop again, and repeat - sweeping the whole map in rows rather than
 * spiraling out from the center.
 *
 * The connector between rows always turns the same way twice in a row (right-right, then left-left, alternating
 * each time) - which is exactly what keeps the perpendicular "hop" direction constant from one row to the next.
 * Start it flying north from the bottom-left (southwest) corner of the map, for example, and the first connector
 * turns right (east), hops, then turns right again (south) - covering the map corner-to-corner in one pass. When
 * the hop direction eventually reaches its own world border, it reverses so the sweep can cover the far side too
 * - tracked against wherever the hop axis started, so a corner start (one side already at the edge) finishes
 * after a single pass, while a center start correctly needs both sides visited.
 *
 * For servers without a usable world border, "Row Length Mode: Set Distance" replaces the border with a known
 * area: start in a corner facing along one edge, set Row Distance to the length of that edge, Sweep Width to the
 * other edge (or 0 for a square), and Hop Side to whichever way points into the map. Rows then run between two
 * fixed lines anchored at the start position, and the sweep finishes after the row at the far side. The world
 * border, if there is one, is still respected as a hard stop.
 *
 * See {@link AbstractFlightArchiveModule} for the anti-cheat evasion, terrain following, landing phase, stuck
 * recovery, and boat support this module shares with {@link ArchiveModule} - this class only owns the zig-zag
 * specific path planning (world border detection, the row/hop state machine, and alternating turns).
 */
public class ZigZagArchiveModule extends AbstractFlightArchiveModule {
    /** What ends each long row: the world border, or a fixed number of blocks. */
    public enum RowLengthMode {
        WORLD_BORDER("World Border"),
        SET_DISTANCE("Set Distance");

        private final String title;

        RowLengthMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Which way the hops between rows go, relative to the direction the first row is flown. */
    public enum HopSide {
        RIGHT("Right"),
        LEFT("Left");

        private final String title;

        HopSide(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final Setting<RowLengthMode> rowLengthModeSetting = sgGeneral.add(new EnumSetting.Builder<RowLengthMode>()
        .name("Row Length Mode")
        .description("World Border: fly each row until the world border. Set Distance: fly each row a fixed number of blocks, for servers without a usable world border.")
        .defaultValue(RowLengthMode.WORLD_BORDER)
        .build()
    );

    private final Setting<Integer> rowDistanceSetting = sgGeneral.add(new IntSetting.Builder()
        .name("Row Distance")
        .description("Length of each row in blocks. Rows are anchored to where you start, so they all begin and end on the same two lines.")
        .defaultValue(10000)
        .min(1)
        .noSlider()
        .visible(() -> rowLengthModeSetting.get() == RowLengthMode.SET_DISTANCE)
        .build()
    );

    private final Setting<Integer> sweepWidthSetting = sgGeneral.add(new IntSetting.Builder()
        .name("Sweep Width")
        .description("How far, in blocks, to sweep in the hop direction before finishing (the distance between the first and last row). 0 = same as Row Distance.")
        .defaultValue(0)
        .min(0)
        .noSlider()
        .visible(() -> rowLengthModeSetting.get() == RowLengthMode.SET_DISTANCE)
        .build()
    );

    private final Setting<HopSide> hopSideSetting = sgGeneral.add(new EnumSetting.Builder<HopSide>()
        .name("Hop Side")
        .description("Which way to hop between rows, relative to the direction you start flying. E.g. starting in the bottom-right (south-east) corner facing north, hops need to go Left (west).")
        .defaultValue(HopSide.RIGHT)
        .build()
    );

    private final Setting<Integer> stepDistanceSetting = sgGeneral.add(new IntSetting.Builder()
        .name("Step Distance")
        .description("How far (in blocks) to fly perpendicular to the border before turning back into the next row.")
        .defaultValue(128)
        .min(1)
        .sliderMax(1000)
        .build()
    );

    private final Setting<Integer> borderApproachMarginSetting = sgGeneral.add(new IntSetting.Builder()
        .name("Border Approach Margin")
        .description("Turn this many blocks short of the actual world border, rather than flying right up to it.")
        .defaultValue(16)
        .min(0)
        .sliderMax(256)
        .build()
    );

    /**
     * Row/hop state machine:
     *   FLYING              -> straight-line flight in {@code rowDirection} until the world border
     *   PRE_STEP_COOLDOWN   -> momentum-settling pause, then turn to face {@code stepDirection}
     *   STEPPING            -> straight-line flight in {@code stepDirection} for the configured step distance
     *   POST_STEP_COOLDOWN  -> momentum-settling pause, then turn to the reversed {@code rowDirection}
     *   DONE                -> full map coverage complete, holding position until reset (module toggle or
     *                          {@code .archive reset})
     */
    private enum Phase { FLYING, PRE_STEP_COOLDOWN, STEPPING, POST_STEP_COOLDOWN, DONE }
    private Phase phase = Phase.FLYING;

    /** Direction of the current long, border-to-border leg. Reverses every time a row/hop connector completes. */
    private Direction rowDirection;

    /**
     * Direction of the short perpendicular hop between rows. Stays constant across connectors (so rows steadily
     * march one way across the map) until it hits its own world border, at which point it reverses - covering
     * the far half of the map from wherever the module was started instead of just one half.
     */
    private Direction stepDirection;

    /** Player position when STEPPING began, to measure how far we've hopped so far. */
    private double stepStartX;
    private double stepStartZ;

    /**
     * The hop direction the module was started with, fixed for the whole run - used as a stable reference point
     * for coverage tracking even though {@code stepDirection} itself flips back and forth over time.
     */
    private Direction initialStepDirection;

    /** Whether the hop axis border in {@code initialStepDirection} has been reached at some point. */
    private boolean initialSideCovered = false;

    /** Whether the hop axis border opposite {@code initialStepDirection} has been reached at some point. */
    private boolean oppositeSideCovered = false;

    /**
     * Set Distance mode only: the direction of the very first row, and the player's coordinate along the row axis
     * and the hop axis when the sweep started. Rows end at absolute coordinates derived from these (the start line,
     * or the start line + Row Distance) rather than "Row Distance from wherever this row began", so momentum
     * overshoot at each turn can't accumulate and shift the rows over a long run.
     */
    private Direction initialRowDirection;
    private double rowAxisOrigin;
    private double hopAxisOrigin;

    /** Set Distance mode only: the sweep width has been reached, so the current row is the last one. */
    private boolean onFinalRow = false;

    public ZigZagArchiveModule() {
        super("zigzag-archiver-module", "Automatic flying in a lawnmower pattern to archive the entire world.", "PilzBros ZigZag Archiver >> ");
    }

    /**
     * Toggling the module back on - whether it's a deliberate pause/resume or it ran to DONE from finishing full
     * map coverage - should mean "sweep again", not silently sit there because the old phase/direction are
     * still whatever they were last time. Unlike the spiral module, which is built to resume a long-running,
     * possibly-reconnected session in place, a zig-zag sweep either hasn't started or has nothing left to do,
     * so there's no in-progress state worth preserving across a toggle.
     */
    @Override
    public void onActivate() {
        resetThings();
    }

    /**
     * Resets the sweep back to a fresh start: forgets the current phase/direction/coverage-tracking so the next
     * tick picks a brand new starting direction and hop side exactly like a freshly-enabled module would. Also
     * available via {@code .archive reset}.
     */
    public void resetThings() {
        // A stale "false" here (persisted from an earlier session, or left over from before this setting even
        // existed) would otherwise make every tick bail out at the very first check, no matter what else this
        // reset does - re-force it on so a reset always means "actually try again."
        archiveEnabled.set(true);
        phase = Phase.FLYING;
        rowDirection = null;
        stepDirection = null;
        initialStepDirection = null;
        initialSideCovered = false;
        oppositeSideCovered = false;
        initialRowDirection = null;
        onFinalRow = false;
        chunkPosAtLastTurn = null;
        tickCounter = 0;
        speedSlowDownTicks = slowdownTicksSetting.get();
        resetLandingState();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;

        // Reset unconditionally before any early return below, so a tick that bails out for any reason (module
        // disabled, turning, in water, etc.) never leaves a stale "keep thrusting" signal for onEntityMove to
        // act on later.
        boatShouldThrustThisTick = false;

        if (!archiveEnabled.get()) {
            return;
        }

        if (lastPlayer != null && !lastPlayer.equals(player)) {
            // The player reference changed under us - a reconnect, respawn, or dimension change - so every bit
            // of progress tracking (direction, phase, chunk positions) is keyed to a world/session that's gone.
            // The spiral module relies on HelperModule to detect this externally and toggle it back on with a
            // fresh reference; this module isn't wired to that, so it has to handle it directly here. Reset
            // rather than just returning forever, otherwise this comparison would never match again and the
            // module would look permanently stuck even after being disabled and re-enabled.
            LOG.info(LOG_PREFIX + "Player reference changed (reconnect/respawn/dimension change) - resetting for a fresh sweep.");
            resetThings();
        }

        lastPlayer = player;

        if (rowDirection == null) {
            // Take over whatever direction the player was already flying, same as the spiral module does -
            // predictable, and means you aim it just by looking where you want the first row to go. The first
            // connector turns right, matching the classic lawnmower pattern. Starting in a corner facing along
            // one edge (e.g. bottom-left facing north) makes the first hop turn away from that edge, toward the
            // interior, which is exactly what you want.
            rowDirection = player.getMotionDirection();
            stepDirection = hopSideSetting.get() == HopSide.LEFT
                ? turnCounterClockwise(rowDirection)
                : turnClockwise(rowDirection);
            initialStepDirection = stepDirection;

            initialRowDirection = rowDirection;
            rowAxisOrigin = axisCoordinate(player, rowDirection);
            hopAxisOrigin = axisCoordinate(player, stepDirection);

            if (isSetDistanceMode()) {
                LOG.info(LOG_PREFIX + "Set Distance sweep: rows of " + rowDistanceSetting.get() + " blocks starting " + rowDirection + ", hopping " + stepDirection + " for " + effectiveSweepWidth() + " blocks.");
            }

            // If we're already starting at one extreme of the hop axis - e.g. flying this from a corner of the
            // map rather than the center - that extreme is already covered, so a single pass to the far border
            // completes coverage instead of assuming a center start that needs both extremes visited.
            initialSideCovered = hasReachedWorldBorder(player, initialStepDirection);
            oppositeSideCovered = hasReachedWorldBorder(player, initialStepDirection.getOpposite());
        }

        if (chunkPosAtLastTurn == null) {
            chunkPosAtLastTurn = player.chunkPosition();
        }

        // Riding a boat is a distinct movement mode: we steer and set horizontal thrust directly on the boat,
        // but altitude is Entity Control's job, so none of the player-velocity-based logic below - landing
        // phase, terrain following, stuck recovery - applies while boating.
        AbstractBoat boat = player.getVehicle() instanceof AbstractBoat riddenBoat ? riddenBoat : null;

        if (boat == null && (player.isInWater() || player.isInLava() || player.onClimbable())) {
            if (landingState != LandingState.IDLE) {
                LOG.info(LOG_PREFIX + "Landing phase: aborted, the player ended up in water/lava or on a climbable.");
                endLandingPhase();
            }

            return;
        }

        if (boat == null && !anticheatEvasionEnabled.get() && player.onGround()) {
            return;
        }

        tickCounter++;

        if (boat == null && anticheatEvasionEnabled.get() && handleLandingPhase(player)) {
            return;
        }

        switch (phase) {
            case FLYING -> tickFlying(player, boat);
            case PRE_STEP_COOLDOWN -> tickPreStepCooldown(player);
            case STEPPING -> tickStepping(player, boat);
            case POST_STEP_COOLDOWN -> tickPostStepCooldown(player);
            case DONE -> { /* Full coverage complete - hold position until reset (module toggle or .archive reset). */ }
        }
    }

    /** Straight-line flight in {@code rowDirection} until the end of the row (world border or set distance). */
    private void tickFlying(LocalPlayer player, AbstractBoat boat) {
        boolean reachedBorder = hasReachedWorldBorder(player, rowDirection);

        if (reachedBorder || hasReachedSetRowEnd(player)) {
            if (isSetDistanceMode() && onFinalRow) {
                LOG.info(LOG_PREFIX + "Finished the last row - Set Distance sweep complete. Holding position; toggle the module or run .archive reset to sweep again.");
                phase = Phase.DONE;
                return;
            }

            LOG.info(LOG_PREFIX + "Reached the " + (reachedBorder ? "world border" : "end of the row") + " traveling " + rowDirection + " - turning to hop " + stepDirection + ".");
            phase = Phase.PRE_STEP_COOLDOWN;
            speedSlowDownTicks = slowdownTicksSetting.get();
            return;
        }

        if (playerYawLockSetting.get()) {
            setPlayerYawTowardsDirection(rowDirection);
        }

        if (boat != null) {
            boatShouldThrustThisTick = true;
        } else {
            movePlayerDirection(rowDirection, player);
        }
    }

    /** Momentum-settling pause after hitting the border, then the first turn of the connector. */
    private void tickPreStepCooldown(LocalPlayer player) {
        if (speedSlowDownTicks-- > 0) {
            // Let momentum settle before pivoting, same reasoning as the spiral's turn cooldown - an instant
            // direction reversal is its own signature to anti-cheats that re-simulate physics.
            return;
        }

        speedSlowDownTicks = slowdownTicksSetting.get();

        setPlayerYawTowardsDirection(stepDirection);
        stepStartX = player.getX();
        stepStartZ = player.getZ();

        // The hop is a new leg in its own right, so the landing cadence (which measures progress against
        // chunkPosAtLastTurn) needs to start counting from here rather than from the start of the row we just
        // finished.
        chunkPosAtLastTurn = player.chunkPosition();
        chunkDistanceAtLastLanding = 0;

        phase = Phase.STEPPING;
    }

    /** Straight-line flight in {@code stepDirection} for the configured step distance (or until its own border). */
    private void tickStepping(LocalPlayer player, AbstractBoat boat) {
        double distanceStepped = switch (stepDirection) {
            case NORTH, SOUTH -> Math.abs(player.getZ() - stepStartZ);
            case EAST, WEST -> Math.abs(player.getX() - stepStartX);
            default -> 0.0;
        };

        double effectiveStepDistance = stepDistanceSetting.get();

        // Jitter the step distance so rows aren't spaced with perfectly identical periodicity - the same
        // pattern-detector rationale as the spiral's leg-length jitter.
        if (anticheatEvasionEnabled.get() && jitterPercent.get() > 0) {
            double jitterFactor = 1.0 + (jitterRng.nextDouble() * 2.0 - 1.0) * (jitterPercent.get() / 100.0);
            effectiveStepDistance *= jitterFactor;
        }

        boolean hitStepBorder = hasReachedWorldBorder(player, stepDirection);

        if (isSetDistanceMode()) {
            // The sweep is a single pass across a known width from a corner, so there's no reversing to cover a
            // far side: once the hop has carried us out to the sweep width (or a border got in the way), stop the
            // hop there and make the next row the final one.
            double sweptSoFar = Math.abs(axisCoordinate(player, stepDirection) - hopAxisOrigin);

            if (hitStepBorder || sweptSoFar >= effectiveSweepWidth()) {
                LOG.info(LOG_PREFIX + (hitStepBorder ? "Hit the world border" : "Reached the sweep width") + " while hopping " + stepDirection + " - flying one final row.");
                onFinalRow = true;
                phase = Phase.POST_STEP_COOLDOWN;
                speedSlowDownTicks = slowdownTicksSetting.get();
                return;
            }
        } else if (hitStepBorder) {
            // Mark whichever fixed extreme we just reached as covered, tracked against the direction the module
            // was started with (not the current, possibly-since-flipped stepDirection) so this is correct
            // regardless of whether we started at a map edge (one extreme already covered, one hit finishes it)
            // or in the middle (neither covered yet, needs both extremes visited).
            if (stepDirection == initialStepDirection) {
                initialSideCovered = true;
            } else {
                oppositeSideCovered = true;
            }

            if (initialSideCovered && oppositeSideCovered) {
                LOG.info(LOG_PREFIX + "Reached the world border on both sides of the sweep - full map coverage complete. Holding position; toggle the module or run .archive reset to sweep again.");
                phase = Phase.DONE;
                return;
            }

            // Otherwise, the perpendicular sweep just ran out of world on this side - reverse it so future hops
            // head back the other way, toward the side not yet covered.
            LOG.info(LOG_PREFIX + "Hit the world border while hopping " + stepDirection + " - reversing the sweep direction.");
            stepDirection = stepDirection.getOpposite();
        }

        if (!hitStepBorder && distanceStepped < effectiveStepDistance) {
            if (playerYawLockSetting.get()) {
                setPlayerYawTowardsDirection(stepDirection);
            }

            if (boat != null) {
                boatShouldThrustThisTick = true;
            } else {
                movePlayerDirection(stepDirection, player);
            }

            return;
        }

        phase = Phase.POST_STEP_COOLDOWN;
        speedSlowDownTicks = slowdownTicksSetting.get();
    }

    /** Momentum-settling pause after the hop, then the second turn of the connector - resuming the row, reversed. */
    private void tickPostStepCooldown(LocalPlayer player) {
        if (speedSlowDownTicks-- > 0) {
            return;
        }

        speedSlowDownTicks = slowdownTicksSetting.get();

        rowDirection = rowDirection.getOpposite();
        LOG.info(LOG_PREFIX + "Resuming the sweep, now traveling " + rowDirection + ".");
        setPlayerYawTowardsDirection(rowDirection);

        chunkPosAtLastTurn = player.chunkPosition();
        chunkDistanceAtLastLanding = 0;

        phase = Phase.FLYING;
    }

    private boolean isSetDistanceMode() {
        return rowLengthModeSetting.get() == RowLengthMode.SET_DISTANCE;
    }

    /** Sweep width in blocks; 0 in the setting means "same as Row Distance" (a square area). */
    private int effectiveSweepWidth() {
        int width = sweepWidthSetting.get();
        return width > 0 ? width : rowDistanceSetting.get();
    }

    /** The player's coordinate along the axis of the given horizontal direction (X for east/west, Z for north/south). */
    private static double axisCoordinate(LocalPlayer player, Direction direction) {
        return direction.getAxis() == Direction.Axis.X ? player.getX() : player.getZ();
    }

    /**
     * Set Distance mode only: whether the current row has reached its end line. Rows flown in the starting
     * direction end Row Distance blocks past the start line, and rows flown back end at the start line itself.
     */
    private boolean hasReachedSetRowEnd(LocalPlayer player) {
        if (!isSetDistanceMode() || initialRowDirection == null) return false;

        int outwardSign = initialRowDirection.getStepX() + initialRowDirection.getStepZ();
        double endLine = rowDirection == initialRowDirection
            ? rowAxisOrigin + outwardSign * (double) rowDistanceSetting.get()
            : rowAxisOrigin;

        int travelSign = rowDirection.getStepX() + rowDirection.getStepZ();
        return (axisCoordinate(player, rowDirection) - endLine) * travelSign >= 0;
    }

    /**
     * Whether the player is within {@code borderApproachMargin} blocks of the world border, in the given
     * direction of travel.
     */
    private boolean hasReachedWorldBorder(LocalPlayer player, Direction direction) {
        if (mc.level == null) return false;

        WorldBorder border = mc.level.getWorldBorder();
        double margin = borderApproachMarginSetting.get();

        return switch (direction) {
            case NORTH -> player.getZ() <= border.getMinZ() + margin;
            case SOUTH -> player.getZ() >= border.getMaxZ() - margin;
            case EAST -> player.getX() >= border.getMaxX() - margin;
            case WEST -> player.getX() <= border.getMinX() + margin;
            default -> false;
        };
    }
}
