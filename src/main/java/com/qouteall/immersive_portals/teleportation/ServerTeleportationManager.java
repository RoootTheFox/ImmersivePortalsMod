package com.qouteall.immersive_portals.teleportation;

import com.qouteall.immersive_portals.Helper;
import com.qouteall.immersive_portals.McHelper;
import com.qouteall.immersive_portals.ModMain;
import com.qouteall.immersive_portals.MyNetwork;
import com.qouteall.immersive_portals.compat.RequiemCompat;
import com.qouteall.immersive_portals.ducks.IEServerPlayNetworkHandler;
import com.qouteall.immersive_portals.ducks.IEServerPlayerEntity;
import com.qouteall.immersive_portals.portal.Portal;
import com.qouteall.immersive_portals.portal.global_portals.GlobalPortalStorage;
import net.minecraft.client.network.packet.CustomPayloadS2CPacket;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.dimension.DimensionType;

import java.util.*;
import java.util.stream.Stream;

public class ServerTeleportationManager {
    private Set<ServerPlayerEntity> teleportingEntities = new HashSet<>();
    private WeakHashMap<Entity, Long> lastTeleportGameTime = new WeakHashMap<>();
    public boolean isFiringMyChangeDimensionEvent = false;
    
    public ServerTeleportationManager() {
        ModMain.postServerTickSignal.connectWithWeakRef(this, ServerTeleportationManager::tick);
        Portal.serverPortalTickSignal.connectWithWeakRef(
            this, (this_, portal) ->
                getEntitiesToTeleport(portal).forEach(entity -> {
                    tryToTeleportRegularEntity(portal, entity);
                })
        );
    }
    
    public void tryToTeleportRegularEntity(Portal portal, Entity entity) {
        if (!(entity instanceof ServerPlayerEntity)) {
            if (entity.getVehicle() != null || doesEntityClutterContainPlayer(entity)) {
                return;
            }
            ModMain.serverTaskList.addTask(() -> {
                teleportRegularEntity(entity, portal);
                return true;
            });
        }
    }
    
    public static Stream<Entity> getEntitiesToTeleport(Portal portal) {
        return portal.world.getEntities(
            Entity.class,
            portal.getBoundingBox().expand(2),
            e -> true
        ).stream().filter(
            e -> !(e instanceof Portal)
        ).filter(
            portal::shouldEntityTeleport
        );
    }
    
    public void onPlayerTeleportedInClient(
        ServerPlayerEntity player,
        DimensionType dimensionBefore,
        Vec3d posBefore,
        UUID portalId
    ) {
        ServerWorld originalWorld = McHelper.getServer().getWorld(dimensionBefore);
        Entity portalEntity = originalWorld.getEntity(portalId);
        if (portalEntity == null) {
            portalEntity = GlobalPortalStorage.get(originalWorld).data
                .stream().filter(
                    p -> p.getUuid().equals(portalId)
                ).findFirst().orElse(null);
        }
        lastTeleportGameTime.put(player, McHelper.getServerGameTime());
        
        if (canPlayerTeleport(player, dimensionBefore, posBefore, portalEntity)) {
            if (isTeleporting(player)) {
                Helper.err(player.toString() + "is teleporting frequently");
            }
    
            DimensionType dimensionTo = ((Portal) portalEntity).dimensionTo;
            Vec3d newPos = ((Portal) portalEntity).applyTransformationToPoint(posBefore);
    
            teleportPlayer(player, dimensionTo, newPos);
    
            ((Portal) portalEntity).onEntityTeleportedOnServer(player);
        }
        else {
            Helper.err(String.format(
                "Player cannot teleport through portal %s %s %s %s",
                player.getName().asString(),
                player.dimension,
                player.getPos(),
                portalEntity
            ));
        }
    }
    
    private boolean canPlayerTeleport(
        ServerPlayerEntity player,
        DimensionType dimensionBefore,
        Vec3d posBefore,
        Entity portalEntity
    ) {
        if (player.getVehicle() != null) {
            return true;
        }
        return canPlayerReachPos(player, dimensionBefore, posBefore) &&
            portalEntity instanceof Portal &&
            ((Portal) portalEntity).getDistanceToPlane(posBefore) < 20;
    }
    
    private boolean canPlayerReachPos(
        ServerPlayerEntity player,
        DimensionType dimension,
        Vec3d pos
    ) {
        return player.dimension == dimension ?
            isClose(pos, player.getPos())
            :
            McHelper.getServerPortalsNearby(player, 20)
                .anyMatch(
                    portal -> portal.dimensionTo == dimension &&
                        portal.getDistanceToNearestPointInPortal(portal.reverseTransformPoint(pos)) < 20
                );
    }
    
    private static boolean isClose(Vec3d a, Vec3d b) {
        return a.squaredDistanceTo(b) < 20 * 20;
    }
    
    private void teleportPlayer(
        ServerPlayerEntity player,
        DimensionType dimensionTo,
        Vec3d newPos
    ) {
        ServerWorld fromWorld = (ServerWorld) player.world;
        ServerWorld toWorld = McHelper.getServer().getWorld(dimensionTo);
    
        if (player.dimension == dimensionTo) {
            player.updatePosition(newPos.x, newPos.y, newPos.z);
        }
        else {
            changePlayerDimension(player, fromWorld, toWorld, newPos);
            ((IEServerPlayNetworkHandler) player.networkHandler).cancelTeleportRequest();
        }
    
        McHelper.adjustVehicle(player);
        ((IEServerPlayerEntity) player).setIsInTeleportationState(true);
        player.networkHandler.syncWithPlayerPosition();
    }
    
    public void invokeTpmeCommand(
        ServerPlayerEntity player,
        DimensionType dimensionTo,
        Vec3d newPos
    ) {
        ServerWorld fromWorld = (ServerWorld) player.world;
        ServerWorld toWorld = McHelper.getServer().getWorld(dimensionTo);
        
        if (player.dimension == dimensionTo) {
            player.updatePosition(newPos.x, newPos.y, newPos.z);
        }
        else {
            changePlayerDimension(player, fromWorld, toWorld, newPos);
            sendPositionConfirmMessage(player);
        }
        
        player.networkHandler.requestTeleport(
            newPos.x,
            newPos.y,
            newPos.z,
            player.yaw,
            player.pitch
        );
        player.networkHandler.syncWithPlayerPosition();
        ((IEServerPlayNetworkHandler) player.networkHandler).cancelTeleportRequest();
        
    }
    
    /**
     * {@link ServerPlayerEntity#changeDimension(DimensionType)}
     */
    private void changePlayerDimension(
        ServerPlayerEntity player,
        ServerWorld fromWorld,
        ServerWorld toWorld,
        Vec3d destination
    ) {
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            ((IEServerPlayerEntity) player).stopRidingWithoutTeleportRequest();
        }
    
        BlockPos oldPos = player.getBlockPos();
    
        teleportingEntities.add(player);
    
        fromWorld.removePlayer(player);
        player.removed = false;
    
        player.updatePosition(destination.x, destination.y, destination.z);
    
        player.world = toWorld;
        player.dimension = toWorld.dimension.getType();
        toWorld.onPlayerChangeDimension(player);
    
        toWorld.checkChunk(player);
    
        isFiringMyChangeDimensionEvent = true;
        McHelper.getServer().getPlayerManager().sendWorldInfo(
            player, toWorld
        );
        isFiringMyChangeDimensionEvent = false;
    
        player.interactionManager.setWorld(toWorld);
    
        if (vehicle != null) {
            Vec3d vehiclePos = new Vec3d(
                destination.x,
                McHelper.getVehicleY(vehicle, player),
                destination.z
            );
            changeEntityDimension(
                vehicle,
                toWorld.dimension.getType(),
                vehiclePos
            );
            ((IEServerPlayerEntity) player).startRidingWithoutTeleportRequest(vehicle);
            McHelper.adjustVehicle(player);
        }
    
        Helper.log(String.format(
            "%s teleported from %s %s to %s %s",
            player.getName().asString(),
            fromWorld.dimension.getType(),
            oldPos,
            toWorld.dimension.getType(),
            player.getBlockPos()
        ));
    
        //this is used for the advancement of "we need to go deeper"
        //and the advancement of travelling for long distance through nether
        if (toWorld.dimension.getType() == DimensionType.THE_NETHER) {
            //this is used for
            ((IEServerPlayerEntity) player).setEnteredNetherPos(player.getPos());
        }
        ((IEServerPlayerEntity) player).updateDimensionTravelAdvancements(fromWorld);
    
        RequiemCompat.onPlayerTeleportedServer(player);
    }
    
    private void sendPositionConfirmMessage(ServerPlayerEntity player) {
        CustomPayloadS2CPacket packet = MyNetwork.createStcDimensionConfirm(
            player.dimension,
            player.getPos()
        );
        
        player.networkHandler.sendPacket(packet);
    }
    
    private void tick() {
        teleportingEntities = new HashSet<>();
        long tickTimeNow = McHelper.getServerGameTime();
        ArrayList<ServerPlayerEntity> copiedPlayerList =
            McHelper.getCopiedPlayerList();
        if (tickTimeNow % 10 == 7) {
            for (ServerPlayerEntity player : copiedPlayerList) {
                if (!player.notInAnyWorld) {
                    Long lastTeleportGameTime =
                        this.lastTeleportGameTime.getOrDefault(player, 0L);
                    if (tickTimeNow - lastTeleportGameTime > 60) {
                        sendPositionConfirmMessage(player);
                        ((IEServerPlayerEntity) player).setIsInTeleportationState(false);
                    }
                    else {
                        ((IEServerPlayNetworkHandler) player.networkHandler).cancelTeleportRequest();
                    }
                }
            }
        }
        copiedPlayerList.forEach(player -> {
            McHelper.getEntitiesNearby(
                player,
                Entity.class,
                32
            ).filter(
                entity -> !(entity instanceof ServerPlayerEntity)
            ).forEach(entity -> {
                McHelper.getGlobalPortals(entity.world).stream()
                    .filter(
                        globalPortal -> globalPortal.shouldEntityTeleport(entity)
                    )
                    .findFirst()
                    .ifPresent(
                        globalPortal -> tryToTeleportRegularEntity(globalPortal, entity)
                    );
            });
        });
    }
    
    public boolean isTeleporting(ServerPlayerEntity entity) {
        return teleportingEntities.contains(entity);
    }
    
    private void teleportRegularEntity(Entity entity, Portal portal) {
        assert entity.dimension == portal.dimension;
        assert !(entity instanceof ServerPlayerEntity);
    
        long currGameTime = McHelper.getServerGameTime();
        Long lastTeleportGameTime = this.lastTeleportGameTime.getOrDefault(entity, 0L);
        if (currGameTime - lastTeleportGameTime < 5) {
            return;
        }
        this.lastTeleportGameTime.put(entity, currGameTime);
    
        if (entity.hasVehicle() || doesEntityClutterContainPlayer(entity)) {
            return;
        }
        
        Vec3d newPos = portal.applyTransformationToPoint(entity.getPos());
        
        if (portal.dimensionTo != entity.dimension) {
            changeEntityDimension(entity, portal.dimensionTo, newPos);
        }
    
        entity.updatePosition(
            newPos.x, newPos.y, newPos.z
        );
    
        portal.onEntityTeleportedOnServer(entity);
    }
    
    /**
     * {@link Entity#changeDimension(DimensionType)}
     */
    public void changeEntityDimension(
        Entity entity,
        DimensionType toDimension,
        Vec3d destination
    ) {
        ServerWorld fromWorld = (ServerWorld) entity.world;
        ServerWorld toWorld = McHelper.getServer().getWorld(toDimension);
        entity.detach();
    
        fromWorld.removeEntity(entity);
        entity.removed = false;
    
        entity.updatePosition(destination.x, destination.y, destination.z);
    
        entity.world = toWorld;
        entity.dimension = toDimension;
        toWorld.onDimensionChanged(entity);
    }
    
    private boolean doesEntityClutterContainPlayer(Entity entity) {
        if (entity instanceof PlayerEntity) {
            return true;
        }
        List<Entity> passengerList = entity.getPassengerList();
        if (passengerList.isEmpty()) {
            return false;
        }
        return passengerList.stream().anyMatch(this::doesEntityClutterContainPlayer);
    }
    
    public boolean isJustTeleported(Entity entity, long valveTickTime) {
        long currGameTime = McHelper.getServerGameTime();
        Long lastTeleportGameTime = this.lastTeleportGameTime.getOrDefault(entity, 0L);
        return currGameTime - lastTeleportGameTime < valveTickTime;
    }
}
