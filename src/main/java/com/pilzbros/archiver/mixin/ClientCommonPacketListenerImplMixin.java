package com.pilzbros.archiver.mixin;

import com.pilzbros.archiver.modules.NotifierModule;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every way an in-game connection ends - a kick/ban packet, a timeout, a dropped connection, or one of our own
 * modules disconnecting - goes through {@code onDisconnect} with the reason the disconnect screen will show.
 * Meteor's {@code GameLeftEvent} fires too but doesn't carry that reason, which is the useful part of a notification.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void archiver$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        NotifierModule.onConnectionLost(details.reason());
    }
}
