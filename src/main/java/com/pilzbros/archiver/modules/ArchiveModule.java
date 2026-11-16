package com.pilzbros.archiver.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * The PilzBros Fly-Assisted Archiver is a Meteor Client addon that handles the flight navigation to (hopefully) have the
 * player visit every chunk on a server for the purposes of archival. With the assistance of other tools that will download
 * visited chunks, the goal is to create a snapshot and preserve a world for archival and community enjoyment.
 *
 * When enabled in the Meteor client by the player, this client will automatically fly the player in a spiral pattern
 * moving outward from their current location (hopefully the center of the world). It will travel the player on a path
 * that will, in combination with the render distance, cover all chunks on a world.
 *
 * This was originally designed for the archival of MinecraftOnline.com. With the server being on 1.12, client hacks
 * like boat fly still function. This addon made it possible for me to fly in a spiral pattern, visiting all the chunks
 * on the world to create the first full offline copy of the 10+ year old map. If you're looking to archive a more modern
 * server with better anti-cheat protections, you may find Baritone useful for it's Elytra auto flight.
 *
 * Recommendations:
 * - Meteor
 *
 * To Begin Archiving:
 * 1. Set up your world downloading tool, point it to your destination server, and connect your client (with Meteor client)
 * to it.
 *
 * 2. Navigate to the middle most point of the world you wish to download.
 *
 * See {@link AbstractFlightArchiveModule} for the anti-cheat evasion, terrain following, landing phase, stuck
 * recovery, and boat support this module shares with {@link ZigZagArchiveModule} - this class only owns the
 * spiral-specific path planning (growing leg length, always turning the same way).
 */
public class ArchiveModule extends AbstractFlightArchiveModule {
    // All distances below are real, linear chunk counts measured along the axis of travel. (Earlier versions used
    // ChunkPos#distanceSquared / 2, which is (leg length)^2 / 2 - that made the spiral's rings creep closer together
    // as it grew, so the modifier had to be constantly recalibrated.) The setting names were changed along with the
    // units so that values persisted in the old squared units aren't silently reinterpreted.
    private final Setting<Integer> startingDistanceSetting = sgGeneral.add(new IntSetting.Builder()
        .name("Starting Leg Chunks")
        .description("Base length, in chunks, of the first leg of the spiral (the per-leg increase is added on top).")
        .defaultValue(8)
        .min(1)
        .sliderMax(64)
        .build()
    );

    private final Setting<Integer> ongoingDistanceModifierSetting = sgGeneral.add(new IntSetting.Builder()
        .name("Chunks Added Per Leg")
        .description("How many chunks longer each leg is than the previous one. The gap between parallel passes is twice this, so set it a little under the server's view distance for slight overlap.")
        .defaultValue(8)
        .min(1)
        .sliderMax(32)
        .build()
    );

    private long traveledChunkDistance = startingDistanceSetting.get();

    public ArchiveModule() {
        super("archiver-module", "Automatic flying in a spiral to archive the entire world.", "PilzBros Archiver >> ");
    }

    /**
     * Resets archive progress and checkpoint locations. This can be used when you need to manually resume by setting
     * lengths yourself.
     */
    public void resetThings() {
        // A stale "false" here (persisted from an earlier session) would otherwise make every tick bail out at
        // the very first check regardless of anything else this reset does - re-force it on.
        archiveEnabled.set(true);

        LocalPlayer player = Minecraft.getInstance().player;
        chunkPosAtLastTurn = player.chunkPosition();
        lastPlayerDictatedDirection = player.getMotionDirection();
        tickCounter = 0;
        resetLandingState();
    }

    /**
     * When called, this manually sets the chunk distance to the distance between the player's current chunk and the
     * lastChunkPos location. This is used when manually resuming an archive job.
     */
    public void manuallySetChunkDistance() {
        // The direction of the leg being measured isn't known here, so take the larger of the two axis offsets -
        // for a roughly straight, axis-aligned leg that's its length, ignoring any sideways wander.
        ChunkPos from = chunkPosAtLastTurn;
        ChunkPos to = Minecraft.getInstance().player.chunkPosition();
        setTraveledChunkDistance(Math.max(Math.abs(to.x() - from.x()), Math.abs(to.z() - from.z())));
    }

    /**
     * Linear distance in chunks between two positions, measured only along the axis of the given direction of
     * travel. Legs are axis-aligned, so this is exactly the leg length, and sideways drift doesn't inflate it.
     */
    private static int chunksAlongAxis(ChunkPos from, ChunkPos to, Direction direction) {
        return direction.getAxis() == Direction.Axis.X
            ? Math.abs(to.x() - from.x())
            : Math.abs(to.z() - from.z());
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

        if (lastPlayer == null) {
            lastPlayer = player;
        }

        if (chunkPosAtLastTurn == null) {
            chunkPosAtLastTurn = player.chunkPosition();
        }

        if (lastPlayerDictatedDirection == null) {
            lastPlayerDictatedDirection = player.getMotionDirection();
        }

        if (!lastPlayer.equals(Minecraft.getInstance().player)) {
            // We need our helper module to toggle us on and off.
            return;
        }

        // Riding a boat is a distinct movement mode: we steer and set horizontal thrust directly on the boat,
        // but altitude is Entity Control's job (its "Flight" setting, the boat equivalent of the standalone
        // Flight module we rely on when flying free), so none of the player-velocity-based logic below -
        // landing phase, terrain following, stuck recovery - applies while boating.
        AbstractBoat boat = player.getVehicle() instanceof AbstractBoat riddenBoat ? riddenBoat : null;

        if (boat == null && (player.isInWater() || player.isInLava() || player.onClimbable())) {
            // The player needs to be flying in order for us to be able to proceed with the movement handling.
            // Abandon any landing in progress rather than leaving the state machine wedged mid-descent - this is
            // what happens when a landing interval comes due over an ocean.
            if (landingState != LandingState.IDLE) {
                LOG.info(LOG_PREFIX + "Landing phase: aborted, the player ended up in water/lava or on a climbable.");
                endLandingPhase();
            }

            return;
        }

        if (boat == null && !anticheatEvasionEnabled.get() && player.onGround())
            // Without evasion we only ever drive movement while airborne. With evasion on we deliberately keep
            // ticking while grounded, because the landing phase needs to run its hold-and-climb-out on the ground.
            return;

        tickCounter++;

        // -------------------------------------------------------------------
        // Anti-cheat evasion: landing-phase state machine
        // -------------------------------------------------------------------
        // Periodically force a real ground contact, which resets the violation
        // accumulators in every anti-cheat we looked at. This runs before the
        // spiral navigation so descend/hold/climb take priority over movement.
        // Boats can't "land" this way without fighting Entity Control for altitude, so this is skipped entirely
        // while riding one.
        // -------------------------------------------------------------------
        if (boat == null && anticheatEvasionEnabled.get() && handleLandingPhase(player)) {
            return;
        }

        // We need to keep track of how many chunks we're traveling since the last time we turned. We use this to determine
        // when it's time for us to turn again.
        int distanceTraveledSinceLastTurn = chunksAlongAxis(chunkPosAtLastTurn, player.chunkPosition(), lastPlayerDictatedDirection);

        // Determine how far we still need to go before we've reached the point when we can turn.
        long distanceYetToGo = (traveledChunkDistance + ongoingDistanceModifierSetting.get()) - distanceTraveledSinceLastTurn;

        // Jitter the effective turn distance so the spiral legs aren't perfectly periodic - fixed cadences are
        // exactly what the pattern detectors look for.
        if (anticheatEvasionEnabled.get() && jitterPercent.get() > 0) {
            double jitterFactor = 1.0 + (jitterRng.nextDouble() * 2.0 - 1.0) * (jitterPercent.get() / 100.0);
            distanceYetToGo = (long) (distanceYetToGo * jitterFactor);
        }

        if (distanceYetToGo >= 0) {
            // Move the player in the same direction.
            Direction directionForMovement = lastPlayerDictatedDirection;

             if (playerYawLockSetting.get()) {
                 // Lock the player's yaw to the direction we _should_ be heading. This can correct for mistakes when you
                 // move your mouse across the screen. When disabled, you can get yourself off course real fast.
                 setPlayerYawTowardsDirection(directionForMovement);
             }

            // Now we can move! Free-flying, we drive the player's own velocity directly, right here. Riding a
            // boat, we can't safely do the same from here - see onEntityMove - so we just flag that thrust is
            // due and let that handler apply it the next time the boat actually moves.
            if (boat != null) {
                boatShouldThrustThisTick = true;
            } else {
                movePlayerDirection(directionForMovement, player);
            }

             if (distanceYetToGo % 100 == 0) {
                 LOG.info(LOG_PREFIX + "Player is moving in direction " + player.getMotionDirection().name() + " has traveled " + distanceTraveledSinceLastTurn + " chunks since last turn, needs to travel an additional " + distanceYetToGo + " more before turning.");
             }

            return;
        }

        // We've moved as far as we need to in this direction, it's time to turn.
        if (speedSlowDownTicks-- > 0) {
            // They're moving too fast for us to turn without losing momentum into the next turn. Let's wait until they stop.
            return;
        }

        // Reset the slowdown speed ticks remaining to the setting for the next time we're in velocity cooldown.
        speedSlowDownTicks = slowdownTicksSetting.get();

        // Record how long the leg we just finished actually was; the next leg will be this plus the per-leg increase.
        // This must be measured before turning, because setPlayerYawTowardsDirection overwrites
        // lastPlayerDictatedDirection, which tells us which axis the finished leg ran along.
        setTraveledChunkDistance(chunksAlongAxis(chunkPosAtLastTurn, player.chunkPosition(), lastPlayerDictatedDirection));
        chunkPosAtLastTurn = player.chunkPosition();

        // It's time to turn!
        LOG.info(LOG_PREFIX + "Time to turn! Player was traveling " + player.getMotionDirection().name() + ", turning to now go " + getDirectionToTurnFromCurrentDirection(player.getMotionDirection()).name());
        setPlayerYawTowardsDirection(getDirectionToTurnFromCurrentDirection(player.getMotionDirection()));

        // The landing cadence is measured against chunkPosAtLastTurn, which we just rebased, so its bookkeeping
        // has to start over too.
        chunkDistanceAtLastLanding = 0;
    }

    /**
     * Sets the chunk distance attribute that dictates how far the next leg of the pass should go before turning.
     */
    private void setTraveledChunkDistance(long distance) {
        traveledChunkDistance = distance;
        LOG.info("New chunk distance target = " + traveledChunkDistance);
    }

    /**
     * Determines the direction the player should be rotated after completing the segment of the supplied direction.
     * The spiral always turns the same way (clockwise), which is what makes it spiral outward rather than
     * zig-zagging back and forth.
     */
    public Direction getDirectionToTurnFromCurrentDirection(Direction direction) {
        return turnClockwise(direction);
    }
}
