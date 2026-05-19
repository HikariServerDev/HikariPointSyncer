package net.atsukigames.hikaripointsyncer;

import net.atsukigames.hikaripointsyncer.data.SyncWaypoint;
import net.atsukigames.hikaripointsyncer.server.WaypointManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class HikariPointSyncer implements ModInitializer {
    public static final String MOD_ID = "hikaripointsyncer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier UPLOAD_PACKET = new Identifier(MOD_ID, "upload_waypoint");
    public static final Identifier SET_DELETED_PACKET = new Identifier(MOD_ID, "set_waypoint_deleted");
    public static final Identifier REQUEST_SYNC_PACKET = new Identifier(MOD_ID, "request_sync");

    @Override
    public void onInitialize() {
        LOGGER.info("HikariPointSyncer initialized!");

        ServerLifecycleEvents.SERVER_STARTED.register(WaypointManager::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(WaypointManager::save);
        ServerTickEvents.END_SERVER_TICK.register(WaypointManager::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            WaypointManager.syncToPlayer(handler.player);
        });

        ServerPlayNetworking.registerGlobalReceiver(UPLOAD_PACKET, (server, player, handler, buf, responseSender) -> {
            SyncWaypoint wp = SyncWaypoint.read(buf);
            server.execute(() -> {
                WaypointManager.addOrUpdateWaypoint(server, wp);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SET_DELETED_PACKET, (server, player, handler, buf, responseSender) -> {
            UUID id = buf.readUuid();
            boolean deleted = buf.readBoolean();
            server.execute(() -> {
                // Check permissions: author or OP level 2
                SyncWaypoint wp = WaypointManager.get(id);
                if (wp != null) {
                    boolean isAuthor = wp.author.equals(player.getGameProfile().getName());
                    boolean isOp = server.getPlayerManager().isOperator(player.getGameProfile()) || player.hasPermissionLevel(2);
                    if (isAuthor || isOp) {
                        WaypointManager.markDeleted(server, id, deleted);
                    } else {
                        LOGGER.warn("Player {} tried to modify waypoint {} without permission", player.getName().asString(), id);
                    }
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(REQUEST_SYNC_PACKET, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                WaypointManager.syncToPlayer(player);
            });
        });
    }
}
