package net.atsukigames.hikaripointsyncer.mixin;

import net.atsukigames.hikaripointsyncer.client.gui.SyncWaypointScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Xaero's World Map の GuiWaypoints にボタンを注入する
// remap=false を使用しているため、intermediaryの名前（method_25426）を使う必要がある
@Pseudo
@Mixin(targets = "xaero.common.gui.GuiWaypoints", remap = false)
public abstract class XaeroWorldMapGuiMixin extends Screen {

    protected XaeroWorldMapGuiMixin(Text title) {
        super(title);
    }

    // Fabric実行時のScreen.init()のintermediaryメソッド名はmethod_25426
    // require=0 にすることで、見つからない場合でもクラッシュしない
    @Inject(
        method = "method_25426()V",
        at = @At("TAIL"),
        require = 0
    )
    private void hikari_onInit(CallbackInfo ci) {
        // サーバー側にModが入っている場合のみボタンを追加
        if (!net.atsukigames.hikaripointsyncer.client.HikariPointSyncerClient.serverHasMod) return;
        // Xaero's Waypoint画面の右上にSyncWaypointボタンを追加
        this.addDrawableChild(new ButtonWidget(this.width - 110, 5, 100, 20, new LiteralText("SyncWaypoint"), (button) -> {
            MinecraftClient.getInstance().setScreen(new SyncWaypointScreen(this));
        }));
    }
}
