package net.atsukigames.hikaripointsyncer.client.gui;

import net.atsukigames.hikaripointsyncer.client.integration.XaeroIntegration;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import java.util.List;

/**
 * サーバー/ワールドの一覧を表示する選択画面。
 * Xaero'sのウェイポイントを選択する前にどのサーバー/ワールドのWaypointを
 * アップロードするかを選ぶ画面。
 */
public class ServerSelectScreen extends Screen {
    private final Screen parent;
    private ServerListWidget listWidget;

    public ServerSelectScreen(Screen parent) {
        super(new LiteralText("サーバー/ワールド選択"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.listWidget = new ServerListWidget(this.client, this.width, this.height, 30, this.height - 40, 26);
        this.addSelectableChild(this.listWidget);

        List<String> entries = XaeroIntegration.getServerOrWorldList();
        for (String name : entries) {
            this.listWidget.addServerEntry(name, this);
        }

        this.addDrawableChild(new ButtonWidget(this.width / 2 - 100, this.height - 30, 200, 20, new LiteralText("戻る"), (button) -> {
            this.client.setScreen(this.parent);
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        this.listWidget.render(matrices, mouseX, mouseY, delta);
        drawCenteredText(matrices, this.textRenderer, new LiteralText("アップロード元のサーバー/ワールドを選択"), this.width / 2, 10, 0xFFFFFF);
        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    public static class ServerListWidget extends AlwaysSelectedEntryListWidget<ServerListWidget.Entry> {
        public ServerListWidget(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);
        }

        public void addServerEntry(String name, Screen parent) {
            this.addEntry(new Entry(name, parent));
        }

        @Override
        public int getRowWidth() {
            return 300;
        }

        @Override
        protected int getScrollbarPositionX() {
            return this.width / 2 + 154;
        }

        public static class Entry extends AlwaysSelectedEntryListWidget.Entry<Entry> {
            private final String serverName;
            private final Screen parent;
            private final ButtonWidget selectBtn;

            public Entry(String serverName, Screen parent) {
                this.serverName = serverName;
                this.parent = parent;
                this.selectBtn = new ButtonWidget(0, 0, 80, 20, new LiteralText("選択"), (button) -> {
                    MinecraftClient.getInstance().setScreen(new LocalWaypointScreen(parent));
                });
            }

            @Override
            public void render(MatrixStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
                MinecraftClient client = MinecraftClient.getInstance();
                client.textRenderer.draw(matrices, serverName, x + 5, y + 7, 0xFFFFFF);
                selectBtn.x = x + entryWidth - 85;
                selectBtn.y = y + 2;
                selectBtn.render(matrices, mouseX, mouseY, tickDelta);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (selectBtn.mouseClicked(mouseX, mouseY, button)) return true;
                return super.mouseClicked(mouseX, mouseY, button);
            }

            @Override
            public Text getNarration() {
                return new LiteralText(serverName);
            }
        }
    }
}
