package net.atsukigames.hikaripointsyncer.client.gui;

import net.atsukigames.hikaripointsyncer.HikariPointSyncer;
import net.atsukigames.hikaripointsyncer.client.HikariPointSyncerClient;
import net.atsukigames.hikaripointsyncer.client.network.ClientWaypointManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * SyncWaypointScreen: サーバーで共有されているWaypointを全表示する画面。
 * World/Server・Sub-World/Dimension の選択は不要（サーバー同期済みデータをそのまま表示）。
 *
 * レイアウト:
 *   [タブ: 共有中のWaypoint] [タブ: 削除したWaypoint]  (画面上部中央寄せ・ブラックアウト対応)
 *   ─── Waypoint一覧 (名前・ディメンション・座標) ───
 *   [ローカルからアップロード]
 */
public class SyncWaypointScreen extends Screen {

    private final Screen parent;
    private int tab = 0; // 0=共有中, 1=削除済み
    private SharedWaypointListWidget list;
    private int tickCounter = 0;

    private ButtonWidget sharedTabBtn;
    private ButtonWidget deletedTabBtn;

    // タブ高さ
    private static final int TAB_Y = 4;
    private static final int TAB_H = 18;
    private static final int LIST_TOP = TAB_Y + TAB_H + 2;
    private static final int BOT_H = 28;

    public SyncWaypointScreen(Screen parent) {
        super(new LiteralText("Sync Waypoints"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int half = this.width / 2;

        // ── タブ: 中央寄せで配置 ──
        int tabW = 150; // マイクラGUIサイズに合わせる
        int gap = 4;
        int totalW = (tabW * 2) + gap;
        int startX = half - (totalW / 2);

        sharedTabBtn = new ButtonWidget(startX, TAB_Y, tabW, TAB_H,
            new LiteralText("共有中のWaypoint"), b -> { 
                tab = 0; 
                refresh(); 
                updateTabStates();
            }
        );
        this.addDrawableChild(sharedTabBtn);

        deletedTabBtn = new ButtonWidget(startX + tabW + gap, TAB_Y, tabW, TAB_H,
            new LiteralText("削除したWaypoint"), b -> { 
                tab = 1; 
                refresh(); 
                updateTabStates();
            }
        );
        this.addDrawableChild(deletedTabBtn);

        updateTabStates();

        // ── Waypointリスト ──
        list = new SharedWaypointListWidget(
            this.client, this.width, this.height,
            LIST_TOP, this.height - BOT_H, 36, this
        );
        this.addSelectableChild(list);
        refresh();

        // ── ローカルからアップロード ──
        ButtonWidget up = new ButtonWidget(
            this.width / 2 - 100, this.height - BOT_H + 4, 200, 20,
            new LiteralText("ローカルからアップロード"),
            b -> { if (client != null) client.setScreen(new LocalWaypointScreen(this)); }
        );
        up.active = HikariPointSyncerClient.serverHasMod;
        this.addDrawableChild(up);
    }

    private void updateTabStates() {
        if (sharedTabBtn != null) sharedTabBtn.active = (tab != 0);
        if (deletedTabBtn != null) deletedTabBtn.active = (tab != 1);
    }

    private void refresh() {
        if (list == null) return;
        list.updateEntries(
            tab == 1 ? ClientWaypointManager.getAllDeleted() : ClientWaypointManager.getAllShared(),
            tab == 1
        );
    }

    public void updateList() { refresh(); }

    @Override
    public void tick() {
        super.tick();
        if (tickCounter++ % 20 == 0) { // 20 ticks = 1 second
            if (HikariPointSyncerClient.serverHasMod) {
                ClientPlayNetworking.send(HikariPointSyncer.REQUEST_SYNC_PACKET, PacketByteBufs.create());
            }
        }
    }

    @Override
    public void render(MatrixStack m, int mouseX, int mouseY, float delta) {
        this.renderBackground(m);
        list.render(m, mouseX, mouseY, delta);
        super.render(m, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
