package net.atsukigames.hikaripointsyncer.mixin;

import net.atsukigames.hikaripointsyncer.client.UpdateChecker;
import net.atsukigames.hikaripointsyncer.client.gui.UpdateScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (UpdateChecker.updateAvailable) {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().setScreen(new UpdateScreen((TitleScreen) (Object) this));
            });
        }
    }
}
