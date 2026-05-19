package net.atsukigames.hikaripointsyncer.client.gui;

import net.atsukigames.hikaripointsyncer.HikariPointSyncer;
import net.atsukigames.hikaripointsyncer.client.integration.XaeroIntegration;
import net.atsukigames.hikaripointsyncer.data.SyncWaypoint;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;

/**
 * サーバーから共有されているWaypointの一覧ウィジェット。
 * 各エントリにディメンション・座標を表示する。
 */
public class SharedWaypointListWidget extends AlwaysSelectedEntryListWidget<SharedWaypointListWidget.Entry> {
    private final SyncWaypointScreen parent;

    public SharedWaypointListWidget(MinecraftClient client, int width, int height,
                                    int top, int bottom, int itemHeight,
                                    SyncWaypointScreen parent) {
        super(client, width, height, top, bottom, itemHeight);
        this.parent = parent;
    }

    public static int getRGBColor(int colorIndex) {
        if (colorIndex >= 0 && colorIndex <= 15) {
            switch (colorIndex) {
                case 0: return 0x555555; // Black -> Dark Gray
                case 1: return 0x0000AA; // Dark Blue
                case 2: return 0x00AA00; // Dark Green
                case 3: return 0x00AAAA; // Dark Aqua
                case 4: return 0xAA0000; // Dark Red
                case 5: return 0xAA00AA; // Dark Purple
                case 6: return 0xFFAA00; // Gold
                case 7: return 0xAAAAAA; // Gray
                case 8: return 0x555555; // Dark Gray
                case 9: return 0x5555FF; // Blue
                case 10: return 0x55FF55; // Green
                case 11: return 0x55FFFF; // Aqua
                case 12: return 0xFF5555; // Red
                case 13: return 0xFF55FF; // Light Purple
                case 14: return 0xFFFF55; // Yellow
                case 15: return 0xFFFFFF; // White
                default: return 0xFFFFFF;
            }
        }
        if (colorIndex == -1) {
            return 0x55FF55; // Random / Default Green
        }
        return colorIndex | 0xFF000000;
    }

    public void updateEntries(List<SyncWaypoint> waypoints, boolean isDeletedList) {
        this.clearEntries();
        for (SyncWaypoint wp : waypoints) {
            this.addEntry(new Entry(wp, isDeletedList));
        }
    }

    @Override
    public int getRowWidth() {
        return Math.min(this.width - 16, 420);
    }

    @Override
    protected int getScrollbarPositionX() {
        return this.left + this.width - 6;
    }

    public class Entry extends AlwaysSelectedEntryListWidget.Entry<Entry> {
        private final SyncWaypoint wp;
        private final boolean isDeletedList;
        private final boolean alreadyDownloaded;
        private final boolean canDeleteWp;
        private final ButtonWidget actionBtn;    // DL または 復元
        private final ButtonWidget deleteBtn;    // 削除

        public Entry(SyncWaypoint wp, boolean isDeletedList) {
            this.wp = wp;
            this.isDeletedList = isDeletedList;

            if (isDeletedList) {
                this.alreadyDownloaded = false;
                this.canDeleteWp = false;
                this.actionBtn = new ButtonWidget(0, 0, 50, 20, new LiteralText("復元"), b -> {
                    sendSetDeletedPacket(wp.id, false);
                    if (parent != null) parent.updateList();
                });
                this.deleteBtn = null;
            } else {
                // Xaeroに既に保存されているかチェック
                this.alreadyDownloaded = XaeroIntegration.hasWaypoint(wp);
                this.canDeleteWp = XaeroIntegration.canDelete(wp);
                String btnText = alreadyDownloaded ? "済み" : "DL";

                this.actionBtn = new ButtonWidget(0, 0, 50, 20, new LiteralText(btnText), b -> {
                    boolean success = XaeroIntegration.addWaypointToXaeroReflectively(wp);
                    if (success) {
                        b.setMessage(new LiteralText("済み"));
                        b.active = false;
                        if (parent != null) parent.updateList();
                    } else {
                        b.setMessage(new LiteralText("エラー"));
                    }
                });

                if (alreadyDownloaded) {
                    this.actionBtn.active = false;
                }

                this.deleteBtn = new ButtonWidget(0, 0, 50, 20, new LiteralText("削除"), b -> {
                    sendSetDeletedPacket(wp.id, true);
                    if (parent != null) parent.updateList();
                });

                // 削除席限なしの場合はボタンを無効化
                if (!canDeleteWp) {
                    this.deleteBtn.active = false;
                }
            }
        }

        private void sendSetDeletedPacket(UUID id, boolean deleted) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeUuid(id);
            buf.writeBoolean(deleted);
            ClientPlayNetworking.send(HikariPointSyncer.SET_DELETED_PACKET, buf);
        }

        @Override
        public void render(MatrixStack matrices, int index, int y, int x,
                           int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float tickDelta) {
            MinecraftClient mc = MinecraftClient.getInstance();

            // ダウンロード済みエントリはブラックアウト（暗いオーバーレイ）
            if (!isDeletedList && alreadyDownloaded) {
                fill(matrices, x, y, x + entryWidth, y + entryHeight, 0xAA000000);
            } else if (hovered) {
                fill(matrices, x, y, x + entryWidth, y + entryHeight, 0x22FFFFFF);
            }

            // テキストの透明度: ダウンロード済みは暗くする
            int textAlpha = (!isDeletedList && alreadyDownloaded) ? 0x66 : 0xFF;
            int nameColor   = (textAlpha << 24) | 0x55FF55;
            int dimColor    = (textAlpha << 24) | 0x55FFFF;
            int coordColor  = (textAlpha << 24) | 0xAAAAAA;
            int bracketColor = (textAlpha << 24) | 0xFFFFFF;

            // [頭文字] (カッコは白、頭文字はWaypointの色)
            int initialColor = getRGBColor(wp.color);
            if (alreadyDownloaded && !isDeletedList) initialColor = (textAlpha << 24) | (initialColor & 0x00FFFFFF);
            mc.textRenderer.draw(matrices, "[", x + 2, y + 4, bracketColor);
            int bracketW = mc.textRenderer.getWidth("[");
            mc.textRenderer.draw(matrices, wp.initial, x + 2 + bracketW, y + 4, initialColor);
            int initialW = mc.textRenderer.getWidth(wp.initial);
            mc.textRenderer.draw(matrices, "]", x + 2 + bracketW + initialW, y + 4, bracketColor);

            // Waypoint名
            mc.textRenderer.draw(matrices, wp.name, x + 22, y + 4, nameColor);

            // ダウンロード済みバッジ
            if (!isDeletedList && alreadyDownloaded) {
                mc.textRenderer.draw(matrices, "[DL済み]", x + 22 + mc.textRenderer.getWidth(wp.name) + 4, y + 4, 0x6655FFFF);
            }

            // ディメンション
            mc.textRenderer.draw(matrices, wp.dimension, x + 22, y + 14, dimColor);

            // 座標
            String yStr = (wp.y == XaeroIntegration.Y_UNKNOWN) ? "~" : String.valueOf(wp.y);
            String coords = "(" + wp.x + ", " + yStr + ", " + wp.z + ")";
            mc.textRenderer.draw(matrices, coords, x + 22, y + 24, coordColor);

            // 削除済みタブ: 残り時間（名前の下、y+14）
            if (isDeletedList) {
                long rem = (30L * 24 * 60 * 60 * 1000) - (System.currentTimeMillis() - wp.deletedTime);
                String timeStr;
                if (rem <= 0) {
                    timeStr = "完全削除中";
                } else {
                    long diffSeconds = rem / 1000 % 60;
                    long diffMinutes = rem / (60 * 1000) % 60;
                    long diffHours = rem / (60 * 60 * 1000) % 24;
                    long diffDays = rem / (24 * 60 * 60 * 1000);

                    if (diffDays >= 1) {
                        timeStr = "残り" + diffDays + "日";
                    } else if (diffHours >= 1) {
                        timeStr = "残り" + diffHours + "時間";
                    } else {
                        timeStr = "残り" + diffMinutes + "分" + diffSeconds + "秒";
                    }
                }
                mc.textRenderer.draw(matrices, timeStr, x + 120, y + 14, 0xFFFF5555);

                actionBtn.x = x + entryWidth - 54;
                actionBtn.y = y + 8;
                actionBtn.render(matrices, mouseX, mouseY, tickDelta);
            } else {
                // DLボタン
                actionBtn.x = x + entryWidth - 110;
                actionBtn.y = y + 8;
                actionBtn.render(matrices, mouseX, mouseY, tickDelta);

                // 削除ボタン（権限なしの場合は点線枚が出る禁止スタイルで表示）
                if (deleteBtn != null) {
                    deleteBtn.x = x + entryWidth - 54;
                    deleteBtn.y = y + 8;
                    if (!canDeleteWp) {
                        // 無効化状態で描画するだけ（ボタンは自動的にグレーで描画される）
                    }
                    deleteBtn.render(matrices, mouseX, mouseY, tickDelta);
                }
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (actionBtn != null && actionBtn.mouseClicked(mouseX, mouseY, button)) return true;
            if (deleteBtn != null && deleteBtn.mouseClicked(mouseX, mouseY, button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public Text getNarration() {
            return new LiteralText(wp.name);
        }
    }
}
