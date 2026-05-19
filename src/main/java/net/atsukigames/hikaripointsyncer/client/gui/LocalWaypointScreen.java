package net.atsukigames.hikaripointsyncer.client.gui;

import net.atsukigames.hikaripointsyncer.HikariPointSyncer;
import net.atsukigames.hikaripointsyncer.client.integration.XaeroIntegration;
import net.atsukigames.hikaripointsyncer.client.integration.XaeroIntegration.XaeroWorldSet;
import net.atsukigames.hikaripointsyncer.client.network.ClientWaypointManager;
import net.atsukigames.hikaripointsyncer.data.SyncWaypoint;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * ローカルWaypointアップロード画面。
 *
 * 上部: World/Server プルダウン  |  Sub-World/Dimension プルダウン  (Xaero's スタイル)
 * 中部: ウェイポイント一覧 (名前・ディメンション・座標・アップロードボタン)
 * 下部: 戻るボタン
 */
public class LocalWaypointScreen extends Screen {

    private final Screen parent;

    // 内部データ
    private List<String> serverFolderList = new ArrayList<>();
    private List<XaeroWorldSet> subWorldSets = new ArrayList<>();

    // 画面表示用リスト
    private List<String> serverDisplayList = new ArrayList<>();
    private List<String> subWorldDisplayList = new ArrayList<>();

    private DropdownWidget serverDrop;
    private DropdownWidget dimDrop;

    private UploadListWidget listWidget;

    // レイアウト定数
    private static final int LBL_Y  = 3;   // "World/Server" ラベル Y
    private static final int DROP_Y  = 13;  // プルダウン Y
    private static final int DROP_H  = 14;  // プルダウン高さ
    private static final int LIST_TOP = DROP_Y + DROP_H + 4;
    private static final int BOT_H   = 28;

    public LocalWaypointScreen(Screen parent) {
        super(new LiteralText("Waypoint アップロード"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // ── サーバーフォルダリスト構築 ──
        serverFolderList = new ArrayList<>(XaeroIntegration.getServerOrWorldList());
        HikariPointSyncer.LOGGER.info("[HPS] Xaeroフォルダ内のサーバー一覧: " + serverFolderList);

        // 接続中のサーバー情報からフォールバックと優先インデックス検索
        int defSrvIdx = 0;
        ServerInfo srv = MinecraftClient.getInstance().getCurrentServerEntry();
        if (srv != null) {
            String srvAddr = srv.address.replace(":", "_").toLowerCase();
            for (int i = 0; i < serverFolderList.size(); i++) {
                if (serverFolderList.get(i).toLowerCase().contains(srvAddr)) {
                    defSrvIdx = i;
                    break;
                }
            }
        }

        if (serverFolderList.isEmpty()) {
            serverFolderList.add(srv != null ? "Multiplayer_" + srv.address.replace(":", "_") : "Multiplayer_unknown");
        }

        // 表示用サーバー名リストを作成（現在の自動接続サーバーには (auto) と追記）
        serverDisplayList = new ArrayList<>();
        for (int i = 0; i < serverFolderList.size(); i++) {
            String folder = serverFolderList.get(i);
            String disp = XaeroIntegration.getDisplayName(folder);
            if (i == defSrvIdx && srv != null) {
                disp += " (auto)";
            }
            serverDisplayList.add(disp);
        }

        // 初期選択されているサーバー配下の Sub-World / Dimension セット一覧を作成
        updateSubWorldSets(serverFolderList.get(defSrvIdx));

        int half  = this.width / 2;
        int dropW = half - 6;

        // ── World/Server プルダウン ──
        serverDrop = new DropdownWidget(3, DROP_Y, dropW, DROP_H, serverDisplayList, idx -> {
            String selectedFolder = serverFolderList.get(idx);
            updateSubWorldSets(selectedFolder);

            // dimDropを新しいデータで再生成
            dimDrop = new DropdownWidget(half + 3, DROP_Y, dropW, DROP_H, subWorldDisplayList, di -> reloadList());
            reloadList();
        });
        serverDrop.setSelected(defSrvIdx);

        // ── Sub-World/Dimension プルダウン ──
        dimDrop = new DropdownWidget(half + 3, DROP_Y, dropW, DROP_H, subWorldDisplayList, idx -> reloadList());

        // ── ウェイポイントリスト ──
        listWidget = new UploadListWidget(this.client, this.width, this.height,
            LIST_TOP, this.height - BOT_H, 34);
        this.addSelectableChild(listWidget);
        reloadList();

        // ── 戻るボタン ──
        this.addDrawableChild(new ButtonWidget(
            this.width / 2 - 100, this.height - BOT_H + 4, 200, 20,
            new LiteralText("戻る"),
            b -> { if (client != null) client.setScreen(parent); }
        ));
    }

    private void updateSubWorldSets(String serverFolder) {
        subWorldSets = new ArrayList<>(XaeroIntegration.getSubWorldsAndDimensions(serverFolder));
        subWorldDisplayList = new ArrayList<>();

        for (XaeroWorldSet set : subWorldSets) {
            subWorldDisplayList.add(set.toString());
        }

        if (subWorldDisplayList.isEmpty()) {
            subWorldDisplayList.add("default (overworld)");
            subWorldSets.add(new XaeroWorldSet("default", "overworld", null));
        }

        HikariPointSyncer.LOGGER.info("[HPS] サーバー " + serverFolder + " のSub-Worldセット: " + subWorldDisplayList);
    }

    /** 選択中のサーバー+Sub-Worldセットでリストをロード */
    private void reloadList() {
        if (listWidget == null) return;
        if (serverFolderList.isEmpty()) return;

        String serverFolder = serverFolderList.get(serverDrop.getSelectedIndex());
        int setIdx = dimDrop != null ? dimDrop.getSelectedIndex() : 0;
        if (setIdx < 0 || setIdx >= subWorldSets.size()) setIdx = 0;

        XaeroWorldSet selectedSet = subWorldSets.get(setIdx);
        HikariPointSyncer.LOGGER.info("[HPS] 選択されたSubWorldセット: " + selectedSet + ", パス=" + selectedSet.filePath);

        // 1. まずディスクのテキストファイルからローカルウェイポイントを読み込む
        List<SyncWaypoint> fileWps = XaeroIntegration.getWaypointsFromSet(selectedSet, serverFolder);
        
        // 2. メモリ上（Xaeroの実行状態）に実際にロードされているウェイポイントリストを取得
        List<SyncWaypoint> memoryWps = XaeroIntegration.getMemoryWaypoints();
        
        // 3. 現在接続中のディメンションに関して、メモリ上に存在しない（＝実際に削除された）幽霊データを完全に除外
        List<SyncWaypoint> filteredWps = new ArrayList<>();
        for (SyncWaypoint fileWp : fileWps) {
            boolean existsInMemory = false;
            for (SyncWaypoint memWp : memoryWps) {
                if (memWp.name.equalsIgnoreCase(fileWp.name)
                    && memWp.x == fileWp.x
                    && memWp.z == fileWp.z
                    && memWp.dimension.equalsIgnoreCase(fileWp.dimension)) {
                    existsInMemory = true;
                    break;
                }
            }
            
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world != null) {
                String activeDim = "overworld";
                if (mc.world.getRegistryKey() == net.minecraft.world.World.NETHER) activeDim = "the_nether";
                else if (mc.world.getRegistryKey() == net.minecraft.world.World.END) activeDim = "the_end";
                
                // 現在プレイヤーがいるアクティブなディメンションである場合、メモリ側の状態を「真の正解」とし、メモリにないものは除外する
                if (selectedSet.dimension.equalsIgnoreCase(activeDim)) {
                    if (existsInMemory) {
                        filteredWps.add(fileWp);
                    }
                } else {
                    // 現在プレイヤーがいない他のディメンションのデータについては、メモリにロードされていないだけなのでディスクデータを表示する
                    filteredWps.add(fileWp);
                }
            } else {
                filteredWps.add(fileWp);
            }
        }

        HikariPointSyncer.LOGGER.info("[HPS] 読み込まれたWaypoint数=" + filteredWps.size() + " (フィルタ前=" + fileWps.size() + ")");
        listWidget.clearAndAdd(filteredWps);
    }

    @Override
    public void render(MatrixStack m, int mouseX, int mouseY, float delta) {
        this.renderBackground(m);
        listWidget.render(m, mouseX, mouseY, delta);

        int half = this.width / 2;

        // ── ラベル ──
        drawCenteredText(m, textRenderer, new LiteralText("World/Server"),
            half / 2, LBL_Y, 0xFFFFFF);
        drawCenteredText(m, textRenderer, new LiteralText("Sub-World/Dimension"),
            half + half / 2, LBL_Y, 0xFFFFFF);

        // ── プルダウン描画 ──
        serverDrop.render(m, mouseX, mouseY);
        if (dimDrop != null) dimDrop.render(m, mouseX, mouseY);

        super.render(m, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (serverDrop != null && serverDrop.mouseScrolled(amount)) return true;
        if (dimDrop != null && dimDrop.mouseScrolled(amount)) return true;
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (serverDrop != null && serverDrop.mouseClicked(mx, my, btn)) {
            if (dimDrop != null) dimDrop.close();
            return true;
        }
        if (dimDrop != null && dimDrop.mouseClicked(mx, my, btn)) {
            if (serverDrop != null) serverDrop.close();
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    // ──────────────────────────────────────────────────────
    //  ウェイポイントリストウィジェット
    // ──────────────────────────────────────────────────────
    public static class UploadListWidget extends AlwaysSelectedEntryListWidget<UploadListWidget.Entry> {

        public UploadListWidget(MinecraftClient client, int width, int height,
                                int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);
        }

        @Override
        public int getRowWidth() { return Math.min(this.width - 16, 400); }

        @Override
        protected int getScrollbarPositionX() { return this.left + this.width - 6; }

        public void clearAndAdd(List<SyncWaypoint> wps) {
            this.clearEntries();
            for (SyncWaypoint wp : wps) this.addEntry(new Entry(wp));
        }

        public static class Entry extends AlwaysSelectedEntryListWidget.Entry<Entry> {
            private final SyncWaypoint wp;
            private final ButtonWidget btn;

            public Entry(SyncWaypoint wp) {
                this.wp = wp;
                
                // サーバーの共有リスト（ClientWaypointManager）内に既に同じ名前・座標のものが存在するかチェック
                boolean alreadySynced = false;
                for (SyncWaypoint sharedWp : ClientWaypointManager.getAllShared()) {
                    if (sharedWp.name.equalsIgnoreCase(wp.name)
                        && sharedWp.x == wp.x
                        && sharedWp.z == wp.z) {
                        alreadySynced = true;
                        break;
                    }
                }
                String btnText = alreadySynced ? "済み" : "アップロード";

                this.btn = new ButtonWidget(0, 0, 90, 20, new LiteralText(btnText), b -> {
                    PacketByteBuf buf = PacketByteBufs.create();
                    wp.write(buf);
                    ClientPlayNetworking.send(HikariPointSyncer.UPLOAD_PACKET, buf);
                    b.active = false;
                    b.setMessage(new LiteralText("済み"));
                });
                if (alreadySynced) {
                    this.btn.active = false;
                }
            }

            @Override
            public void render(MatrixStack m, int idx, int y, int x,
                               int entryW, int entryH,
                               int mx, int my, boolean hovered, float delta) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (hovered) fill(m, x, y, x + entryW, y + entryH, 0x22FFFFFF);

                // [頭文字] (カッコは白、頭文字はWaypointの色)
                int initialColor = SharedWaypointListWidget.getRGBColor(wp.color);
                mc.textRenderer.draw(m, "[", x + 2, y + 3, 0xFFFFFF);
                int bracketW = mc.textRenderer.getWidth("[");
                mc.textRenderer.draw(m, wp.initial, x + 2 + bracketW, y + 3, initialColor);
                int initialW = mc.textRenderer.getWidth(wp.initial);
                mc.textRenderer.draw(m, "]", x + 2 + bracketW + initialW, y + 3, 0xFFFFFF);

                mc.textRenderer.draw(m, wp.name, x + 22, y + 3, 0x55FF55);
                mc.textRenderer.draw(m, wp.dimension, x + 22, y + 14, 0x55FFFF);

                // Y座標が ~ の場合をサポート
                String yStr = (wp.y == XaeroIntegration.Y_UNKNOWN) ? "~" : String.valueOf(wp.y);
                mc.textRenderer.draw(m, "(" + wp.x + ", " + yStr + ", " + wp.z + ")",
                    x + 22, y + 23, 0xAAAAAA);

                btn.x = x + entryW - 94;
                btn.y = y + 7;
                btn.render(m, mx, my, delta);
            }

            @Override
            public boolean mouseClicked(double mx, double my, int btn2) {
                return btn.mouseClicked(mx, my, btn2) || super.mouseClicked(mx, my, btn2);
            }

            @Override
            public Text getNarration() { return new LiteralText(wp.name); }
        }
    }
}
