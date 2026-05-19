package net.atsukigames.hikaripointsyncer.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.atsukigames.hikaripointsyncer.HikariPointSyncer;
import net.atsukigames.hikaripointsyncer.data.SyncWaypoint;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WaypointManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ConcurrentHashMap<UUID, SyncWaypoint> waypoints = new ConcurrentHashMap<>();
    private static final long THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000;
    
    public static final Identifier SYNC_ALL_PACKET = new Identifier(HikariPointSyncer.MOD_ID, "sync_all");
    public static final Identifier SYNC_SINGLE_PACKET = new Identifier(HikariPointSyncer.MOD_ID, "sync_single");
    public static final Identifier DELETE_FORCE_PACKET = new Identifier(HikariPointSyncer.MOD_ID, "delete_force");

    public static void load(MinecraftServer server) {
        waypoints.clear();
        Path savePath = server.getSavePath(WorldSavePath.ROOT).resolve("hikari_waypoints.json");
        if (Files.exists(savePath)) {
            try (FileReader reader = new FileReader(savePath.toFile())) {
                List<SyncWaypoint> loaded = GSON.fromJson(reader, new TypeToken<List<SyncWaypoint>>(){}.getType());
                if (loaded != null) {
                    for (SyncWaypoint wp : loaded) {
                        waypoints.put(wp.id, wp);
                    }
                }
            } catch (IOException e) {
                HikariPointSyncer.LOGGER.error("Failed to load waypoints", e);
            }
        }
    }

    public static void save(MinecraftServer server) {
        Path savePath = server.getSavePath(WorldSavePath.ROOT).resolve("hikari_waypoints.json");
        try (FileWriter writer = new FileWriter(savePath.toFile())) {
            GSON.toJson(new ArrayList<>(waypoints.values()), writer);
        } catch (IOException e) {
            HikariPointSyncer.LOGGER.error("Failed to save waypoints", e);
        }
    }

    public static void tick(MinecraftServer server) {
        if (server.getTicks() % 1200 == 0) { // Every minute
            checkExpirations(server);
            save(server);
        }
    }

    private static void checkExpirations(MinecraftServer server) {
        long now = System.currentTimeMillis();
        List<UUID> toRemove = new ArrayList<>();
        
        for (SyncWaypoint wp : waypoints.values()) {
            if (wp.isDeleted && now - wp.deletedTime > THIRTY_DAYS_MS) {
                toRemove.add(wp.id);
            }
        }

        if (!toRemove.isEmpty()) {
            Path deletedFile = server.getSavePath(WorldSavePath.ROOT).resolve("deleted_waypoints_uuids.txt");
            try {
                for (UUID id : toRemove) {
                    Files.writeString(deletedFile, id.toString() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    waypoints.remove(id);
                    broadcastForceDelete(server, id);
                }
            } catch (IOException e) {
                HikariPointSyncer.LOGGER.error("Failed to append to deleted list", e);
            }
        }
    }
    
    public static void addOrUpdateWaypoint(MinecraftServer server, SyncWaypoint wp) {
        waypoints.put(wp.id, wp);
        save(server);
        broadcastSingle(server, wp);
    }
    
    public static void markDeleted(MinecraftServer server, UUID id, boolean deleted) {
        SyncWaypoint wp = waypoints.get(id);
        if (wp != null) {
            wp.isDeleted = deleted;
            if (deleted) {
                wp.deletedTime = System.currentTimeMillis();
                save(server);
                broadcastSingle(server, wp);
                // 共有リストから削除（論理削除）された時点で、全プレイヤーのクライアントからWaypointを強制削除
                broadcastForceDelete(server, id);
            } else {
                wp.deletedTime = 0;
                save(server);
                broadcastSingle(server, wp);
            }
        }
    }

    public static void syncToPlayer(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        List<SyncWaypoint> list = new ArrayList<>(waypoints.values());
        buf.writeInt(list.size());
        for (SyncWaypoint wp : list) {
            wp.write(buf);
        }
        ServerPlayNetworking.send(player, SYNC_ALL_PACKET, buf);
    }

    private static void broadcastSingle(MinecraftServer server, SyncWaypoint wp) {
        PacketByteBuf buf = PacketByteBufs.create();
        wp.write(buf);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, SYNC_SINGLE_PACKET, buf);
        }
    }

    private static void broadcastForceDelete(MinecraftServer server, UUID id) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(id);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, DELETE_FORCE_PACKET, buf);
        }
    }
    
    public static SyncWaypoint get(UUID id) {
        return waypoints.get(id);
    }
}
