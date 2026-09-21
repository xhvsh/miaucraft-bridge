package com.xhvsh.miaucraftbridge;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public final class BridgeCommand implements CommandExecutor, TabCompleter {

  private static final List<String> SUBCOMMANDS = List.of("reload", "status", "test", "stats", "update");
  private static final List<String> UPDATE_ARGS = List.of("check", "apply", "status");

  private final MiaucraftBridgePlugin plugin;

  public BridgeCommand(MiaucraftBridgePlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("miaucraftbridge.admin")) {
      sender.sendMessage("§cYou don't have permission to use this.");
      return true;
    }

    String sub = args.length == 0 ? "status" : args[0].toLowerCase();
    switch (sub) {
      case "reload" -> plugin.reloadRemote(sender);
      case "status" -> plugin.statusLines().forEach(sender::sendMessage);
      case "test" -> plugin.testConnection(sender);
      case "stats" -> plugin.forceStats(sender);
      case "update" -> {
        String action = args.length >= 2 ? args[1].toLowerCase() : "check";
        switch (action) {
          case "apply" -> plugin.applyUpdate(sender);
          case "status" -> plugin.statusLines().forEach(sender::sendMessage);
          default -> plugin.checkUpdate(sender);
        }
      }
      default -> {
        sender.sendMessage("§e/bridge reload §7- re-fetch the remote config");
        sender.sendMessage("§e/bridge status §7- show plugin/remote state");
        sender.sendMessage("§e/bridge test §7- check the Supabase connection");
        sender.sendMessage("§e/bridge stats §7- force a stat reconcile now");
        sender.sendMessage("§e/bridge update [check|apply] §7- check for / install a newer jar");
      }
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      String prefix = args[0].toLowerCase();
      return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("update")) {
      String prefix = args[1].toLowerCase();
      return UPDATE_ARGS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
    return List.of();
  }
}
