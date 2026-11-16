package com.pilzbros.archiver.modules;

import com.pilzbros.archiver.ArchiverAddon;
import meteordevelopment.meteorclient.events.entity.EntityMoveEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.EntityControl;
import meteordevelopment.meteorclient.systems.modules.movement.Flight;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;
import java.util.StringJoiner;
import java.util.logging.Logger;

/**
 * Shared flight execution logic for the archiver's movement modules (currently the spiral {@link ArchiveModule}
 * and the lawnmower-pattern {@link ZigZagArchiveModule}). Both modules need the exact same "how do I actually
 * move safely" toolkit - anti-cheat evasion, terrain following, the periodic-landing state machine, stuck
 * recovery, and boat support via Entity Control - they just disagree on the path-planning policy (when to turn
 * and which way). This base class owns everything in the first category; each subclass's own {@code onTick}
 * owns only the second, calling into these protected helpers to actually execute movement.
 */
public abstract class AbstractFlightArchiveModule extends Module {
    protected final SettingGroup sgGeneral = settings.getDefaultGroup();
    protected final SettingGroup sgAntiCheat = settings.createGroup("Anti-Cheat Evasion");

    protected final String LOG_PREFIX;
    protected static final Logger LOG = Logger.getLogger("Minecraft");

    public final Setting<Boolean> archiveEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("Enable Archive + Flying")
        .description("Fly to the appropriate height, then enable for the addon to take control and begin flying in the pattern for archiving.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> playerYawLockSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Player Yaw")
        .description("When enabled, each tick will ensure the player's yaw is looking in the correct direction.")
        .defaultValue(true)
        .build()
    );

    protected final Setting<Integer> slowdownTicksSetting = sgGeneral.add(new IntSetting.Builder()
        .name("slowdownTicks")
        .description("The number of ticks to wait for momentum to settle before actually pivoting once it's time to turn.")
        .defaultValue(10)
        .min(0)
        .sliderMax(120)
        .build()
    );

    protected final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Your movement speed while flying. The higher the movement speed, the more likely you are to be kicked.")
        .defaultValue(0.2)
        .min(0.0)
        .sliderMax(10)
        .build()
    );

    /**
     * Separate from {@code speed} on purpose: the same raw velocity added to a boat's deltaMovement each tick
     * translates to a dramatically higher real-world speed than it does for a free-flying player - the vanilla
     * boat isn't fighting the same horizontal drag a flying player is, especially while airborne (which is only
     * ever the case here because Entity Control is keeping it up). A value that feels right for free flight will
     * launch a boat far too fast, so boat and player thrust need their own independently-tuned setting.
     */
    protected final Setting<Double> boatSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("Boat Speed")
        .description("Movement speed while riding a boat. Boats accelerate much more per unit of injected velocity than free flight does - start low and tune up.")
        .defaultValue(0.01)
        .min(0.0)
        .sliderMax(1)
        .build()
    );

    // =======================================================================
    // Anti-Cheat Evasion Settings
    // =======================================================================
    // The following settings come out of a deep dive on GrimAC, NoCheatPlus,
    // Negativity, and Spartan. Each one targets a specific detection signature
    // that all four engines share, so tuning against the intersection of them
    // is the safest bet on a server whose anti-cheat we can't identify.
    // =======================================================================

    /**
     * Master toggle for all anti-cheat evasion logic. When disabled the module
     * behaves exactly like the original archiver (constant altitude, fixed turn
     * distance, no speed cap). Enable this when archiving servers that run a
     * modern anti-cheat such as GrimAC, Spartan, Negativity, or NoCheatPlus.
     */
    protected final Setting<Boolean> anticheatEvasionEnabled = sgAntiCheat.add(new BoolSetting.Builder()
        .name("Enable Anti-Cheat Evasion")
        .description("Activates sine-wave flying, speed caps, periodic landing, and jittered turns to evade movement checks.")
        .defaultValue(true)
        .build()
    );

    /**
     * Hard ceiling on horizontal displacement per tick.
     *
     * Why: All four anti-cheats re-simulate vanilla physics and compare the
     * client's position deltas against predicted velocity. Negativity has an
     * explicit airborne horizontal cap of ~0.85 blocks/tick. NCP uses a friction
     * model where base walk is 0.221 multiplied by state modifiers. GrimAC checks
     * vector offsets against predicted motion. Spartan compares against vanilla
     * terminal-velocity tables.
     *
     * 0.6 leaves a generous margin below Negativity's 0.85 cap while still
     * covering ground quickly - 0.6 b/t is 12 blocks/sec.
     */
    protected final Setting<Double> maxHorizontalSpeed = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Max Horizontal Speed")
        .description("Hard cap on horizontal blocks/tick. Keep <=0.6 for GrimAC/Spartan/NCP safety.")
        .defaultValue(0.6)
        .min(0.01)
        .sliderMax(2.0)
        .build()
    );

    /**
     * Boat equivalent of {@link #maxHorizontalSpeed}, kept as its own setting for the same reason
     * {@link #boatSpeed} is separate from {@link #speed} - the same number means a very different real speed
     * for a boat than for a free-flying player.
     */
    protected final Setting<Double> boatMaxHorizontalSpeed = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Boat Max Horizontal Speed")
        .description("Hard cap on the boat's horizontal blocks/tick. Boats need a much lower cap here than a flying player does for the same real-world speed.")
        .defaultValue(0.03)
        .min(0.001)
        .sliderMax(1.0)
        .build()
    );

    /**
     * Amplitude of the continuous sine-wave altitude oscillation.
     *
     * Why: Sustained zero-Y motion while airborne is the single most reliable
     * detection signature across every anti-cheat studied:
     *   - GrimAC: sub-millimeter vector-offset comparison catches any non-physics motion.
     *   - NCP: absolute jump-height cap (1.35 blocks) and hover envelope catch flat flight.
     *   - Negativity: "not moving Y" fires at 98% confidence on the 2nd consecutive flat tick.
     *   - Spartan: GravitySimulation expects (motion - 0.08) * 0.98 per tick.
     *
     * A small continuous sine wave on Y means we never hold exactly constant
     * altitude for more than a tick. +/-0.04 reads as natural micro-adjustment
     * but is large enough to keep Y velocity non-zero every tick.
     */
    protected final Setting<Double> verticalOscillationAmplitude = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Vertical Oscillation Amplitude")
        .description("+/- blocks of sine-wave Y jitter. 0.02-0.08 recommended. Prevents flat-Y detectors.")
        .defaultValue(0.04)
        .min(0.0)
        .sliderMax(0.2)
        .build()
    );

    /**
     * Period of the sine wave, measured in ticks.
     *
     * Why: A very fast oscillation (e.g. 5 ticks) looks artificial to heuristic
     * detectors like Negativity's OmegaCraftFly bounce detector and Spartan's
     * spam-penalty multiplier. A very slow one (e.g. 200 ticks) allows too many
     * near-flat ticks in a row. 40 ticks (~2 seconds at 20 tps) mimics the small
     * bobbing a real player does while adjusting flight altitude.
     */
    protected final Setting<Integer> verticalOscillationPeriod = sgAntiCheat.add(new IntSetting.Builder()
        .name("Vertical Oscillation Period")
        .description("Ticks per full sine-wave cycle. 30-50 recommended.")
        .defaultValue(40)
        .min(5)
        .sliderMax(200)
        .build()
    );

    /**
     * How many chunks to fly before intentionally descending to the ground.
     *
     * Why: All four anti-cheats accumulate violations over time, and decay is
     * either very slow or nonexistent:
     *   - GrimAC: advantageGained decays x0.999/tick (negligible).
     *   - NCP: survivalFlyVL decays only after 40 clean moves.
     *   - Negativity: warn counters have no decay at all.
     *   - Spartan: accumulated violation time has no explicit decay.
     *
     * "Mostly clean" flight isn't good enough for a run this long, because
     * occasional micro-flags add up permanently. Landing resets every tracker:
     * NCP's decay window, Negativity's fly-air-below and wasFlying counters,
     * Spartan's accumulated violation time, and it gives the client time to
     * catch up on chunk data (GrimAC hard-teleports on unloaded chunks).
     */
    protected final Setting<Integer> landingIntervalChunks = sgAntiCheat.add(new IntSetting.Builder()
        .name("Landing Interval (Chunks)")
        .description("Fly this many chunks, then land for a ground-contact reset.")
        .defaultValue(50)
        .min(5)
        .sliderMax(500)
        .build()
    );

    /**
     * How long to stay on the ground during each landing phase, in ticks.
     *
     * Why: NCP needs 40 clean moves before survivalFlyVL starts decaying.
     * Negativity's grace periods are shorter (1-2s), but Spartan and GrimAC both
     * benefit from a longer baseline of legitimate physics. 100 ticks is 5
     * seconds at 20 tps, comfortably past NCP's window.
     */
    protected final Setting<Integer> landingDurationTicks = sgAntiCheat.add(new IntSetting.Builder()
        .name("Landing Duration (Ticks)")
        .description("How many ticks to remain on ground per landing phase. >=100 recommended for NCP decay.")
        .defaultValue(100)
        .min(20)
        .sliderMax(300)
        .build()
    );

    /**
     * Target altitude above the local terrain surface.
     *
     * Why: Flying at a fixed global Y (e.g. Y=120) means being far above the
     * ground most of the time, which opens several detection paths: long
     * open-air climbs trip Negativity's fly-air-below counter (>6 ticks), large
     * vertical adjustments look like jump-arc deviations to Spartan's
     * GravitySimulation, and clipping unexpected blocks trips Phase/Exploits.
     *
     * Sampling the surface heightmap and flying a fixed offset above it makes
     * vertical motion correlate with hills and valleys, which reads much more
     * like legitimate elytra/boat/pathing behaviour. Set to 0 to disable terrain
     * awareness and hold whatever altitude you manually flew to.
     */
    protected final Setting<Integer> terrainAwareAltitude = sgAntiCheat.add(new IntSetting.Builder()
        .name("Terrain-Aware Altitude")
        .description("Blocks above local surface to fly. 0 disables terrain awareness. 2-4 recommended.")
        .defaultValue(3)
        .min(0)
        .sliderMax(20)
        .build()
    );

    /**
     * Maximum percentage (+/-) to randomly vary the turn distance each leg.
     *
     * Why: Perfectly periodic legs are a signature for heuristic
     * detectors - Negativity's OmegaCraftFly bounce detector looks for repeating
     * patterns, and Spartan applies a spam-penalty multiplier when motion is too
     * regular. +/-10% keeps the pattern from being exactly geometric while staying
     * small enough that chunk coverage has no gaps.
     */
    protected final Setting<Integer> jitterPercent = sgAntiCheat.add(new IntSetting.Builder()
        .name("Jitter Percent")
        .description("+/- % random variation in turn distance. 5-15% recommended to break pattern detectors.")
        .defaultValue(10)
        .min(0)
        .sliderMax(50)
        .build()
    );

    /**
     * Low-frequency phase noise applied to the vertical sine wave.
     *
     * Why: A perfectly periodic sine wave is itself a fingerprint for the same
     * pattern detectors. Slowly drifting the phase means the waveform never
     * repeats exactly:
     *     jitter = sin((phase + noise) * 2pi) * amplitude
     *     noise  = sin(tick * freq) * phaseNoiseStrength
     *
     * A strength of 0.1 is +/-0.1 of a full cycle (+/-36 degrees) of drift.
     */
    protected final Setting<Double> phaseNoiseStrength = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Phase Noise Strength")
        .description("Low-frequency phase perturbation for the vertical sine wave. 0.0 = perfectly periodic. 0.05-0.15 recommended.")
        .defaultValue(0.1)
        .min(0.0)
        .sliderMax(0.5)
        .build()
    );

    /**
     * Frequency of the low-frequency phase-noise sine wave.
     *
     * Why: The default 0.003 (per tick) yields a ~105-second cycle at 20 tps.
     * Faster frequencies drift the phase more rapidly (harder to fingerprint but
     * risks looking jittery); slower ones are smoother but repeat sooner.
     */
    protected final Setting<Double> phaseNoiseFrequency = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Phase Noise Frequency")
        .description("Phase-noise frequency per tick. 0.001 = slow (~314s cycle), 0.01 = fast (~31s cycle).")
        .defaultValue(0.003)
        .min(0.0)
        .sliderMax(0.01)
        .build()
    );

    /**
     * How many blocks to scan downward from the heightmap when looking for a
     * solid surface.
     *
     * Why: The heightmap returns the highest non-air block, which may be leaves,
     * tall grass, a carpet, or a sign. We scan down until we find one the player
     * could actually stand on. 32 blocks handles tall trees and most buildings;
     * dense jungle or skyscrapers may need more. Lower it if you'd rather land
     * on foliage than the ground beneath it.
     */
    protected final Setting<Integer> solidBlockScanDepth = sgAntiCheat.add(new IntSetting.Builder()
        .name("Solid Block Scan Depth")
        .description("Max blocks to scan downward from heightmap to find solid ground. Increase for tall structures.")
        .defaultValue(32)
        .min(1)
        .sliderMax(64)
        .build()
    );

    /**
     * Speed at which the player descends during the landing phase.
     *
     * Why: A gentle descent stays out of NCP's fall-distance accumulator and
     * GrimAC's sudden-offset checks. Trade landing speed for safety here if the
     * server has stricter vertical motion checks.
     */
    protected final Setting<Double> landingDescendSpeed = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Landing Descend Speed")
        .description("Downward blocks/tick during landing. Lower = safer but slower.")
        .defaultValue(0.12)
        .min(0.01)
        .sliderMax(0.5)
        .build()
    );

    /**
     * Speed at which the player ascends after the landing phase.
     *
     * Why: The climb out has to stay under NCP's maxJumpHeight cap (1.35 blocks
     * above last setback) and avoid Negativity's fly-air-below counter. Slow
     * this down if the server flags take-offs.
     */
    protected final Setting<Double> landingAscendSpeed = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Landing Ascend Speed")
        .description("Upward blocks/tick after landing. Lower = safer but slower.")
        .defaultValue(0.08)
        .min(0.01)
        .sliderMax(0.5)
        .build()
    );

    /**
     * Proportional gain for terrain-aware altitude correction.
     *
     * Why: Each tick we compute yError = desiredY - currentY and apply
     * yError * gain. A higher gain corrects altitude faster but looks snappy; a
     * lower one is smoother but drifts further from target.
     */
    protected final Setting<Double> terrainCorrectionGain = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Terrain Correction Gain")
        .description("Proportional gain for altitude correction. Increase for tighter tracking.")
        .defaultValue(0.05)
        .min(0.0)
        .sliderMax(0.2)
        .build()
    );

    /**
     * Hard clamp on the vertical correction applied during terrain-aware flight.
     *
     * Why: Even with a small gain, a large altitude error (flying out over a
     * cliff) produces a large correction. This caps the vertical velocity change
     * per tick so it stays under anti-cheat vertical-speed limits.
     */
    protected final Setting<Double> maxVerticalCorrection = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Max Vertical Correction")
        .description("Max blocks/tick of vertical correction. Lower = safer.")
        .defaultValue(0.15)
        .min(0.01)
        .sliderMax(0.5)
        .build()
    );

    /**
     * How many blocks ahead (in the direction of travel) to sample terrain height, in addition to the column
     * directly beneath the player.
     *
     * Why: reacting only to the player's current column means the correction only starts climbing after flying
     * into a hill, tree, or wall - by then forward velocity has already driven the player into the obstacle.
     * Sampling ahead lets the climb start before the player reaches it. 0 disables lookahead and reverts to
     * sampling only the current column.
     */
    protected final Setting<Integer> terrainLookaheadBlocks = sgAntiCheat.add(new IntSetting.Builder()
        .name("Terrain Lookahead")
        .description("Blocks ahead of travel direction to sample terrain height, so climbing starts before you reach a hill or tree. 0 disables.")
        .defaultValue(6)
        .min(0)
        .sliderMax(20)
        .build()
    );

    /**
     * Consecutive ticks of near-zero horizontal displacement, despite commanding non-trivial forward speed,
     * before the module assumes it's wedged against terrain or an object and forces a climb.
     *
     * Why: without this, hitting something the terrain correction didn't climb over in time (a tree trunk, a
     * cliff face, a wall) leaves the player pinned there indefinitely - the module keeps commanding the same
     * blocked velocity every tick with no way to recover on its own.
     */
    protected final Setting<Integer> stuckDetectionTicks = sgAntiCheat.add(new IntSetting.Builder()
        .name("Stuck Detection Ticks")
        .description("Ticks of near-zero horizontal movement before assuming you're wedged against terrain and forcing a climb.")
        .defaultValue(10)
        .min(2)
        .sliderMax(60)
        .build()
    );

    /**
     * Upward blocks/tick applied once the player is flagged as stuck, overriding the normal terrain correction
     * until horizontal movement actually resumes.
     */
    protected final Setting<Double> stuckRecoverySpeed = sgAntiCheat.add(new DoubleSetting.Builder()
        .name("Stuck Recovery Climb Speed")
        .description("Upward blocks/tick used to power out of a stuck/wedged state.")
        .defaultValue(0.25)
        .min(0.01)
        .sliderMax(1.0)
        .build()
    );

    // =======================================================================
    // Runtime State
    // =======================================================================

    /** Chunk position at the start of the current leg, used both for turn-distance bookkeeping and as the
     *  reference point for the landing-phase cadence. Each subclass rebases this whenever it starts a new leg. */
    protected ChunkPos chunkPosAtLastTurn;

    protected int speedSlowDownTicks;
    protected Direction lastPlayerDictatedDirection;

    /** Tick counter incremented every onTick; the time parameter for sine-wave generation. */
    protected long tickCounter = 0;

    /** PRNG for jittering turn distances and other non-deterministic evasion tweaks. */
    protected final Random jitterRng = new Random();

    /** Position last tick, for detecting when horizontal movement has stalled against an obstacle. NaN when not yet sampled. */
    private double lastCheckedX = Double.NaN;
    private double lastCheckedZ = Double.NaN;

    /** Consecutive ticks spent with near-zero horizontal displacement despite commanding forward speed. */
    private int stuckTicks = 0;

    /**
     * Whether {@link #onEntityMove} should add boat thrust the next time the ridden boat actually moves. Set
     * from each subclass's {@code onTick} (which owns the path-planning decision of whether to move at all this
     * tick) and consumed by the event handler that owns the actual velocity write - see {@link #onEntityMove}
     * for why those two things can't just be done in the same place for boats.
     */
    protected boolean boatShouldThrustThisTick = false;

    /**
     * Landing-phase state machine:
     *   IDLE    -> normal flight
     *   DESCEND -> actively moving down toward the surface
     *   GROUND  -> touching ground, sending onGround=true, waiting out the decay window
     *   ASCEND  -> climbing back to cruising altitude before resuming IDLE
     */
    protected enum LandingState { IDLE, DESCEND, GROUND, ASCEND }
    protected LandingState landingState = LandingState.IDLE;

    /** How many ticks are left in the current GROUND phase. */
    private int groundTicksRemaining = 0;

    /**
     * Altitude we were cruising at when the landing cycle began, so ASCEND knows where to return to. NaN when
     * no landing is in progress - Y is legitimately negative in modern worlds, so 0 or -1 can't be the sentinel.
     */
    private double cruiseYBeforeLanding = Double.NaN;

    /** Latched ASCEND target, computed once on entering the phase so the exit test can't chase a moving goal. */
    private double ascendTargetY = Double.NaN;

    /** Safety budget so a blocked ascent (ceiling, cave) can't wedge the state machine forever. */
    private int ascendTicksRemaining = 0;

    /** Safety budget for the descent, for when there's no ground below us to find (unloaded chunk, void). */
    private int descendTicksRemaining = 0;

    /** Chunk-distance reading at the last landing, so we only land once per interval rather than every tick in the band. */
    protected int chunkDistanceAtLastLanding = 0;

    /** Tracks the player across reconnects/account swaps so a stale reference can't keep driving movement. */
    public LocalPlayer lastPlayer;

    protected AbstractFlightArchiveModule(String name, String description, String logPrefix) {
        super(ArchiverAddon.CATEGORY, name, description);
        this.LOG_PREFIX = logPrefix;
        this.speedSlowDownTicks = slowdownTicksSetting.get();
    }

    /**
     * Snapshot of the tuning values in play right now, for correlating with how long a session survives before a
     * kick. Includes Meteor's own Flight module settings (mode/speed/anti-kick) since that's what's actually
     * keeping us airborne in survival, and Entity Control's settings since that's the boat equivalent - this
     * module's own settings only drive the path navigation (and, for boats, horizontal thrust) on top of
     * whichever one applies.
     */
    public String describeTuning() {
        StringBuilder sb = new StringBuilder();
        sb.append("Archiver").append(describeModuleSettings(this));

        Flight flight = Modules.get().get(Flight.class);
        if (flight != null) {
            sb.append(" Flight").append(describeModuleSettings(flight));
        }

        EntityControl entityControl = Modules.get().get(EntityControl.class);
        if (entityControl != null) {
            sb.append(" EntityControl").append(describeModuleSettings(entityControl));
        }

        return sb.toString();
    }

    /**
     * Generic settings dump for any Meteor module, so we don't have to keep this in sync by hand every time a
     * module we depend on (like Flight) adds/renames a setting.
     */
    public static String describeModuleSettings(Module module) {
        StringJoiner joiner = new StringJoiner(" ", "[", "]");
        for (SettingGroup group : module.settings) {
            for (Setting<?> setting : group) {
                joiner.add(setting.name + "=" + setting.get());
            }
        }
        return joiner.toString();
    }

    protected void resetLastPlayer() {
        lastPlayer = Minecraft.getInstance().player;
    }

    /**
     * Puts the landing state machine back to a clean IDLE and forgets the landing cadence. Called whenever we
     * lose our positional bearings, since the cadence is measured against chunkPosAtLastTurn.
     */
    protected void resetLandingState() {
        endLandingPhase();
        chunkDistanceAtLastLanding = 0;
    }

    /**
     * Drops out of whatever landing phase we're in and back to normal flight, leaving the cadence bookkeeping
     * alone so the next landing is a full interval away rather than immediate.
     */
    protected void endLandingPhase() {
        landingState = LandingState.IDLE;
        groundTicksRemaining = 0;
        cruiseYBeforeLanding = Double.NaN;
        ascendTargetY = Double.NaN;
        ascendTicksRemaining = 0;
        descendTicksRemaining = 0;

        // Movement wasn't tracked via movePlayerDirection while landing, so the next stuck check has nothing
        // valid to compare against - start it fresh rather than let a stale reading misfire.
        stuckTicks = 0;
        lastCheckedX = Double.NaN;
        lastCheckedZ = Double.NaN;
    }

    /**
     * Moves the player in the provided direction.
     *
     * With anti-cheat evasion enabled this does three things the original archiver did not:
     *
     * 1. HORIZONTAL SPEED CAP
     *    Rather than blindly adding {@code speed} to the current X or Z component, we compute the intended
     *    displacement vector and clamp its magnitude to {@code maxHorizontalSpeed}. That avoids diagonal
     *    overshoot (adding speed to both axes yields sqrt(2)*speed) and keeps per-tick displacement under the
     *    ~0.85 block/tick airborne ceiling used by Negativity, NCP, GrimAC, and Spartan.
     *
     * 2. CONTINUOUS VERTICAL OSCILLATION
     *    A small sine-wave offset on Y every tick, so Y velocity is never exactly zero twice in a row - the
     *    exact signature Negativity flags at 98% confidence, and one that also keeps us clear of GrimAC's
     *    sub-millimeter offset tolerance and NCP's hover envelope.
     *
     * 3. TERRAIN-AWARE ALTITUDE TARGETING
     *    When terrain awareness is on we sample the heightmap at the player's X/Z and nudge vertical velocity
     *    toward surface + offset. Hugging the terrain avoids the long open-air climbs that trip Negativity's
     *    fly-air-below counter, the jump-arc deviations Spartan's GravitySimulation measures, and the block
     *    clipping that trips Phase/Exploits.
     */
    public void movePlayerDirection(Direction direction, LocalPlayer player) {
        // Ensure the player is facing the right direction.
        setPlayerYawTowardsDirection(direction);

        double dx = 0.0;
        double dz = 0.0;

        switch (direction) {
            case NORTH -> dz -= speed.get();
            case SOUTH -> dz += speed.get();
            case EAST -> dx += speed.get();
            case WEST -> dx -= speed.get();
            default -> LOG.warning("Player needs to move, but direction " + direction + " is unknown.");
        }

        if (!anticheatEvasionEnabled.get()) {
            setPlayerVelocity(player.getDeltaMovement().x + dx, player.getDeltaMovement().y, player.getDeltaMovement().z + dz);
            return;
        }

        boolean forceClimb = updateStuckDetection(player);

        // Evasion 1: cap the resulting horizontal speed, not just our contribution to it.
        double totalVx = player.getDeltaMovement().x + dx;
        double totalVz = player.getDeltaMovement().z + dz;
        double len = Math.sqrt(totalVx * totalVx + totalVz * totalVz);
        double cap = maxHorizontalSpeed.get();

        if (len > cap) {
            double scale = cap / len;
            totalVx *= scale;
            totalVz *= scale;
        }

        // Evasion 2 + 3: vertical oscillation, plus a gentle pull toward the terrain-relative cruise altitude.
        double velocityY = player.getDeltaMovement().y + computeYJitter();

        if (terrainAwareAltitude.get() > 0) {
            double surfaceY = sampleAheadSurfaceHeight(player, direction);

            if (surfaceY > Integer.MIN_VALUE) {
                // Proportional correction, so the climb or descent looks smooth rather than an instant snap.
                double yCorrection = ((surfaceY + terrainAwareAltitude.get()) - player.getY()) * terrainCorrectionGain.get();
                double maxCorrection = maxVerticalCorrection.get();

                velocityY += Math.clamp(yCorrection, -maxCorrection, maxCorrection);
            }
        }

        if (forceClimb) {
            // Wedged against something (a tree, a cliff face, a wall) that the terrain correction didn't climb
            // over in time. Override it and power straight up until horizontal movement actually resumes,
            // instead of sitting there commanding the same blocked velocity forever.
            velocityY = Math.max(velocityY, stuckRecoverySpeed.get());
        }

        setPlayerVelocity(totalVx, velocityY, totalVz);
    }

    /**
     * Applies the boat's horizontal thrust for this tick - the boat equivalent of {@link #movePlayerDirection}.
     *
     * This can't just write {@code boat.setDeltaMovement(...)} from {@code onTick} the way the free-flight path
     * writes to the player. Entity Control hooks this same {@link EntityMoveEvent} and, every time it fires,
     * unconditionally rewrites the boat's full velocity vector - reading whatever's currently there as its
     * baseline for the axes it doesn't override, then setting all three unconditionally. Writing from onTick
     * means our write's timing relative to Entity Control's own pass on this event is unspecified, so depending
     * on ordering, Entity Control can either carry our value forward (works) or read a stale/zeroed value and
     * overwrite ours right back out (no movement, which is exactly what happens with Entity Control's own
     * "Speed" setting on - it recomputes horizontal velocity from real key state, which is never pressed here).
     *
     * Registering here at {@link EventPriority#LOW} - below Entity Control's default priority - guarantees we
     * always run after it and get the last word on X/Z, every single time the boat moves, regardless of what
     * Entity Control's own settings are doing. Y is deliberately never touched: that's still entirely Entity
     * Control's "Flight" setting to own, the same role the standalone Flight module plays for free flight.
     */
    @EventHandler(priority = EventPriority.LOW)
    protected void onEntityMove(EntityMoveEvent event) {
        if (!archiveEnabled.get() || !boatShouldThrustThisTick) return;
        if (!(event.entity instanceof AbstractBoat boat)) return;
        if (boat.getControllingPassenger() != Minecraft.getInstance().player) return;
        if (lastPlayerDictatedDirection == null) return;

        double dx = 0.0;
        double dz = 0.0;

        switch (lastPlayerDictatedDirection) {
            case NORTH -> dz -= boatSpeed.get();
            case SOUTH -> dz += boatSpeed.get();
            case EAST -> dx += boatSpeed.get();
            case WEST -> dx -= boatSpeed.get();
        }

        double totalVx = event.movement.x + dx;
        double totalVz = event.movement.z + dz;

        if (anticheatEvasionEnabled.get()) {
            double len = Math.sqrt(totalVx * totalVx + totalVz * totalVz);
            double cap = boatMaxHorizontalSpeed.get();

            if (len > cap) {
                double scale = cap / len;
                totalVx *= scale;
                totalVz *= scale;
            }
        }

        ((IVec3) event.movement).meteor$setXZ(totalVx, totalVz);
    }

    /**
     * Tracks actual horizontal displacement tick-to-tick and flags when it's stalled out despite the module
     * commanding non-trivial forward speed - the signature of being wedged against terrain or an object that the
     * terrain-follow correction didn't climb over in time.
     *
     * @return true once the stall has lasted {@code stuckDetectionTicks} consecutive ticks, meaning the caller
     *         should force a climb rather than trust the normal terrain correction to free the player.
     */
    private boolean updateStuckDetection(LocalPlayer player) {
        double actualDelta = Double.MAX_VALUE;

        if (!Double.isNaN(lastCheckedX)) {
            double dx = player.getX() - lastCheckedX;
            double dz = player.getZ() - lastCheckedZ;
            actualDelta = Math.sqrt(dx * dx + dz * dz);
        }

        lastCheckedX = player.getX();
        lastCheckedZ = player.getZ();

        double commandedSpeed = Math.min(speed.get(), maxHorizontalSpeed.get());

        if (commandedSpeed > 0.001 && actualDelta < commandedSpeed * 0.25) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        if (stuckTicks == stuckDetectionTicks.get()) {
            LOG.info(LOG_PREFIX + "Movement stalled for " + stuckTicks + " ticks - looks wedged against terrain, forcing a climb to clear it.");
        }

        return stuckTicks >= stuckDetectionTicks.get();
    }

    /**
     * The vertical sine-wave jitter for this tick, with low-frequency phase noise layered on.
     *
     * The base wave is a fast periodic one ({@code verticalOscillationPeriod}). A second, far slower wave is
     * added to its phase so the waveform never repeats exactly, which is what breaks the signature-based
     * heuristics (Negativity's OmegaCraftFly, Spartan's spam penalty) that look for perfectly periodic motion.
     *
     * @return the Y offset to apply this tick, or 0.0 when the amplitude is zeroed out.
     */
    private double computeYJitter() {
        if (verticalOscillationAmplitude.get() <= 0.0) {
            return 0.0;
        }

        double phase = (tickCounter % verticalOscillationPeriod.get()) / (double) verticalOscillationPeriod.get();
        double noise = phaseNoiseStrength.get() > 0.0
            ? Math.sin(tickCounter * phaseNoiseFrequency.get()) * phaseNoiseStrength.get()
            : 0.0;

        return Math.sin((phase + noise) * 2.0 * Math.PI) * verticalOscillationAmplitude.get();
    }

    /**
     * Samples the world's surface height at the player's current X/Z position.
     *
     * @return the Y of the highest solid block in the player's column, or {@link Integer#MIN_VALUE} if the
     *         chunk isn't loaded yet.
     */
    private double sampleSurfaceHeight(LocalPlayer player) {
        return sampleSurfaceHeight(player, player.blockPosition());
    }

    /**
     * Samples the world's surface height at an arbitrary column, so callers can look ahead of the player's
     * actual position instead of only reacting to what's directly beneath them.
     *
     * Starts from the {@link Heightmap.Types#WORLD_SURFACE} heightmap and then scans downward for the first
     * block the player could actually stand on. The heightmap alone isn't enough: it returns the highest
     * non-air block, which may be leaves, tall grass, a sign, or a carpet. Measuring altitude from - or trying
     * to land on - a block with no collision leaves the player in the air with onGround false, which defeats
     * the entire point of the landing phase, since all four anti-cheats cross-reference onGround against block
     * collision.
     *
     * @return the Y of the highest solid block in the column, or {@link Integer#MIN_VALUE} if the chunk isn't
     *         loaded yet.
     */
    private double sampleSurfaceHeight(LocalPlayer player, BlockPos columnPos) {
        if (mc.level == null) return Integer.MIN_VALUE;

        // Start from the heightmap top - cheap, already computed.
        BlockPos surfacePos = mc.level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, columnPos);
        if (surfacePos == null) return Integer.MIN_VALUE;

        int lowestY = Math.max(mc.level.getMinY(), surfacePos.getY() - solidBlockScanDepth.get());

        for (int y = surfacePos.getY(); y >= lowestY; y--) {
            BlockPos checkPos = new BlockPos(surfacePos.getX(), y, surfacePos.getZ());
            BlockState state = mc.level.getBlockState(checkPos);

            if (state != null && state.entityCanStandOn(mc.level, checkPos, player)) {
                return y;
            }
        }

        // Nothing solid within range, fall back to the raw heightmap.
        return surfacePos.getY();
    }

    /**
     * The higher of the surface height directly beneath the player and the surface height
     * {@code terrainLookaheadBlocks} ahead in the direction of travel.
     *
     * Why: sampling only the current column means the correction is purely reactive - it doesn't start climbing
     * until the player is already over (or into) rising terrain, a tree, or a wall. By then forward velocity has
     * already driven the player into the obstacle, and since collision resolution zeroes the actual displacement
     * while the same forward velocity keeps getting commanded every tick, the player stays wedged there.
     * Sampling ahead means the climb starts before the player reaches the obstacle instead of after hitting it.
     */
    private double sampleAheadSurfaceHeight(LocalPlayer player, Direction direction) {
        double here = sampleSurfaceHeight(player, player.blockPosition());

        int lookahead = terrainLookaheadBlocks.get();
        if (lookahead <= 0 || direction == null) return here;

        double ahead = sampleSurfaceHeight(player, player.blockPosition().relative(direction, lookahead));

        if (here <= Integer.MIN_VALUE) return ahead;
        if (ahead <= Integer.MIN_VALUE) return here;

        return Math.max(here, ahead);
    }

    /**
     * Landing-phase state machine handler. Returns {@code true} when the tick was fully consumed by landing
     * logic, in which case the caller skips path navigation for this tick.
     *
     * The four states and why each one exists:
     *
     *   DESCEND: drop at a gentle {@code landingDescendSpeed} - slow enough to stay out of NCP's fall-distance
     *   accumulator and GrimAC's sudden-offset checks, quick enough to reach ground in reasonable time.
     *
     *   GROUND: hold still and let the vanilla client report onGround=true once it detects collision, for long
     *   enough that every anti-cheat gets a chance to reset its state.
     *
     *   ASCEND: climb back to the altitude we came from at {@code landingAscendSpeed}, staying under NCP's
     *   maxJumpHeight cap (1.35 blocks above last setback) and clear of Negativity's fly-air-below counter.
     *
     *   IDLE: normal flight, watching for the next landing cycle to come due.
     */
    protected boolean handleLandingPhase(LocalPlayer player) {
        if (mc.level == null) return false;

        switch (landingState) {
            case DESCEND -> {
                setPlayerVelocity(player.getDeltaMovement().x, -landingDescendSpeed.get(), player.getDeltaMovement().z);

                if (player.onGround()) {
                    LOG.info(LOG_PREFIX + "Landing phase: touched ground. Holding for " + landingDurationTicks.get() + " ticks to reset anti-cheat state.");
                    landingState = LandingState.GROUND;
                    groundTicksRemaining = landingDurationTicks.get();
                    return true;
                }

                if (--descendTicksRemaining <= 0) {
                    // Nothing solid turned up on the way down - an unloaded chunk or a hole through the world.
                    // Give up on this landing and get back to covering chunks.
                    LOG.info(LOG_PREFIX + "Landing phase: aborted, never found ground on the way down.");
                    endLandingPhase();
                }

                return true;
            }

            case GROUND -> {
                // Hold position on the ground. Vanilla physics handles the onGround reporting for us, we just
                // zero out horizontal velocity to stay put.
                setPlayerVelocity(0, player.getDeltaMovement().y, 0);

                if (--groundTicksRemaining <= 0) {
                    LOG.info(LOG_PREFIX + "Landing phase: ground hold complete. Ascending to resume flight.");
                    landingState = LandingState.ASCEND;
                    ascendTargetY = computeAscendTarget(player);
                    // Generous budget for the climb, so a ceiling or an overhang can't wedge us in ASCEND forever.
                    ascendTicksRemaining = (int) Math.ceil((ascendTargetY - player.getY()) / landingAscendSpeed.get()) * 2 + 40;
                }

                return true;
            }

            case ASCEND -> {
                setPlayerVelocity(player.getDeltaMovement().x, landingAscendSpeed.get(), player.getDeltaMovement().z);

                if (player.getY() >= ascendTargetY || --ascendTicksRemaining <= 0) {
                    LOG.info(LOG_PREFIX + "Landing phase: ascent complete. Resuming flight.");
                    endLandingPhase();
                }

                return true;
            }

            case IDLE -> {
                if (chunkPosAtLastTurn == null) return false;

                // Chunk distance traveled since the last turn stands in for coverage progress. Compare against
                // the reading at the previous landing rather than testing a modulo, so we land once per interval
                // instead of re-triggering every tick we spend in the same chunk band.
                int chunksSinceLastTurn = player.chunkPosition().distanceSquared(chunkPosAtLastTurn) / 2;

                if (chunksSinceLastTurn - chunkDistanceAtLastLanding >= landingIntervalChunks.get()) {
                    LOG.info(LOG_PREFIX + "Landing phase: triggering periodic ground contact after " + chunksSinceLastTurn + " chunks.");
                    landingState = LandingState.DESCEND;
                    chunkDistanceAtLastLanding = chunksSinceLastTurn;
                    cruiseYBeforeLanding = player.getY();
                    // Enough ticks to reach the bottom of the world at the configured speed, with slack.
                    descendTicksRemaining = (int) Math.ceil((player.getY() - mc.level.getMinY()) / landingDescendSpeed.get()) * 2 + 40;
                    return true;
                }

                return false;
            }
        }

        return false;
    }

    /**
     * The altitude ASCEND climbs back to, latched once on entering the phase so the exit test isn't chasing a
     * moving target. Terrain-aware flight goes back to surface + offset; otherwise we return to whatever
     * altitude we were cruising at when the landing began.
     */
    private double computeAscendTarget(LocalPlayer player) {
        if (terrainAwareAltitude.get() > 0) {
            double surfaceY = sampleSurfaceHeight(player);

            if (surfaceY > Integer.MIN_VALUE) {
                return surfaceY + terrainAwareAltitude.get();
            }
        }

        // Terrain awareness off, or the chunk isn't loaded - resume the altitude we left.
        return Double.isNaN(cruiseYBeforeLanding) ? player.getY() + 5 : cruiseYBeforeLanding;
    }

    /**
     * Sets the velocity of the player.
     */
    private void setPlayerVelocity(double x, double y, double z) {
        Minecraft.getInstance().player.setDeltaMovement(x, y, z);
    }

    /**
     * Sets the player's (and, if riding one, the boat's) yaw to look in the provided direction.
     * @param direction Direction to face.
     */
    public void setPlayerYawTowardsDirection(Direction direction) {
        LocalPlayer player = Minecraft.getInstance().player;

        float yaw = 0;

        switch (direction) {
            case NORTH -> yaw = -180;
            case EAST -> yaw = -90;
            case SOUTH -> yaw = 0;
            case WEST -> yaw = 90;
        }

        player.setYRot(yaw);
        player.setYBodyRot(yaw);
        player.setYHeadRot(yaw);
        lastPlayerDictatedDirection = direction;

        // Riding a boat: the boat is what actually needs to face the travel direction, both so it doesn't look
        // like it's sliding sideways and so Entity Control's own lock-yaw (which mirrors the boat's yaw from the
        // player's every tick) isn't fighting a stale rotation.
        if (player.getVehicle() instanceof AbstractBoat boat) {
            boat.setYRot(yaw);
            boat.setYBodyRot(yaw);
            boat.setYHeadRot(yaw);
        }
    }

    /** Rotates a cardinal direction 90 degrees clockwise (as seen from above). */
    protected static Direction turnClockwise(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> throw new IllegalArgumentException("Unsupported direction " + direction);
        };
    }

    /** Rotates a cardinal direction 90 degrees counter-clockwise (as seen from above). */
    protected static Direction turnCounterClockwise(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            default -> throw new IllegalArgumentException("Unsupported direction " + direction);
        };
    }
}
