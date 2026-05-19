package net.atsukigames.hikaripointsyncer.client.network;

import net.atsukigames.hikaripointsyncer.data.SyncWaypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientWaypointManager {
    private static final ConcurrentHashMap<UUID, SyncWaypoint> waypoints = new ConcurrentHashMap<>();

    public static void setAll(List<SyncWaypoint> list) {
        waypoints.clear();
        for (SyncWaypoint wp : list) {
            waypoints.put(wp.id, wp);
        }
    }

    public static void addOrUpdate(SyncWaypoint wp) {
        waypoints.put(wp.id, wp);
    }

    public static void forceDelete(UUID id) {
        SyncWaypoint wp = waypoints.get(id);
        if (wp != null) {
            // ローカルの Xaero フォルダ・メモリから強制削除を実行
            net.atsukigames.hikaripointsyncer.client.integration.XaeroIntegration.removeWaypointFromXaeroReflectively(wp);
        }
        waypoints.remove(id);
    }

    public static List<SyncWaypoint> getLoadedSyncWaypoints() {
        return new ArrayList<>(waypoints.values());
    }

    public static List<SyncWaypoint> getAllShared() {
        List<SyncWaypoint> shared = new ArrayList<>();
        for (SyncWaypoint wp : waypoints.values()) {
            if (!wp.isDeleted) {
                shared.add(wp);
            }
        }
        return shared;
    }

    public static List<SyncWaypoint> getAllDeleted() {
        List<SyncWaypoint> deleted = new ArrayList<>();
        for (SyncWaypoint wp : waypoints.values()) {
            if (wp.isDeleted) {
                deleted.add(wp);
            }
        }
        return deleted;
    }

    /** サーバーから切断した際にリストを空にする */
    public static void clear() {
        waypoints.clear();
    }
}
