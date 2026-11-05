package game.data.entity;

import config.Config;
import config.Version;
import game.data.PlayerSession;
import game.data.WorldManager;
import game.data.chunk.Chunk;
import game.data.coordinates.CoordinateDim2D;
import game.data.entity.player.PlayerReader;
import game.data.entity.specific.Villager;
import packets.DataTypeProvider;
import packets.UUID;
import se.llbit.nbt.SpecificTag;
import static util.ExceptionHandling.attempt;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EntityRegistry{
	
	private final Map<UUID, PlayerEntity> players;
	private final Map<CoordinateDim2D, Set<Entity>> perChunk;
	private final Map<Integer, Entity> entities;
	// fleet mode only: the bots that can see each entity, it's only forgotten once none of them can
	private final Map<Integer, Set<PlayerSession>> viewers;
	private final WorldManager worldManager;
	private static PlayerReader playerReader;
	
	private final ExecutorService executor;
	
	public EntityRegistry(WorldManager manager) {
		this.worldManager = manager;
		this.perChunk = new ConcurrentHashMap<>();
		this.entities = new ConcurrentHashMap<>();
		this.players = new ConcurrentHashMap<>();
		this.viewers = new ConcurrentHashMap<>();
		
		this.executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Entity Parser Service"));
		;
	}
	
	/**
	 * Whether entity updates received by the player of the current thread are used. Unlike chunks these are tracked
	 * while saving is paused, only a stopped bot's are dropped. Threads without a bot are the single user's.
	 */
	private static boolean acceptsEntities() {
		PlayerSession session = PlayerSession.bound();
		return session == null || session.acceptsData();
	}

	/**
	 * Run the task on the entity thread with the session of the player that received it. Entity packets of a bot
	 * that stopped saving are dropped.
	 */
	private void run(Runnable task) {
		if(!acceptsEntities()){
			return;
		}
		executor.execute(PlayerSession.capture(task));
	}
	
	/**
	 * Add a new entity.
	 */
	public void addEntity(DataTypeProvider provider, Function<DataTypeProvider, Entity> parser) {
		run(() -> attempt(() -> {
			Entity ent = parser.apply(provider);
			if(ent == null){
				return;
			}
			entities.put(ent.getId(), ent);
			
			PlayerSession session = PlayerSession.bound();
			if(session != null && session.isFleet()){
				viewers.computeIfAbsent(ent.getId(), k -> ConcurrentHashMap.newKeySet()).add(session);
			}
			
			ent.registerOnLocationChange((oldPos, newPos) -> {
				CoordinateDim2D oldChunk = oldPos == null ? null : oldPos.globalToDimChunk();
				CoordinateDim2D newChunk = newPos.globalToDimChunk();
				
				// if they're the same, just mark the chunk as unsaved
				if(oldPos == newPos){
					markUnsaved(newChunk);
					return;
				}
				
				Set<Entity> entities = oldChunk == null ? null : perChunk.get(oldChunk);
				if(entities != null){
					entities.remove(ent);
					
					if(entities.isEmpty()){
						perChunk.remove(oldChunk);
					}
				}
				
				Set<Entity> set = perChunk.computeIfAbsent(newChunk, (k) -> ConcurrentHashMap.newKeySet());
				set.add(ent);
				
				markUnsaved(newChunk);
				
			});
			
			if(ent instanceof Villager villager){
				villager.registerOnTradeUpdate((pos) -> markUnsaved(pos.globalToDimChunk()));
			}
		}));
	}
	
	public void addPlayer(DataTypeProvider provider) {
		run(() -> attempt(() -> {
			PlayerEntity player = PlayerEntity.parse(provider);
			players.put(player.getUUID(), player);
		}));
	}
	
	/**
	 * Sent by the server to update the user list (<tab> in the client).
	 *
	 * @param provider
	 */
	public void updatePlayerAction(DataTypeProvider provider) {
		run(() -> attempt(() -> {
			if(playerReader == null){
				playerReader = PlayerReader.getVersioned();
			}
			playerReader.updatePlayerAction(players, provider);
		}));
	}
	
	private void markUnsaved(CoordinateDim2D coord) {
		Chunk chunk = worldManager.getChunk(coord);
		if(chunk != null){
			worldManager.touchChunk(chunk);
		}
	}
	
	/**
	 * Delete all tile entities for a chunk, only done when the chunk is also unloaded. Note that this only related to
	 * tile entities sent in the update-tile-entity packets, ones sent with the chunk will only be stored in the chunk.
	 *
	 * @param location the position of the chunk for which we can delete tile entities.
	 */
	public void unloadChunk(CoordinateDim2D location) {
		Set<Entity> entities = perChunk.remove(location);
		if(entities == null){
			return;
		}
		
		for(Entity e : entities){
			this.entities.remove(e.getId());
			this.viewers.remove(e.getId());
		}
	}
	
	public void addMetadata(DataTypeProvider provider) {
		run(() -> attempt(() -> {
			Entity ent = entities.get(provider.readVarInt());
			
			if(ent != null){
				ent.parseMetadata(provider);
			}
		}));
	}
	
	public void updatePositionRelative(DataTypeProvider provider) {
		run(() -> attempt(() -> {
			IMovableEntity ent = getMovableEntity(provider.readVarInt());
			
			if(ent == null){
				return;
			}
			
			// 26.3+ prefixes the delta with a VarInt packing onGround (bit 0) and a step count (remaining bits)
			int steps = Config.versionReporter().isAtLeast(Version.V26_3) ? provider.readVarInt() >>> 1 : 0;
			if(steps == 0){
				ent.incrementPosition(provider.readShort(), provider.readShort(), provider.readShort());
				return;
			}
			
			// stepped deltas are each relative to the previous step, so the final position is their sum
			int dx = 0, dy = 0, dz = 0;
			for(int i = 0; i < steps; i++){
				provider.readVarInt(); // tick offset
				dx += provider.readShort();
				dy += provider.readShort();
				dz += provider.readShort();
			}
			ent.incrementPosition(dx, dy, dz);
		}));
	}
	
	/**
	 * EntityPositionSync. Before 26.3 (from 1.21.2) it carries an absolute PositionMoveRotation, which starts with the
	 * position as three doubles. From 26.3 the absolute position is sent as a PositionPath: a VarInt type (0 = a single
	 * position, 1 = a list of position + tick offset steps, ending at the entity's current position).
	 */
	public void updatePositionSync(DataTypeProvider provider) {
		if(!Config.versionReporter().isAtLeast(Version.V26_3)){
			updatePositionAbsolute(provider);
			return;
		}
		
		run(() -> attempt(() -> {
			IMovableEntity ent = getMovableEntity(provider.readVarInt());
			if(ent == null){
				return;
			}
			
			if(provider.readVarInt() == 1){
				int steps = provider.readVarInt();
				if(steps == 0){
					return;
				}
				for(int i = 0; i < steps - 1; i++){
					provider.readDouble();
					provider.readDouble();
					provider.readDouble();
					provider.readVarInt(); // tick offset
				}
			}
			ent.readPosition(provider);
		}));
	}
	
	public void updatePositionAbsolute(DataTypeProvider provider) {
		run(() -> attempt(() -> {
			IMovableEntity ent = getMovableEntity(provider.readVarInt());
			
			if(ent != null){
				ent.readPosition(provider);
			}
		}));
	}
	
	public IMovableEntity getMovableEntity(int entId) {
		Entity tmpEnt = entities.get(entId);
		if(tmpEnt == null){
			return null;
		}
		IMovableEntity ent = players.get(tmpEnt.uuid);
		if(ent != null){
			return ent;
		}
		
		return tmpEnt;
	}
	
	public List<SpecificTag> getEntitiesNbt(CoordinateDim2D location) {
		Set<Entity> entities = perChunk.get(location);
		
		if(entities == null){
			return Collections.emptyList();
		}
		
		return entities.stream().map(Entity::toNbt).collect(Collectors.toList());
	}
	
	/**
	 * Forget all entities, the player changed worlds or disconnected. In fleet mode only the entities the bot of
	 * the current thread could see are forgotten, and only if no other bot can see them.
	 */
	public void reset() {
		PlayerSession session = PlayerSession.bound();
		if(session != null && session.isFleet()){
			forgetSession(session);
			return;
		}
		this.entities.clear();
		this.perChunk.clear();
		this.players.clear();
		this.viewers.clear();
	}
	
	/**
	 * The bot can no longer see any of its entities. Like with destroyEntities, they stay in their chunk until it's
	 * unloaded.
	 */
	public void forgetSession(PlayerSession session) {
		for(Map.Entry<Integer, Set<PlayerSession>> entry : viewers.entrySet()){
			Set<PlayerSession> seenBy = entry.getValue();
			if(seenBy.remove(session) && seenBy.isEmpty()){
				forget(entry.getKey());
			}
		}
	}
	
	private void forget(int id) {
		viewers.remove(id);
		Entity ent = entities.remove(id);
		if(ent != null){
			players.remove(ent.uuid);
		}
	}
	
	public void addEquipment(DataTypeProvider provider) {
		run(() -> attempt(() -> {
			int id = provider.readVarInt();
			Entity ent = entities.get(id);
			
			if(ent != null){
				ent.addEquipment(provider);
			}
		}));
	}
	
	/**
	 * When destroyEntities is called, we don't remove the entities from the perChunk map. These will only be removed
	 * when the chunk is unloaded. This way we won't accidentally delete entities that belong to an unsaved chunk.
	 */
	public void destroyEntities(DataTypeProvider provider) {
		if(!acceptsEntities()){
			return;
		}
		PlayerSession session = PlayerSession.bound();
		try {
			int count = provider.readVarInt();
			while (count-- > 0 && provider.hasNext()) {
				int id = provider.readVarInt();
				
				// another bot may still see it
				Set<PlayerSession> seenBy = viewers.get(id);
				if (session != null && seenBy != null) {
					seenBy.remove(session);
					if (!seenBy.isEmpty()) {
						continue;
					}
				}
				if (entities.containsKey(id)) {
					forget(id);
				}
			}
		} catch (RuntimeException ex) {
		}
	}
	
	public int countActiveEntities() {
		return this.entities.size();
	}
	
	public int countActivePlayers() {
		return this.players.size();
	}
	
	public Collection<PlayerEntity> getPlayerSet() {
		return players.values();
	}
	
	public void addVillagerTrades(DataTypeProvider provider) {
		run(() -> attempt(() -> {
			worldManager.getVillagerManager().parseAndStoreVillagerTrade(provider);
		}));
	}
}
