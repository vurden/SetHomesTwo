---
bump: patch
---

Fix home renaming on 1.21.11 reading the old output-item name instead of the entered text, compile against Spigot 1.21.11, and add an isolated test-server launcher.

Keep homes whose original world is missing or unloaded visible without crashing menus or commands. Preserve their saved world UUID and coordinates, and refuse teleporting until that world is available again.

Add the console command `remap-home-world <old-world-uuid> <loaded-world-name>` to reconnect saved homes after a world UUID changes.
