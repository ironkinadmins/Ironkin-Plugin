# Ironkin Bingo Plugin

RuneLite plugin for Ironkin Battleship Bingo.

## What it does

- Loads the active Battleship Bingo tile list from `https://ironkinclan.com`.
- Watches loot drops in RuneLite.
- If a drop matches an active Bingo tile, it captures a screenshot.
- Sends the player name, drop, source, value, screenshot, and timestamp to the Ironkin website.
- The Ironkin website verifies the member's personal plugin token and Discord membership before creating a pending proof.
- Staff review the pending proof on the Ironkin website before the board updates.

## Member setup

1. Sign in to the Ironkin website with Discord.
2. Open `/plugin-setup.html`.
3. Generate a personal Plugin Token.
4. Paste that token into RuneLite: `Ironkin Bingo` → `Plugin Token`.
5. Use `Test Connection` to confirm access.

Keep your Plugin Token private. If it is leaked, generate a new one on the Ironkin website.

## Data sent

This plugin sends only matching Bingo proof information to Ironkin-controlled endpoints:

- RuneScape player name
- item name and item id
- item quantity
- estimated Grand Exchange value
- drop source
- screenshot
- submission timestamp

It does not automate gameplay, click for the player, make decisions for the player, or read passwords.
