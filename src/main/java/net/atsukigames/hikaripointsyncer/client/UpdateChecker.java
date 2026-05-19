package net.atsukigames.hikaripointsyncer.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.atsukigames.hikaripointsyncer.HikariPointSyncer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {
    private static final String API_URL = "https://api.github.com/repos/HikariServerDev/HikariPointSyncer/releases/latest";
    private static final Gson GSON = new Gson();
    public static boolean updateAvailable = false;
    public static String newVersionStr = "";
    public static String downloadUrl = "";

    public static void checkForUpdates() {
        new Thread(() -> {
            try {
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                
                if (conn.getResponseCode() == 200) {
                    JsonObject response = GSON.fromJson(new InputStreamReader(conn.getInputStream()), JsonObject.class);
                    String latestVersion = response.get("tag_name").getAsString().replace("v", "");
                    
                    String currentVersion = FabricLoader.getInstance().getModContainer(HikariPointSyncer.MOD_ID)
                            .get().getMetadata().getVersion().getFriendlyString();
                    
                    if (!latestVersion.equals(currentVersion)) {
                        updateAvailable = true;
                        newVersionStr = latestVersion;
                        // Get the first asset download url
                        if (response.has("assets") && response.getAsJsonArray("assets").size() > 0) {
                            downloadUrl = response.getAsJsonArray("assets").get(0).getAsJsonObject().get("browser_download_url").getAsString();
                        }
                        HikariPointSyncer.LOGGER.info("Update available: " + latestVersion);
                    }
                }
            } catch (Exception e) {
                HikariPointSyncer.LOGGER.warn("Failed to check for updates: " + e.getMessage());
            }
        }).start();
    }
}
