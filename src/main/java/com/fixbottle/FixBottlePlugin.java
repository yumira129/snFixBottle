package com.fixbottle;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public final class FixBottlePlugin extends JavaPlugin implements Listener, TabExecutor {
    private NamespacedKey idKey;
    private NamespacedKey uniqueKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        idKey = new NamespacedKey(this, "fix_bottle");
        uniqueKey = new NamespacedKey(this, "unique");
        Bukkit.getPluginManager().registerEvents(this, this);
        register("givefixbottle");
        register("fixbottle");
    }

    private void register(String name) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
    }

    public ItemStack createBottle(int amount) {
        ItemStack item = new ItemStack(Material.EXPERIENCE_BOTTLE, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(buildName());
        List<String> lore = getConfig().getStringList("item.lore").stream()
                .filter(Objects::nonNull).filter(s -> !s.isEmpty()).map(this::color).collect(Collectors.toList());
        if (!lore.isEmpty()) meta.setLore(lore);
        if (getConfig().getBoolean("item.glint", true)) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.BYTE, (byte) 1);
        if (getConfig().getBoolean("item.unstackable", true))
            pdc.set(uniqueKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        item.setItemMeta(meta);
        return item;
    }

    private String buildName() {
        String raw = getConfig().getString("item.name", "Пузырёк починки");
        if (raw == null) raw = "Пузырёк починки";
        if (raw.indexOf('&') >= 0 || raw.indexOf(ChatColor.COLOR_CHAR) >= 0) return color(raw);
        ChatColor c = ChatColor.GREEN;
        try { c = ChatColor.valueOf(getConfig().getString("item.name-color", "GREEN").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { }
        return c + color(raw);
    }

    private boolean isBottle(ItemStack item) {
        if (item == null || item.getType() != Material.EXPERIENCE_BOTTLE || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(idKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBottleLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownExpBottle)) return;
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player)) return;
        Player player = (Player) projectile.getShooter();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isBottle(hand)) return;

        event.setCancelled(true);
        int repaired = repairInventory(player);
        if (repaired == 0) {
            actionBar(player, msg("nothing"));
            return;
        }
        consumeOne(player);
        player.updateInventory();
        playEffects(player);
        actionBar(player, msg("repaired").replace("{count}", String.valueOf(repaired)));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isBottle(event.getItem())) return;
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
        Player player = event.getPlayer();
        int repaired = repairInventory(player);
        if (repaired == 0) { actionBar(player, msg("nothing")); return; }
        consumeOne(player);
        player.updateInventory();
        playEffects(player);
        actionBar(player, msg("repaired").replace("{count}", String.valueOf(repaired)));
    }

    private int repairInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        int repaired = 0;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (repairItem(item)) { inv.setItem(slot, item); repaired++; }
        }
        return repaired;
    }

    private boolean repairItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getType().getMaxDurability() <= 0) return false;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable)) return false;
        Damageable damageable = (Damageable) meta;
        if (!damageable.hasDamage()) return false;
        damageable.setDamage(0);
        item.setItemMeta(meta);
        return true;
    }

    private void consumeOne(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    private void playEffects(Player player) {
        Location loc = player.getLocation().add(0, 1.0, 0);
        String soundName = getConfig().getString("effects.sound", "ENTITY_EXPERIENCE_BOTTLE_THROW");
        float volume = (float) getConfig().getDouble("effects.sound-volume", 1.0);
        float pitch = (float) getConfig().getDouble("effects.sound-pitch", 1.2);
        try { player.getWorld().playSound(loc, Sound.valueOf(soundName.toUpperCase(Locale.ROOT)), volume, pitch); }
        catch (IllegalArgumentException ex) { player.getWorld().playSound(loc, soundName, volume, pitch); }
        int count = Math.max(0, getConfig().getInt("effects.particle-count", 12));
        if (count == 0) return;
        try {
            Particle particle = Particle.valueOf(getConfig().getString("effects.particle", "VILLAGER_HAPPY").toUpperCase(Locale.ROOT));
            player.getWorld().spawnParticle(particle, loc, count, 0.35, 0.45, 0.35, 0.02);
        } catch (IllegalArgumentException ex) { getLogger().warning("Неизвестный effects.particle, партиклы пропущены"); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("fixbottle")) {
            if (!sender.hasPermission("fixbottle.admin")) { send(sender, msg("no-permission")); return true; }
            reloadConfig(); send(sender, msg("reloaded")); return true;
        }
        if (!sender.hasPermission("fixbottle.give")) { send(sender, msg("no-permission")); return true; }
        Player target;
        if (args.length >= 1) target = Bukkit.getPlayerExact(args[0]);
        else target = sender instanceof Player ? (Player) sender : null;
        if (target == null) { send(sender, args.length >= 1 ? msg("player-not-found").replace("{player}", args[0]) : msg("console-needs-player")); return true; }
        int amount = 1;
        if (args.length >= 2) {
            try { amount = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { send(sender, msg("bad-amount")); return true; }
            if (amount < 1 || amount > 576) { send(sender, msg("bad-amount")); return true; }
        }
        give(target, amount);
        send(target, msg("received").replace("{amount}", String.valueOf(amount)));
        if (!target.equals(sender)) send(sender, msg("given").replace("{amount}", String.valueOf(amount)).replace("{player}", target.getName()));
        return true;
    }

    private void give(Player target, int amount) {
        for (int i = 0; i < amount; i++) {
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(createBottle(1));
            leftover.values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        }
        target.updateInventory();
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("givefixbottle")) return Collections.emptyList();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 2) return Arrays.asList("1", "8", "16", "64");
        return Collections.emptyList();
    }

    private String msg(String path) { return color(getConfig().getString("messages." + path, "")); }
    private String color(String input) { return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input); }
    private void send(CommandSender sender, String text) { if (text != null && !text.isEmpty()) sender.sendMessage(text); }
    private void actionBar(Player player, String text) { player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text)); }
}
