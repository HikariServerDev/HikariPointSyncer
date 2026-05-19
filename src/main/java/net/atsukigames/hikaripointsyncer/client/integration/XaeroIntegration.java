package net.atsukigames.hikaripointsyncer.client.integration;

import net.atsukigames.hikaripointsyncer.HikariPointSyncer;
import net.atsukigames.hikaripointsyncer.data.SyncWaypoint;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ServerInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Xaero's World Map のウェイポイントファイルを読み書きするアダプター。
 * Y座標が `~` の場合は Y_UNKNOWN (-9999) とし、表示時には `~` に変換する。
 */
public class XaeroIntegration {

    private static final Path XAERO_DIR = FabricLoader.getInstance().getGameDir().resolve("xaero").resolve("minimap");
    private static final Path DOWNLOADED_IDS_FILE = FabricLoader.getInstance().getGameDir().resolve("hps_downloaded.txt");

    /** Y座標の未指定(~)を表すセンチネル値 */
    public static final int Y_UNKNOWN = -9999;

    /** ダウンロード済みWaypointのUUIDセット（永続化） */
    private static final Set<UUID> downloadedIds = new HashSet<>();
    private static boolean loadedIds = false;

    /** ダウンロード済みUUIDセットをファイルから読み込む */
    private static void ensureIdsLoaded() {
        if (loadedIds) return;
        loadedIds = true;
        try {
            if (Files.exists(DOWNLOADED_IDS_FILE)) {
                for (String line : Files.readAllLines(DOWNLOADED_IDS_FILE, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        try { downloadedIds.add(UUID.fromString(trimmed)); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            HikariPointSyncer.LOGGER.error("[HPS] ダウンロード済みIDリストの読み込みに失敗", e);
        }
    }

    /** ダウンロード済みUUIDをファイルに保存する */
    private static void saveDownloadedIds() {
        try {
            List<String> lines = downloadedIds.stream().map(UUID::toString).collect(Collectors.toList());
            Files.write(DOWNLOADED_IDS_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            HikariPointSyncer.LOGGER.error("[HPS] ダウンロード済みIDリストの保存に失敗", e);
        }
    }

    /**
     * 指定されたWaypointがダウンロード済みかつローカルに実際に存在するかを返す。
     * 永続化セットにUUIDが存在しても、ローカルから削除された場合は false を返し、
     * セットからも除去して再DLを可能にする。
     */
    public static boolean hasWaypoint(SyncWaypoint wp) {
        ensureIdsLoaded();
        if (!downloadedIds.contains(wp.id)) return false;

        // 実際にローカルファイルまたはメモリに存在するか確認（非同期保存のタイムラグ対策）
        boolean existsLocally = false;
        try {
            List<SyncWaypoint> combined = new ArrayList<>();
            combined.addAll(getLocalWaypoints());
            combined.addAll(getMemoryWaypoints());

            for (SyncWaypoint local : combined) {
                if (local.name.equalsIgnoreCase(wp.name)
                    && local.x == wp.x
                    && local.z == wp.z) {
                    existsLocally = true;
                    break;
                }
            }
        } catch (Exception ignored) {}

        if (!existsLocally) {
            // ローカルから削除済みなのでセットからも除去し、再DLを許可する
            downloadedIds.remove(wp.id);
            saveDownloadedIds();
            return false;
        }
        return true;
    }

    /**
     * 指定されたWaypointを現在のプレイヤーが削除できるかどうかを返す。
     * アップロードした本人（author）、またはOP権限（レベル2以上）があるプレイヤーのみ削除可能。
     */
    public static boolean canDelete(SyncWaypoint wp) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getSession() == null) return false;
        
        // 1. 作成した本人であるか確認
        String myName = mc.getSession().getUsername();
        if (myName != null && myName.equalsIgnoreCase(wp.author)) {
            return true;
        }

        // 2. OP権限（権限レベル2以上）があるか確認
        if (mc.player != null && mc.player.hasPermissionLevel(2)) {
            return true;
        }

        return false;
    }

    public static class XaeroWorldSet {
        public final String subWorldName; // "default", "386166032_2" など
        public final String dimension;    // "overworld", "the_nether" など
        public final Path filePath;       // .txt ファイルへの直接パス

        public XaeroWorldSet(String subWorldName, String dimension, Path filePath) {
            this.subWorldName = subWorldName;
            this.dimension = dimension;
            this.filePath = filePath;
        }

        @Override
        public String toString() {
            return subWorldName + " (" + dimension + ")";
        }
    }

    /**
     * 反射的にXaeroの現在のメモリ空間にWaypointを追加し、ディスクに即時セーブする。
     * アップロード元の重複ローカルWaypointが存在する場合は、自動削除する。
     */
    @SuppressWarnings("unchecked")
    public static boolean addWaypointToXaeroReflectively(SyncWaypoint wp) {
        try {
            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null) return false;

            Object manager = session.getClass().getMethod("getWaypointsManager").invoke(session);
            if (manager == null) return false;

            Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
            if (world == null) return false;

            Object container = world.getClass().getMethod("getContainer").invoke(world);
            if (container == null) return false;

            Collection<?> worlds = (Collection<?>) container.getClass().getMethod("getWorlds").invoke(container);
            if (worlds == null) return false;

            // ディメンションに対応するキーを作成
            String targetKey = "dim%0";
            if (wp.dimension.equalsIgnoreCase("the_nether")) targetKey = "dim%-1";
            else if (wp.dimension.equalsIgnoreCase("the_end")) targetKey = "dim%1";
            else if (wp.dimension.startsWith("dim_")) targetKey = wp.dimension.replace("dim_", "dim%");

            Object targetWorld = null;
            for (Object w : worlds) {
                String worldId = getWorldIdReflectively(w);
                if (worldId != null && (worldId.equalsIgnoreCase(targetKey) || worldId.endsWith(targetKey) || worldId.contains(targetKey))) {
                    targetWorld = w;
                    break;
                }
            }

            if (targetWorld == null) {
                targetWorld = world; // フォールバック
            }

            Object waypointSet = targetWorld.getClass().getMethod("getCurrentSet").invoke(targetWorld);
            if (waypointSet == null) return false;

            List<Object> list = (List<Object>) waypointSet.getClass().getMethod("getList").invoke(waypointSet);
            if (list == null) return false;

            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            java.lang.reflect.Constructor<?> constructor = waypointClass.getConstructor(
                int.class, int.class, int.class, String.class, String.class, int.class
            );

            int y = (wp.y == Y_UNKNOWN) ? 64 : wp.y;
            String wpName = wp.name;

            // フィールドアクセスヘルパー（privateフィールド対応）
            java.lang.reflect.Field nameField = waypointClass.getDeclaredField("name");
            nameField.setAccessible(true);
            java.lang.reflect.Field xField = waypointClass.getDeclaredField("x");
            xField.setAccessible(true);
            java.lang.reflect.Field zField = waypointClass.getDeclaredField("z");
            zField.setAccessible(true);

            // 1. 同名・同座標(X, Z)の既存のWaypointをすべて除去 (重複対応)
            List<Object> toRemove = new ArrayList<>();
            for (Object existingWp : list) {
                String name = (String) nameField.get(existingWp);
                int wx = xField.getInt(existingWp);
                int wz = zField.getInt(existingWp);
                // サフィックス付きの古いデータもマッチさせて除去できるように、包含関係やトリミング処理を入れておく
                String cleanName = getCleanName(name);
                if (cleanName.equalsIgnoreCase(wp.name) && wx == wp.x && wz == wp.z) {
                    toRemove.add(existingWp);
                }
            }
            for (Object r : toRemove) {
                list.remove(r);
            }

            // 3. ダウンロードしたWaypointをメモリに追加
            // ColorId を wp.color を使用
            Object newWp = constructor.newInstance(wp.x, y, wp.z, wpName, wp.initial, wp.color);
            list.add(newWp);

            // 4. 即時保存メソッドを呼び出す
            // saveWaypoints のシグネチャはバージョンにより異なるため、全メソッドを走査して呼び出す
            boolean saved = false;
            for (java.lang.reflect.Method m : manager.getClass().getMethods()) {
                if (!m.getName().toLowerCase().contains("save")) continue;
                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 0) {
                        m.invoke(manager);
                        saved = true;
                        break;
                    } else if (params.length == 1 && params[0].isInstance(targetWorld)) {
                        m.invoke(manager, targetWorld);
                        saved = true;
                        break;
                    } else if (params.length == 1 && params[0].isInstance(container)) {
                        m.invoke(manager, container);
                        saved = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (!saved) {
                // フォールバック: getDeclaredMethods も試みる
                for (java.lang.reflect.Method m : manager.getClass().getDeclaredMethods()) {
                    if (!m.getName().toLowerCase().contains("save")) continue;
                    m.setAccessible(true);
                    Class<?>[] params = m.getParameterTypes();
                    try {
                        if (params.length == 0) {
                            m.invoke(manager);
                            saved = true;
                            break;
                        } else if (params.length == 1 && params[0].isInstance(targetWorld)) {
                            m.invoke(manager, targetWorld);
                            saved = true;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (!saved) {
                HikariPointSyncer.LOGGER.warn("[HPS] saveWaypointsメソッドが見つかりませんでした。メモリへの追加のみ実施します。");
            }

            // ダウンロード済みUUIDを永続化
            ensureIdsLoaded();
            downloadedIds.add(wp.id);
            saveDownloadedIds();

            HikariPointSyncer.LOGGER.info("[HPS] WaypointをXaeroに保存完了: " + wp.name);
            return true;
        } catch (Exception e) {
            HikariPointSyncer.LOGGER.error("[HPS] Xaeroへの反射的保存に失敗しました: " + wp.name, e);
            return false;
        }
    }

    /**
     * 表示用のサーバー名に変換（Multiplayer_ プレフィックスを剥ぎ取り、servers.datから表示名を取得）
     */
    public static String getDisplayName(String folderName) {
        if (folderName.startsWith("Multiplayer_")) {
            String addr = folderName.substring("Multiplayer_".length()).replace("_", ":");
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                net.minecraft.client.option.ServerList serverList = new net.minecraft.client.option.ServerList(client);
                serverList.loadFile();
                for (int i = 0; i < serverList.size(); i++) {
                    ServerInfo info = serverList.get(i);
                    if (info.address.replace(":", "_").equalsIgnoreCase(addr.replace(":", "_"))) {
                        return info.name;
                    }
                }
            } catch (Exception ignored) {}
            return addr.split(":")[0]; // fallback: ポートなしアドレス
        }
        return folderName;
    }

    /**
     * 死亡地点の翻訳キーやUUIDタグをクリーンにする
     */
    public static String getCleanName(String name) {
        if (name.equals("gui.xaero_deathpoint") || name.equals("gui.xearo_deathpoint")) {
            return "死亡地点";
        }
        if (name.contains("[hps:")) {
            int idx = name.indexOf("[hps:");
            return name.substring(0, idx).trim();
        }
        return name;
    }

    /**
     * xaero/minimap フォルダ内のサーバー・ワールド名（フォルダ名）一覧を取得する
     */
    public static List<String> getServerOrWorldList() {
        if (!Files.exists(XAERO_DIR)) return Collections.emptyList();
        try (Stream<Path> stream = Files.list(XAERO_DIR)) {
            return stream
                .filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .filter(name -> !name.equals("backup"))
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            HikariPointSyncer.LOGGER.error("xaero/minimap フォルダの読み込みに失敗", e);
            return Collections.emptyList();
        }
    }

    /**
     * サーバー名フォルダ内の Sub-World と Dimension の組み合わせを取得する
     */
    public static List<XaeroWorldSet> getSubWorldsAndDimensions(String serverOrWorld) {
        List<XaeroWorldSet> list = new ArrayList<>();
        Path serverDir = XAERO_DIR.resolve(serverOrWorld);
        if (!Files.exists(serverDir)) return list;

        try (Stream<Path> dims = Files.list(serverDir)) {
            List<Path> dimDirs = dims.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("dim%"))
                .sorted()
                .collect(Collectors.toList());

            for (Path dimDir : dimDirs) {
                String dimId = dimDir.getFileName().toString().replace("dim%", "");
                String friendlyDim = mapFriendlyDimension(dimId);

                try (Stream<Path> files = Files.list(dimDir)) {
                    List<Path> txtFiles = files.filter(f -> f.getFileName().toString().endsWith(".txt"))
                        .sorted()
                        .collect(Collectors.toList());

                    for (Path txtFile : txtFiles) {
                        String fileName = txtFile.getFileName().toString();
                        String subWorld = "default";
                        if (fileName.startsWith("mw$") && fileName.endsWith(".txt")) {
                            subWorld = fileName.substring(3, fileName.length() - 4);
                        }
                        list.add(new XaeroWorldSet(subWorld, friendlyDim, txtFile));
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}

        return list;
    }

    /**
     * ディメンションIDをマイクラの一般的な名称にマッピング
     */
    public static String mapFriendlyDimension(String dimId) {
        if ("0".equals(dimId)) return "overworld";
        if ("-1".equals(dimId)) return "the_nether";
        if ("1".equals(dimId)) return "the_end";
        return "dim_" + dimId;
    }

    /**
     * 特定の XaeroWorldSet のファイルからウェイポイントをロードする
     */
    public static List<SyncWaypoint> getWaypointsFromSet(XaeroWorldSet set, String serverOrWorldName) {
        List<SyncWaypoint> result = new ArrayList<>();
        if (set == null || set.filePath == null || !Files.exists(set.filePath)) return result;
        result.addAll(parseWaypointFile(set.filePath, set.dimension, serverOrWorldName));
        return result;
    }

    /**
     * Xaero'sのウェイポイントファイルをパースして SyncWaypoint のリストに変換する
     */
    private static List<SyncWaypoint> parseWaypointFile(Path file, String dimName, String serverOrWorldName) {
        List<SyncWaypoint> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                if (line.startsWith("sets:")) continue;
                if (!line.startsWith("waypoint:")) continue;

                try {
                    // waypoint:Name:Initial:X:Y:Z:Color:Disabled:Type:Set:RotateOnTP:TpYaw:VisType:Dest
                    String[] parts = line.split(":", -1);
                    if (parts.length < 7) continue;

                    String name = parts[1];
                    String initial = parts[2];
                    int x = Integer.parseInt(parts[3]);

                    // Y座標が ~ の場合をサポート
                    int y = parts[4].equals("~") ? Y_UNKNOWN : Integer.parseInt(parts[4]);
                    int z = Integer.parseInt(parts[5]);

                    int color = 2; // デフォルトは黄緑
                    if (parts.length > 6 && !parts[6].isEmpty()) {
                        try {
                            color = Integer.parseInt(parts[6]);
                        } catch (NumberFormatException ignored) {}
                    }

                    // UUIDを名前から抽出
                    UUID id = UUID.randomUUID();
                    if (name.contains("[hps:")) {
                        int idx = name.indexOf("[hps:");
                        String uuidStr = name.substring(idx + 5, name.indexOf("]", idx));
                        try {
                            id = UUID.fromString(uuidStr);
                            name = name.substring(0, idx).trim();
                        } catch (Exception ignored) {}
                    }

                    // 死亡地点等の翻訳キーを日本語に変換
                    name = getCleanName(name);

                    SyncWaypoint wp = new SyncWaypoint(
                        id,
                        name,
                        initial,
                        x, y, z,
                        dimName,
                        MinecraftClient.getInstance().getSession() != null
                            ? MinecraftClient.getInstance().getSession().getUsername()
                            : "Unknown",
                        System.currentTimeMillis(),
                        false,
                        0L,
                        color
                    );
                    list.add(wp);
                } catch (Exception e) {
                    HikariPointSyncer.LOGGER.warn("ウェイポイント行のパースに失敗 (スキップします): " + line + " - エラー: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            HikariPointSyncer.LOGGER.error("ウェイポイントファイルの読み込みに失敗: " + file, e);
        }
        return list;
    }

    /**
     * 現在接続中のサーバーに対応するローカルウェイポイントを取得する
     */
    public static List<SyncWaypoint> getLocalWaypoints() {
        MinecraftClient client = MinecraftClient.getInstance();
        List<SyncWaypoint> all = new ArrayList<>();
        if (client.getCurrentServerEntry() != null) {
            String serverName = "Multiplayer_" + client.getCurrentServerEntry().address.replace(":", "_");
            for (XaeroWorldSet set : getSubWorldsAndDimensions(serverName)) {
                all.addAll(getWaypointsFromSet(set, serverName));
            }
        }
        if (all.isEmpty()) {
            for (String s : getServerOrWorldList()) {
                for (XaeroWorldSet set : getSubWorldsAndDimensions(s)) {
                    all.addAll(getWaypointsFromSet(set, s));
                }
            }
        }
        return all;
    }

    private static String getWorldIdReflectively(Object world) {
        if (world == null) return null;
        // Try getId()
        try {
            return (String) world.getClass().getMethod("getId").invoke(world);
        } catch (Exception ignored) {}
        // Try getKey()
        try {
            return (String) world.getClass().getMethod("getKey").invoke(world);
        } catch (Exception ignored) {}
        // Try id field
        try {
            return (String) world.getClass().getField("id").get(world);
        } catch (Exception ignored) {}
        // Try key field
        try {
            return (String) world.getClass().getField("key").get(world);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 現在メモリ上にロードされている Xaero のウェイポイントリストを取得する。
     */
    @SuppressWarnings("unchecked")
    public static List<SyncWaypoint> getMemoryWaypoints() {
        List<SyncWaypoint> all = new ArrayList<>();
        try {
            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null) return all;

            Object manager = session.getClass().getMethod("getWaypointsManager").invoke(session);
            if (manager == null) return all;

            Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
            if (world == null) return all;

            Object container = world.getClass().getMethod("getContainer").invoke(world);
            if (container == null) return all;

            Collection<?> worlds = (Collection<?>) container.getClass().getMethod("getWorlds").invoke(container);
            if (worlds == null) return all;

            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            java.lang.reflect.Field nameField = waypointClass.getDeclaredField("name");
            nameField.setAccessible(true);
            java.lang.reflect.Field xField = waypointClass.getDeclaredField("x");
            xField.setAccessible(true);
            java.lang.reflect.Field yField = waypointClass.getDeclaredField("y");
            yField.setAccessible(true);
            java.lang.reflect.Field zField = waypointClass.getDeclaredField("z");
            zField.setAccessible(true);

            for (Object w : worlds) {
                String worldId = getWorldIdReflectively(w); // "dim%0" など
                String dimName = "overworld";
                if (worldId != null) {
                    if (worldId.contains("dim%-1")) dimName = "the_nether";
                    else if (worldId.contains("dim%1")) dimName = "the_end";
                    else if (worldId.contains("dim%")) {
                        dimName = "dim_" + worldId.substring(worldId.indexOf("dim%") + 4);
                    }
                }

                // XaeroWorld 内の全セットのウェイポイントをスキャン
                java.lang.reflect.Method getSetsMethod = null;
                try {
                    getSetsMethod = w.getClass().getMethod("getSets");
                } catch (NoSuchMethodException ignored) {}

                if (getSetsMethod != null) {
                    Map<String, Object> setsMap = (Map<String, Object>) getSetsMethod.invoke(w);
                    if (setsMap != null) {
                        for (Object setObj : setsMap.values()) {
                            List<Object> list = (List<Object>) setObj.getClass().getMethod("getList").invoke(setObj);
                            if (list != null) {
                                for (Object wpObj : list) {
                                    String name = (String) nameField.get(wpObj);
                                    int x = xField.getInt(wpObj);
                                    int y = yField.getInt(wpObj);
                                    int z = zField.getInt(wpObj);
                                    
                                    all.add(new SyncWaypoint(
                                        UUID.randomUUID(),
                                        getCleanName(name),
                                        "", x, y, z,
                                        dimName,
                                        "Unknown",
                                        System.currentTimeMillis(),
                                        false, 0L, 2
                                    ));
                                }
                            }
                        }
                    }
                }
            }

            // 現在のセット（getCurrentSet）も追加のフォールバックとして確実にスキャン
            try {
                Object curSet = world.getClass().getMethod("getCurrentSet").invoke(world);
                if (curSet != null) {
                    List<Object> list = (List<Object>) curSet.getClass().getMethod("getList").invoke(curSet);
                    if (list != null) {
                        for (Object wpObj : list) {
                            String name = (String) nameField.get(wpObj);
                            int x = xField.getInt(wpObj);
                            int y = yField.getInt(wpObj);
                            int z = zField.getInt(wpObj);
                            
                            all.add(new SyncWaypoint(
                                UUID.randomUUID(),
                                getCleanName(name),
                                "", x, y, z,
                                "overworld", // fallback
                                "Unknown",
                                System.currentTimeMillis(),
                                false, 0L, 2
                            ));
                        }
                    }
                }
            } catch (Exception ignored) {}

        } catch (Exception ignored) {}
        return all;
    }

    /**
     * 反射的にXaeroの現在のメモリ空間から指定されたWaypointを削除し、ディスクに即時セーブする。
     */
    @SuppressWarnings("unchecked")
    public static boolean removeWaypointFromXaeroReflectively(SyncWaypoint wp) {
        try {
            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null) return false;

            Object manager = session.getClass().getMethod("getWaypointsManager").invoke(session);
            if (manager == null) return false;

            Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
            if (world == null) return false;

            Object container = world.getClass().getMethod("getContainer").invoke(world);
            if (container == null) return false;

            Collection<?> worlds = (Collection<?>) container.getClass().getMethod("getWorlds").invoke(container);
            if (worlds == null) return false;

            // ディメンションキー
            String targetKey = "dim%0";
            if (wp.dimension.equalsIgnoreCase("the_nether")) targetKey = "dim%-1";
            else if (wp.dimension.equalsIgnoreCase("the_end")) targetKey = "dim%1";
            else if (wp.dimension.startsWith("dim_")) targetKey = wp.dimension.replace("dim_", "dim%");

            Object targetWorld = null;
            for (Object w : worlds) {
                String worldId = getWorldIdReflectively(w);
                if (worldId != null && (worldId.equalsIgnoreCase(targetKey) || worldId.endsWith(targetKey) || worldId.contains(targetKey))) {
                    targetWorld = w;
                    break;
                }
            }

            if (targetWorld == null) {
                targetWorld = world; // フォールバック
            }

            Object waypointSet = targetWorld.getClass().getMethod("getCurrentSet").invoke(targetWorld);
            if (waypointSet == null) return false;

            List<Object> list = (List<Object>) waypointSet.getClass().getMethod("getList").invoke(waypointSet);
            if (list == null) return false;

            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            java.lang.reflect.Field nameField = waypointClass.getDeclaredField("name");
            nameField.setAccessible(true);
            java.lang.reflect.Field xField = waypointClass.getDeclaredField("x");
            xField.setAccessible(true);
            java.lang.reflect.Field zField = waypointClass.getDeclaredField("z");
            zField.setAccessible(true);

            // 同名・同座標のウェイポイントを探して削除
            List<Object> toRemove = new ArrayList<>();
            for (Object existingWp : list) {
                String name = (String) nameField.get(existingWp);
                int wx = xField.getInt(existingWp);
                int wz = zField.getInt(existingWp);
                String cleanName = getCleanName(name);
                if (cleanName.equalsIgnoreCase(wp.name) && wx == wp.x && wz == wp.z) {
                    toRemove.add(existingWp);
                }
            }

            if (toRemove.isEmpty()) {
                return false; // メモリ上に見つからなかった
            }

            for (Object r : toRemove) {
                list.remove(r);
            }

            // 即時保存メソッドを呼び出す
            boolean saved = false;
            for (java.lang.reflect.Method m : manager.getClass().getMethods()) {
                if (!m.getName().toLowerCase().contains("save")) continue;
                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 0) {
                        m.invoke(manager);
                        saved = true;
                        break;
                    } else if (params.length == 1 && params[0].isInstance(targetWorld)) {
                        m.invoke(manager, targetWorld);
                        saved = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (!saved) {
                for (java.lang.reflect.Method m : manager.getClass().getDeclaredMethods()) {
                    if (!m.getName().toLowerCase().contains("save")) continue;
                    m.setAccessible(true);
                    Class<?>[] params = m.getParameterTypes();
                    try {
                        if (params.length == 0) {
                            m.invoke(manager);
                            saved = true;
                            break;
                        } else if (params.length == 1 && params[0].isInstance(targetWorld)) {
                            m.invoke(manager, targetWorld);
                            saved = true;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }

            // ダウンロード済みリストからも確実に削除
            ensureIdsLoaded();
            downloadedIds.remove(wp.id);
            saveDownloadedIds();

            return true;
        } catch (Exception e) {
            HikariPointSyncer.LOGGER.error("[HPS] Xaeroからの反射的削除に失敗しました: " + wp.name, e);
            return false;
        }
    }
}
