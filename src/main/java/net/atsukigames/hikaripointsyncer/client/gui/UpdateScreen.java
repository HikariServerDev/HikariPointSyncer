package net.atsukigames.hikaripointsyncer.client.gui;

import net.atsukigames.hikaripointsyncer.HikariPointSyncer;
import net.atsukigames.hikaripointsyncer.client.UpdateChecker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class UpdateScreen extends Screen {
    private final Screen parent;
    private boolean isDownloading = false;
    private boolean downloadComplete = false;
    private String statusText = "";

    public UpdateScreen(Screen parent) {
        super(new LiteralText("HikariPointSyncer Updater"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!downloadComplete) {
            this.addDrawableChild(new ButtonWidget(this.width / 2 - 105, this.height / 2 + 30, 100, 20, new LiteralText("更新する"), (button) -> {
                if (!isDownloading) {
                    isDownloading = true;
                    statusText = "ダウンロード中...";
                    button.active = false;
                    downloadUpdate();
                }
            }));

            this.addDrawableChild(new ButtonWidget(this.width / 2 + 5, this.height / 2 + 30, 100, 20, new LiteralText("更新しない"), (button) -> {
                UpdateChecker.updateAvailable = false; // 今セッション中は再表示しない
                MinecraftClient.getInstance().setScreen(parent);
            }));
        } else {
            // ダウンロード完了後のボタンレイアウト
            this.addDrawableChild(new ButtonWidget(this.width / 2 - 105, this.height / 2 + 30, 100, 20, new LiteralText("再起動する"), (button) -> {
                if (client != null) {
                    try {
                        // Java 9+ の ProcessHandle API を使用して、自分自身の実行 Java バイナリと起動引数を完全に取得
                        java.util.Optional<String> commandOpt = ProcessHandle.current().info().command();
                        java.util.Optional<String[]> argsOpt = ProcessHandle.current().info().arguments();
                        
                        if (commandOpt.isPresent() && argsOpt.isPresent()) {
                            String command = commandOpt.get();
                            String[] args = argsOpt.get();
                            
                            // コマンドと引数を結合した配列を生成
                            String[] fullCommand = new String[args.length + 1];
                            fullCommand[0] = command;
                            System.arraycopy(args, 0, fullCommand, 1, args.length);
                            
                            ProcessBuilder pb = new ProcessBuilder(fullCommand);
                            // 実行ディレクトリをマインクラフトのゲームディレクトリに設定
                            pb.directory(FabricLoader.getInstance().getGameDir().toFile());
                            pb.start(); // 非同期でまったく同じ設定のマイクラを起動
                            HikariPointSyncer.LOGGER.info("[HPS] Java ProcessHandle による完璧な自動自己再起動プロセスを起動しました");
                        } else {
                            HikariPointSyncer.LOGGER.warn("[HPS] 起動コマンド情報を取得できなかったため、再起動は行わず終了のみ実行します。");
                        }
                    } catch (Exception e) {
                        HikariPointSyncer.LOGGER.error("[HPS] 自動再起動プロセスの起動に失敗しました。終了のみ実行します。", e);
                    }
                    client.scheduleStop(); // 安全に現在のマイクラを終了
                }
            }));

            this.addDrawableChild(new ButtonWidget(this.width / 2 + 5, this.height / 2 + 30, 100, 20, new LiteralText("再起動しない"), (button) -> {
                MinecraftClient.getInstance().setScreen(parent);
            }));
        }
    }

    private void downloadUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(UpdateChecker.downloadUrl);
                String fileName = UpdateChecker.downloadUrl.substring(UpdateChecker.downloadUrl.lastIndexOf('/') + 1);
                Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
                Path newFilePath = modsDir.resolve(fileName);
                
                try (InputStream in = url.openStream()) {
                    Files.copy(in, newFilePath, StandardCopyOption.REPLACE_EXISTING);
                }
                
                // 1. 過去のバージョンのJARファイルを自動で完全削除する
                FabricLoader.getInstance().getModContainer(HikariPointSyncer.MOD_ID).ifPresent(mod -> {
                    try {
                        Path currentPath = mod.getRootPaths().get(0);
                        if (Files.isRegularFile(currentPath)) {
                            Files.delete(currentPath);
                            HikariPointSyncer.LOGGER.info("[HPS] 過去のバージョンを自動削除しました: " + currentPath.getFileName());
                        }
                    } catch (Exception e) {
                        HikariPointSyncer.LOGGER.error("[HPS] 古いMod JARの削除に失敗しました", e);
                    }
                });

                // modsフォルダ内の他の古い hikaripointsyncer-*.jar や .disabled ファイルも一括で完全クリーンアップ
                try {
                    if (Files.exists(modsDir)) {
                        try (java.util.stream.Stream<Path> stream = Files.list(modsDir)) {
                            stream.forEach(p -> {
                                String name = p.getFileName().toString();
                                if (name.startsWith("hikaripointsyncer-") && name.endsWith(".jar") && !name.equals(fileName)) {
                                    try {
                                        Files.delete(p);
                                        HikariPointSyncer.LOGGER.info("[HPS] 古いバージョンのJARを自動削除しました: " + name);
                                    } catch (Exception ignored) {}
                                }
                                if (name.startsWith("hikaripointsyncer-") && name.endsWith(".disabled")) {
                                    try {
                                        Files.delete(p);
                                    } catch (Exception ignored) {}
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    HikariPointSyncer.LOGGER.error("[HPS] 古いModフォルダのクリーンアップに失敗しました", e);
                }

                downloadComplete = true;
                statusText = "ダウンロードが完了しました。ランチャーを再起動してください。";
                
                // クライアントのメインスレッドで画面の再初期化（UIボタンの更新）を実行
                if (client != null) {
                    client.execute(() -> {
                        this.clearChildren();
                        this.init();
                    });
                }
            } catch (Exception e) {
                statusText = "エラー: " + e.getMessage();
            }
            isDownloading = false;
        }).start();
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, new LiteralText("HikariPointSyncer"), this.width / 2, this.height / 2 - 40, 0xFFFFFF);
        if (!downloadComplete) {
            drawCenteredText(matrices, this.textRenderer, new LiteralText("新しいバージョンがあります。更新してもよろしいですか？"), this.width / 2, this.height / 2 - 20, 0xAAAAAA);
        } else {
            drawCenteredText(matrices, this.textRenderer, new LiteralText("更新データの適用に成功しました。"), this.width / 2, this.height / 2 - 20, 0xAAAAAA);
        }
        drawCenteredText(matrices, this.textRenderer, new LiteralText(statusText), this.width / 2, this.height / 2 + 5, 0xFFFF55);
        super.render(matrices, mouseX, mouseY, delta);
    }
}
