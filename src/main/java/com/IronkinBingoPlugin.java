package com.ironkin.bingo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.DrawManager;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import static net.runelite.http.api.RuneLiteAPI.GSON;

@Slf4j
@PluginDescriptor(
        name = "Ironkin Bingo",
        description = "Submits matching Ironkin Battleship Bingo loot drops and screenshots to the Ironkin clan website for staff review.",
        tags = {"ironkin", "loot", "clan", "event", "bingo", "screenshot"}
)
public class IronkinBingoPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private IronkinBingoConfig config;
    @Inject private ItemManager itemManager;
    @Inject private ConfigManager configManager;
    @Inject private DrawManager drawManager;
    @Inject private OkHttpClient okHttpClient;

    private final Set<String> activeItems = new HashSet<>();
    private String lastLootSource = "";
    private long lastItemsRefresh = 0L;

    private static final long ITEM_REFRESH_MS = 5 * 60 * 1000L;
    private static final Set<String> SPECIAL_LOOT_NPC_NAMES = Set.of("The Whisperer", "Araxxor", "Branda the Fire Queen", "Eldric the Ice King", "Yama");

    @Override
    protected void startUp()
    {
        log.info("Ironkin Bingo starting.");
        refreshActiveItems(false);
    }

    @Override
    protected void shutDown()
    {
        log.info("Ironkin Bingo stopped.");
        activeItems.clear();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!event.getGroup().equalsIgnoreCase("IronkinBingo")) return;

        if (config.testConnection())
        {
            testConnection();
            configManager.unsetConfiguration("IronkinBingo", "testConnection");
        }

        refreshActiveItems(true);
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        lastLootSource = event.getNpc().getName();
        handleReceivedLoot(event.getItems());
    }

    @Subscribe
    public void onLootReceived(LootReceived event)
    {
        lastLootSource = event.getName();
        if (event.getType() == LootRecordType.NPC && SPECIAL_LOOT_NPC_NAMES.contains(event.getName()))
        {
            handleReceivedLoot(event.getItems());
        }
        else if (event.getType() == LootRecordType.EVENT || event.getType() == LootRecordType.PICKPOCKET || event.getType() == LootRecordType.PLAYER)
        {
            handleReceivedLoot(event.getItems());
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage)
    {
        // Kept intentionally minimal. Ironkin Bingo only submits actual matching loot drops.
    }

    private void handleReceivedLoot(Collection<ItemStack> drops)
    {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        refreshActiveItems(false);

        if (activeItems.isEmpty()) return;

        List<String> alreadySent = new ArrayList<>();
        for (ItemStack item : drops)
        {
            ItemComposition comp = itemManager.getItemComposition(item.getId());
            String itemName = comp.getName();
            String normalized = normalize(itemName);
            int value = itemManager.getItemPrice(item.getId()) * item.getQuantity();

            if (!matchesActiveItem(normalized) || alreadySent.contains(normalized)) continue;
            alreadySent.add(normalized);

            drawManager.requestNextFrameListener(image -> {
                try
                {
                    byte[] screenshot = convertImageToByteArray((BufferedImage) image);
                    submitProof(itemName, item.getId(), item.getQuantity(), value, lastLootSource, screenshot);
                }
                catch (IOException e)
                {
                    log.error("Could not capture Ironkin Bingo screenshot.", e);
                    sendChatMessage("Ironkin Bingo could not capture the screenshot.");
                }
            });
        }
    }

    private boolean matchesActiveItem(String normalizedItemName)
    {
        if (activeItems.contains(normalizedItemName)) return true;
        // Basic support for generic site tiles such as "Any Justiciar piece".
        if (activeItems.contains("any justiciar piece") && Set.of("justiciar faceguard", "justiciar chestguard", "justiciar legguards").contains(normalizedItemName)) return true;
        if (activeItems.contains("any masori piece") && (normalizedItemName.startsWith("masori "))) return true;
        if (activeItems.contains("any abyssal dye") && normalizedItemName.equals("abyssal dye")) return true;
        if (activeItems.contains("any ancestral piece") && Set.of("ancestral hat", "ancestral robe top", "ancestral robe bottom").contains(normalizedItemName)) return true;
        if (activeItems.contains("any godsword shard") && normalizedItemName.startsWith("godsword shard ")) return true;
        return false;
    }

    private void refreshActiveItems(boolean force)
    {
        long now = System.currentTimeMillis();
        if (!force && now - lastItemsRefresh < ITEM_REFRESH_MS) return;
        if (config.pluginToken().isBlank()) return;

        HttpUrl url = buildUrl("/api/bingo/plugin/items");
        if (url == null) return;

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.pluginToken().trim())
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e)
            {
                log.warn("Could not load Ironkin Bingo active items.", e);
            }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (!r.isSuccessful())
                    {
                        log.warn("Ironkin Bingo items endpoint returned {}", r.code());
                        return;
                    }

                    JsonObject json = GSON.fromJson(r.body().charStream(), JsonObject.class);
                    Set<String> nextItems = new HashSet<>();
                    JsonArray items = json.getAsJsonArray("items");
                    if (items != null)
                    {
                        items.forEach(element -> nextItems.add(normalize(element.getAsString())));
                    }

                    synchronized (activeItems)
                    {
                        activeItems.clear();
                        activeItems.addAll(nextItems);
                    }
                    lastItemsRefresh = System.currentTimeMillis();
                    log.info("Loaded {} Ironkin Bingo active items.", nextItems.size());
                }
            }
        });
    }

    private void testConnection()
    {
        if (config.pluginToken().isBlank())
        {
            sendChatMessage("Add your Ironkin plugin token first.");
            return;
        }

        HttpUrl url = buildUrl("/api/bingo/submit-proof");
        if (url == null)
        {
            sendChatMessage("Ironkin website URL is invalid.");
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.pluginToken().trim())
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e)
            {
                sendChatMessage("Ironkin Bingo could not connect to the website.");
            }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    sendChatMessage(r.isSuccessful()
                            ? "Ironkin Bingo is connected successfully."
                            : "Ironkin Bingo connected, but your token/member access was rejected.");
                }
            }
        });
    }

    private void submitProof(String itemName, int itemId, int quantity, int value, String source, byte[] screenshot)
    {
        if (config.pluginToken().isBlank())
        {
            sendChatMessage("Ironkin Bingo: add your plugin token before submitting drops.");
            return;
        }

        HttpUrl url = buildUrl("/api/bingo/submit-proof");
        if (url == null) return;

        String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("player", playerName)
                .addFormDataPart("itemName", itemName)
                .addFormDataPart("itemId", String.valueOf(itemId))
                .addFormDataPart("quantity", String.valueOf(quantity))
                .addFormDataPart("value", String.valueOf(value))
                .addFormDataPart("source", source == null ? "Unknown" : source)
                .addFormDataPart("timestamp", Instant.now().toString())
                .addFormDataPart("file", playerName + "_" + itemName.replaceAll("[^a-zA-Z0-9_-]", "_") + ".png",
                        RequestBody.create(MediaType.parse("image/png"), screenshot))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.pluginToken().trim())
                .post(body)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e)
            {
                log.warn("Ironkin Bingo proof submission failed.", e);
                sendChatMessage("Ironkin Bingo could not submit your proof.");
            }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (r.isSuccessful())
                    {
                        sendChatMessage("Ironkin Bingo submitted your drop for staff review.");
                    }
                    else
                    {
                        sendChatMessage("Ironkin Bingo did not submit this drop: " + r.code());
                    }
                }
            }
        });
    }

    private HttpUrl buildUrl(String path)
    {
        String base = config.websiteUrl().trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://" + base;
        HttpUrl parsed = HttpUrl.parse(base);
        if (parsed == null) return null;
        return parsed.newBuilder().encodedPath(path).build();
    }

    private String normalize(String value)
    {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "png", out);
        return out.toByteArray();
    }

    private void sendChatMessage(String message)
    {
        clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "Ironkin", message, null));
    }

    @Provides
    IronkinBingoConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(IronkinBingoConfig.class);
    }
}
