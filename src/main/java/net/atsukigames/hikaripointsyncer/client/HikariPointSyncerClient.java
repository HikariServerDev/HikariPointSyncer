package net.atsukigames.hikaripointsyncer.client;

import net.atsukigames.hikaripointsyncer.HikariPointSyncer;
import net.atsukigames.hikaripointsyncer.client.gui.SyncWaypointScreen;
import net.atsukigames.hikaripointsyncer.client.network.ClientWaypointManager;
import net.atsukigames.hikaripointsyncer.data.SyncWaypoint;
import net.atsukigames.hikaripointsyncer.server.WaypointManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class HikariPointSyncerClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("hikaripointsyncer-client");

    private static KeyBinding syncWaypointKeyBinding;

    /** サーバー側にHikariPointSyncerが入っているかどうか */
    public static volatile boolean serverHasMod = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("HikariPointSyncer client initialized!");

        syncWaypointKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.hikaripointsyncer.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_CONTROL,
                "category.hikaripointsyncer.main"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (syncWaypointKeyBinding.wasPressed()) {
                if (serverHasMod) {
                    client.setScreen(new SyncWaypointScreen(client.currentScreen));
                }
            }
        });

        // チャンネル登録イベント（サーバーがMODのチャンネルを通知してきた時）
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
            if (channels.contains(WaypointManager.SYNC_ALL_PACKET) || channels.contains(HikariPointSyncer.UPLOAD_PACKET)) {
                serverHasMod = true;
                LOGGER.info("[HPS] サーバーのチャンネル登録を検知: serverHasMod = true");
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            serverHasMod = false;
            LOGGER.info("[HPS] サーバーに接続: serverHasMod = false に初期化");
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            serverHasMod = false;
            ClientWaypointManager.clear();
            LOGGER.info("[HPS] サーバーから切断 – serverHasMod をリセット");
        });

        ClientPlayNetworking.registerGlobalReceiver(WaypointManager.SYNC_ALL_PACKET, (client, handler, buf, responseSender) -> {
            serverHasMod = true;
            LOGGER.info("[HPS] SYNC_ALL_PACKET を受信: serverHasMod = true");
            int size = buf.readInt();
            List<SyncWaypoint> list = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                list.add(SyncWaypoint.read(buf));
            }
            client.execute(() -> {
                // 1. 同期前に、削除されたダウンロード済みウェイポイントをローカルから自動強制削除
                for (SyncWaypoint oldWp : ClientWaypointManager.getLoadedSyncWaypoints()) {
                    boolean stillExists = false;
                    for (SyncWaypoint newWp : list) {
                        if (newWp.id.equals(oldWp.id)) {
                            if (!newWp.isDeleted) {
                                stillExists = true;
                            }
                            break;
                        }
                    }
                    if (!stillExists) {
                        // サーバーで削除（論理削除または完全削除）されたので、ローカルの Xaero からも自動消去
                        net.atsukigames.hikaripointsyncer.client.integration.XaeroIntegration.removeWaypointFromXaeroReflectively(oldWp);
                    }
                }

                ClientWaypointManager.setAll(list);
                if (client.currentScreen instanceof SyncWaypointScreen) {
                    ((SyncWaypointScreen) client.currentScreen).updateList();
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(WaypointManager.SYNC_SINGLE_PACKET, (client, handler, buf, responseSender) -> {
            SyncWaypoint wp = SyncWaypoint.read(buf);
            client.execute(() -> {
                ClientWaypointManager.addOrUpdate(wp);
                if (client.currentScreen instanceof SyncWaypointScreen) {
                    ((SyncWaypointScreen) client.currentScreen).updateList();
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(WaypointManager.DELETE_FORCE_PACKET, (client, handler, buf, responseSender) -> {
            UUID id = buf.readUuid();
            client.execute(() -> {
                ClientWaypointManager.forceDelete(id);
                if (client.currentScreen instanceof SyncWaypointScreen) {
                    ((SyncWaypointScreen) client.currentScreen).updateList();
                }
            });
        });
        
        UpdateChecker.checkForUpdates();
    }
}
