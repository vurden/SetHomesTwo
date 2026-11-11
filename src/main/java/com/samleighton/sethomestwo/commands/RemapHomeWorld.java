package com.samleighton.sethomestwo.commands;

import com.samleighton.sethomestwo.dao.HomesDao;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class RemapHomeWorld implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("Run this command from the server console.");
            return true;
        }
        if (args.length != 2) return false;

        UUID oldWorld;
        try {
            oldWorld = UUID.fromString(args[0]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Invalid old world UUID: " + args[0]);
            return true;
        }

        World destination = Bukkit.getWorld(args[1]);
        if (destination == null) {
            sender.sendMessage("World '" + args[1] + "' is not loaded.");
            return true;
        }
        if (oldWorld.equals(destination.getUID())) {
            sender.sendMessage("The old and destination world UUIDs are the same.");
            return true;
        }

        int updated = new HomesDao().remapWorld(oldWorld, destination.getUID());
        if (updated < 0) {
            sender.sendMessage("Could not update homes. Check the database and server log.");
        } else {
            sender.sendMessage("Updated " + updated + " home(s) from " + oldWorld + " to "
                    + destination.getName() + " (" + destination.getUID() + ").");
        }
        return true;
    }
}
