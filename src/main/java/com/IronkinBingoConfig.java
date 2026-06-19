package com.ironkin.bingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("IronkinBingo")
public interface IronkinBingoConfig extends Config
{
    @ConfigItem(
            keyName = "websiteUrl",
            name = "Ironkin Website URL",
            description = "Ironkin website URL. Leave as default unless staff tells you otherwise.",
            position = 1
    )
    default String websiteUrl() { return "https://ironkinclan.com"; }

    @ConfigItem(
            keyName = "pluginToken",
            name = "Plugin Token",
            description = "Your personal Ironkin plugin token from the Ironkin website plugin setup page. Keep this private.",
            secret = true,
            position = 2
    )
    default String pluginToken() { return ""; }

    @ConfigItem(
            keyName = "testConnection",
            name = "Test Connection",
            description = "Turn this on to test your plugin token and website connection.",
            position = 3
    )
    default boolean testConnection() { return false; }
}
