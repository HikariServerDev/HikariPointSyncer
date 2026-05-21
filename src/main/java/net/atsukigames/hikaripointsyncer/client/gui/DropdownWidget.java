package net.atsukigames.hikaripointsyncer.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Xaero's World Map スタイルのプルダウンウィジェット。
 * マウススクロール、スクロールバー、無制限の項目選択をサポート。
 */
public class DropdownWidget extends DrawableHelper {

    public final int x, y, w, h;
    private final List<String> items;
    private int selectedIndex = 0;
    private boolean open = false;

    private int scrollOffset = 0; // 現在のスクロールオフセット（表示開始インデックス）

    private static final int ITEM_H  = 12; // 1項目の高さ
    private static final int MAX_VIS = 10; // 最大表示件数

    private final Consumer<Integer> onSelect;

    public DropdownWidget(int x, int y, int w, int h, List<String> items, Consumer<Integer> onSelect) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        this.items = items;
        this.onSelect = onSelect;
    }

    public void setSelected(int idx) {
        this.selectedIndex = Math.max(0, Math.min(idx, items.size() - 1));
        // 初期選択位置に合わせてスクロール位置も調整
        if (selectedIndex >= scrollOffset + MAX_VIS) {
            scrollOffset = Math.max(0, selectedIndex - MAX_VIS + 1);
        } else if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        }
    }

    public int getSelectedIndex() { return selectedIndex; }

    public String getSelectedValue() {
        return items.isEmpty() ? "" : items.get(selectedIndex);
    }

    public boolean isOpen() { return open; }
    public void close() { open = false; }

    /** テキストボックス部分を描画。open 時はドロップダウンリストも描画 */
    public void render(MatrixStack m, int mouseX, int mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // ── テキストボックス外枠 ──
        fill(m, x - 1, y - 1, x + w + 1, y + h + 1, 0xFFFFFFFF);
        fill(m, x, y, x + w, y + h, 0xFF000000);

        // ── 現在の値 ──
        String label = items.isEmpty() ? "-" : items.get(selectedIndex);
        int maxW = w - 6;
        if (mc.textRenderer.getWidth(label) > maxW)
            label = mc.textRenderer.trimToWidth(label, maxW);
        mc.textRenderer.draw(m, label, x + (w - mc.textRenderer.getWidth(label)) / 2f, y + (h - 9) / 2f + 1, 0xFFFFFF);

        // ── ドロップダウンリスト ──
        if (open && !items.isEmpty()) {
            int vis   = Math.min(items.size(), MAX_VIS);
            int listY = y + h;
            int listH = vis * ITEM_H;

            // リスト背景
            fill(m, x - 1, listY - 1, x + w + 1, listY + listH + 1, 0xFFFFFFFF);
            fill(m, x, listY, x + w, listY + listH, 0xFF000000);

            // スクロール範囲外にインデックスがいかないようにガード
            if (scrollOffset + vis > items.size()) {
                scrollOffset = Math.max(0, items.size() - vis);
            }

            for (int i = 0; i < vis; i++) {
                int actualIdx = scrollOffset + i;
                if (actualIdx >= items.size()) break;

                int iy = listY + i * ITEM_H;
                boolean hovered  = mouseX >= x && mouseX < x + w - (items.size() > MAX_VIS ? 4 : 0)
                                   && mouseY >= iy && mouseY < iy + ITEM_H;
                boolean selected = actualIdx == selectedIndex;

                if (selected) {
                    fill(m, x, iy, x + w - (items.size() > MAX_VIS ? 4 : 0), iy + ITEM_H, 0xFFAA7700); // 黄色 (Xaero風)
                } else if (hovered) {
                    fill(m, x, iy, x + w - (items.size() > MAX_VIS ? 4 : 0), iy + ITEM_H, 0xFF444444);
                }

                String txt = items.get(actualIdx);
                int txtW = w - 4 - (items.size() > MAX_VIS ? 4 : 0);
                if (mc.textRenderer.getWidth(txt) > txtW)
                    txt = mc.textRenderer.trimToWidth(txt, txtW);
                mc.textRenderer.draw(m, txt, x + 2, iy + 2, 0xFFFFFF);
            }

            // ── スクロールバー描画 ──
            if (items.size() > MAX_VIS) {
                int sbX = x + w - 4;
                int sbY = listY;
                int sbH = listH;
                // スクロールバー背景 (濃いグレー)
                fill(m, sbX, sbY, sbX + 4, sbY + sbH, 0xFF222222);

                int thumbH = (int) (((double) MAX_VIS / items.size()) * sbH);
                int maxScroll = items.size() - MAX_VIS;
                int thumbY = sbY + (int) (((double) scrollOffset / maxScroll) * (sbH - thumbH));
                // つまみ (明るいグレー)
                fill(m, sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFF888888);
            }
        }
    }

    /**
     * マウススクロール処理。
     */
    public boolean mouseScrolled(double amount) {
        if (open && items.size() > MAX_VIS) {
            // amount は上が正、下が負
            scrollOffset = Math.max(0, Math.min(scrollOffset - (int) Math.signum(amount), items.size() - MAX_VIS));
            return true;
        }
        return false;
    }

    /**
     * クリック処理。
     * @return クリックを消費した場合 true
     */
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;

        // テキストボックスクリック → 開閉トグル
        if (mx >= x && mx < x + w && my >= y && my < y + h) {
            open = !open;
            return true;
        }

        // リスト項目クリック
        if (open) {
            int vis   = Math.min(items.size(), MAX_VIS);
            int listY = y + h;
            if (mx >= x && mx < x + w && my >= listY && my < listY + vis * ITEM_H) {
                int clickIdx = (int) ((my - listY) / ITEM_H);
                int actualIdx = scrollOffset + clickIdx;
                if (actualIdx >= 0 && actualIdx < items.size()) {
                    selectedIndex = actualIdx;
                    open = false;
                    onSelect.accept(actualIdx);
                    return true;
                }
            }
            // リスト外クリック → 閉じるだけ。クリックを消費し、背後のウィジェットへ透過させない。
            open = false;
            return true;
        }
        return false;
    }
}
