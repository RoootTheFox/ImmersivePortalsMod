package qouteall.imm_ptl.core.mixin.client.sync;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.q_misc_util.dimension.DimensionTypeSync;
import qouteall.imm_ptl.core.ducks.IEClientPlayNetworkHandler;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.network.IPNetworkAdapt;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.Map;
import java.util.UUID;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener implements IEClientPlayNetworkHandler {
    private static final LimitedLogger immptl_limitedLogger = new LimitedLogger(20);
    
    @Shadow
    private ClientLevel level;
    
    @Shadow
    @Final
    private Minecraft minecraft;
    
    @Mutable
    @Shadow
    @Final
    private Map<UUID, PlayerInfo> playerInfoMap;
    
    @Shadow
    public abstract void handleSetEntityPassengersPacket(ClientboundSetPassengersPacket entityPassengersSetS2CPacket_1);
    
    @Shadow
    protected abstract void applyLightData(int x, int z, ClientboundLightUpdatePacketData data);
    
    @Shadow public abstract RegistryAccess registryAccess();
    
    @Shadow private LayeredRegistryAccess<ClientRegistryLayer> registryAccess;
    
    @Override
    public void ip_setWorld(ClientLevel world) {
        this.level = world;
    }
    
    @Override
    public Map getPlayerListEntries() {
        return playerInfoMap;
    }
    
    @Override
    public void setPlayerListEntries(Map value) {
        playerInfoMap = value;
    }
    
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void onInit(
        Minecraft minecraft, Screen screen, Connection connection, ServerData serverData,
        GameProfile gameProfile, WorldSessionTelemetryManager worldSessionTelemetryManager, CallbackInfo ci
    ) {
        isReProcessingPassengerPacket = false;
    }
    
    @Inject(method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V", at = @At("RETURN"))
    private void onOnGameJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
        ClientWorldLoader.isFlatWorld = packet.isFlat();
        DimensionTypeSync.onGameJoinPacketReceived(packet.registryHolder());
    }
    
    @Inject(
        method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void onProcessingPositionPacket(
        ClientboundPlayerPositionPacket packet,
        CallbackInfo ci
    ) {
        if (!IPNetworkAdapt.doesServerHasIP()) {
            return;
        }
        
        ResourceKey<Level> playerDimension = ((IEPlayerPositionLookS2CPacket) packet).getPlayerDimension();
        
        ClientLevel world = minecraft.level;
        
        if (world != null) {
            if (world.dimension() != playerDimension) {
                if (!Minecraft.getInstance().player.isRemoved()) {
                    immptl_limitedLogger.log(String.format(
                        "denied position packet %s %s %s %s",
                        ((IEPlayerPositionLookS2CPacket) packet).getPlayerDimension(),
                        packet.getX(), packet.getY(), packet.getZ()
                    ));
                    ci.cancel();
                }
            }
        }
        
        IPCGlobal.clientTeleportationManager.disableTeleportFor(5);
        
    }
    
    private boolean isReProcessingPassengerPacket;
    
    @Inject(
        method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleSetEntityPassengersPacket(Lnet/minecraft/network/protocol/game/ClientboundSetPassengersPacket;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void onOnEntityPassengersSet(
        ClientboundSetPassengersPacket entityPassengersSetS2CPacket_1,
        CallbackInfo ci
    ) {
        Entity entity_1 = this.level.getEntity(entityPassengersSetS2CPacket_1.getVehicle());
        if (entity_1 == null) {
            if (!isReProcessingPassengerPacket) {
                Helper.log("Re-processed riding packet");
                IPGlobal.clientTaskList.addTask(() -> {
                    isReProcessingPassengerPacket = true;
                    handleSetEntityPassengersPacket(entityPassengersSetS2CPacket_1);
                    isReProcessingPassengerPacket = false;
                    return true;
                });
                ci.cancel();
            }
        }
    }
    
    // for debug
    @Redirect(
        method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleSetEntityData(Lnet/minecraft/network/protocol/game/ClientboundSetEntityDataPacket;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getEntity(I)Lnet/minecraft/world/entity/Entity;"
        )
    )
    private Entity redirectGetEntityById(ClientLevel clientWorld, int id) {
        Entity entity = clientWorld.getEntity(id);
        if (entity == null) {
            immptl_limitedLogger.err("missing entity for data tracking " + clientWorld + id);
        }
        return entity;
    }
    
    // make sure that the game time is synchronized for all dimensions
    @Inject(
        method = "handleSetTime",
        at = @At("RETURN")
    )
    private void onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            ClientLevel currentWorld = minecraft.level;
            for (ClientLevel clientWorld : ClientWorldLoader.getClientWorlds()) {
                if (clientWorld != currentWorld) {
                    clientWorld.setGameTime(packet.getGameTime());
                }
            }
        }
    }
    
    /**
     * Vanilla has a block change acknowledge system.
     * All player actions that involve block change has a sequence number.
     * The server will send acknowledge packet to client every tick to tell that the server acknowledged the action.
     * In the client, each dimension has a {@link BlockStatePredictionHandler}.
     * When the player is performing action, it will start prediction and all client-side block changes will be enqueued with sequence number.
     * When the server send acknowledge packet, the client will apply and dequeue the block changes with sequence number smaller or equal than the acknowledgement sequence number.
     * When the server sends block update, the block state in the queue of block changes will get updated.
     * As ImmPtl has cross-dimensional block interaction, it needs to acknowledge all worlds.
     * In {@link MixinBlockStatePredictionHandler} the sequence number is kept sync across dimensions.
     */
    @Redirect(
        method = "handleBlockChangedAck",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;handleBlockChangedAck(I)V"
        )
    )
    private void redirectHandleBlockChangedAck(ClientLevel instance, int seqNumber) {
        for (ClientLevel clientWorld : ClientWorldLoader.getClientWorlds()) {
            clientWorld.handleBlockChangedAck(seqNumber);
        }
    }
}
