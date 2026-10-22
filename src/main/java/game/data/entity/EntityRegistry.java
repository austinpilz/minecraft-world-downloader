package game.data.entity;

import config.Config;
import config.Version;
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
	private final WorldManager worldManager;
	private static PlayerReader playerReader;
	
	private final ExecutorService executor;
	
	public EntityRegistry(WorldManager manager) {
		this.worldManager = manager;
		this.perChunk = new ConcurrentHashMap<>();
		this.entities = new ConcurrentHashMap<>();
		this.players = new ConcurrentHashMap<>();
		
		this.executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Entity Parser Service"));
		;
	}
	
	/**
	 * Add a new entity.
	 */
	public void addEntity(DataTypeProvider provider, Function<DataTypeProvider, Entity> parser) {
		this.executor.execute(() -> attempt(() -> {
			Entity ent = parser.apply(provider);
			if(ent == null){
				return;
			}
			entities.put(ent.getId(), ent);
			
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
		executor.execute(() -> attempt(() -> {
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
		executor.execute(() -> attempt(() -> {
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
		}
	}
	
	public void addMetadata(DataTypeProvider provider) {
		this.executor.execute(() -> attempt(() -> {
			Entity ent = entities.get(provider.readVarInt());
			
			if(ent != null){
				ent.parseMetadata(provider);
			}
		}));
	}
	
	public void updatePositionRelative(DataTypeProvider provider) {
		this.executor.execute(() -> attempt(() -> {
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
	 * EntityPositionSync. From 26.3 the absolute position is sent as a PositionPath: a VarInt type (0 = a single
	 * position, 1 = a list of position + tick offset steps, ending at the entity's current position).
	 */
	public void updatePositionSync(DataTypeProvider provider) {
		if(!Config.versionReporter().isAtLeast(Version.V26_3)){
			updatePositionRelative(provider);
			return;
		}
		
		this.executor.execute(() -> attempt(() -> {
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
		this.executor.execute(() -> attempt(() -> {
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
	
	public void reset() {
		this.entities.clear();
		this.perChunk.clear();
		this.players.clear();
	}
	
	public void addEquipment(DataTypeProvider provider) {
		this.executor.execute(() -> attempt(() -> {
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
		try {
			int count = provider.readVarInt();
			while (count-- > 0 && provider.hasNext()) {
				int id = provider.readVarInt();
				if (entities.containsKey(id)) {
					players.remove(entities.get(id).uuid);
					entities.remove(id);
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
		this.executor.execute(() -> attempt(() -> {
			worldManager.getVillagerManager().parseAndStoreVillagerTrade(provider);
		}));
	}
}
