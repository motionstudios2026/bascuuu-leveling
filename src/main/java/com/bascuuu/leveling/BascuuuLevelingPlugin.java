package com.bascuuu.leveling;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BascuuuLevelingPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final int MAX_LEVEL = 500;
    private static final String PLAYER_DATA_FOLDER = "data";
    private final Map<UUID, PlayerSkills> playerSkills = new ConcurrentHashMap<>();
    private final Map<Skill, CostFormula> costFormula = new EnumMap<>(Skill.class);
    private final Map<Skill, EffectSettings> effectSettings = new EnumMap<>(Skill.class);
    private MenuConfig menuConfig;
    private boolean papiEnabled;
    private Economy economy;
    private BukkitTask autoSaveTask;
    private NamespacedKey menuSkillKey;
    private NamespacedKey menuItemKey;

    @Override
    public void onEnable() {
        this.menuSkillKey = new NamespacedKey(this, "menu-skill");
        this.menuItemKey = new NamespacedKey(this, "menu-item");
        saveDefaultConfig();
        saveResource("menu.yml", false);
        createDataFolder();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("bascuuu").setExecutor(this);
        getCommand("bascuuu").setTabCompleter(this);
        getCommand("leveling").setExecutor(this);
        getCommand("leveling").setTabCompleter(this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            papiEnabled = true;
            new BascuuuPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI detected: bascuuu placeholders registered.");
        } else {
            papiEnabled = false;
            getLogger().info("PlaceholderAPI not detected: internal placeholders still work.");
        }

        if (!setupEconomy()) {
            getLogger().warning("Vault not found or economy provider unavailable. Upgrade purchases will be disabled.");
        }

        scheduleAutoSave();
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        saveAllPlayers();
    }

    private void loadSettings() {
        reloadConfig();
        loadConfigValues();
        loadMenuConfiguration();
        saveConfig();
    }

    private void createDataFolder() {
        File folder = new File(getDataFolder(), PLAYER_DATA_FOLDER);
        if (!folder.exists()) {
            if (!folder.mkdirs()) {
                getLogger().warning("Unable to create data folder for player storage.");
            }
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    private void loadConfigValues() {
        FileConfiguration cfg = getConfig();
        this.menuConfig = MenuConfig.fromConfiguration(cfg, getLogger());
        this.costFormula.clear();
        this.effectSettings.clear();

        for (Skill skill : Skill.values()) {
            String path = "cost-formula." + skill.getId();
            ConfigurationSection formulaSection = cfg.getConfigurationSection(path);
            if (formulaSection == null) {
                getLogger().warning("Missing cost formula for " + skill.getId() + ", using defaults.");
                costFormula.put(skill, new CostFormula(100.0, 1.05));
            } else {
                double base = Math.max(1.0, formulaSection.getDouble("base-cost", 100.0));
                double multiplier = Math.max(1.01, formulaSection.getDouble("multiplier", 1.05));
                costFormula.put(skill, new CostFormula(base, multiplier));
            }

            String effectPath = "effects." + skill.getId();
            ConfigurationSection effectSection = cfg.getConfigurationSection(effectPath);
            if (effectSection == null) {
                getLogger().warning("Missing effect settings for " + skill.getId() + ", using defaults.");
                effectSettings.put(skill, new EffectSettings(0.0, "ARMOR_TOUGHNESS"));
            } else {
                if (skill == Skill.XP_BOOST) {
                    effectSettings.put(skill, new EffectSettings(effectSection.getDouble("percent-per-level", 0.5), null));
                } else {
                    effectSettings.put(skill, new EffectSettings(effectSection.getDouble("value-per-level", 0.0), effectSection.getString("apply-to", "ARMOR_TOUGHNESS")));
                }
            }
        }
    }

    private void loadMenuConfiguration() {
        File menuFile = new File(getDataFolder(), "menu.yml");
        if (!menuFile.exists()) {
            saveResource("menu.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(menuFile);
        this.menuConfig = MenuConfig.fromMenuConfiguration(config, getLogger(), this.menuConfig);
    }

    private void scheduleAutoSave() {
        int minutes = getConfig().getInt("auto-save-minutes", 5);
        if (minutes <= 0) {
            return;
        }
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveAllPlayers, minutes * 60L * 20L, minutes * 60L * 20L);
        getLogger().info("Auto-save de jugador programado cada " + minutes + " minutos.");
    }

    private void saveAllPlayers() {
        for (UUID uuid : new HashSet<>(playerSkills.keySet())) {
            savePlayerData(uuid);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        loadPlayerData(player);
        applyAllModifiers(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        savePlayerData(uuid);
        playerSkills.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BascuuuMenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        ItemMeta meta = event.getCurrentItem().getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(menuSkillKey, PersistentDataType.STRING)) {
            String rawSkill = pdc.get(menuSkillKey, PersistentDataType.STRING);
            if (rawSkill == null) {
                return;
            }
            Skill skill = Skill.fromId(rawSkill);
            if (skill == null) {
                return;
            }
            if (event.getClick() == ClickType.LEFT) {
                handleSkillPurchase(player, skill, event.getSlot());
            } else if (event.getClick().isRightClick()) {
                sendSkillInfo(player, skill);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        int level = getPlayerLevel(player, Skill.XP_BOOST);
        if (level <= 0) {
            return;
        }
        double percentPerLevel = effectSettings.getOrDefault(Skill.XP_BOOST, new EffectSettings(0.5, null)).value;
        double bonus = level * percentPerLevel / 100.0;
        int newAmount = (int) Math.ceil(event.getAmount() * (1.0 + bonus));
        event.setAmount(Math.max(event.getAmount(), newAmount));
    }

    private void handleSkillPurchase(Player player, Skill skill, int slot) {
        if (economy == null) {
            player.sendMessage(colorize("&cNo hay economía disponible. Instala Vault y un plugin de economía."));
            playSound(player, menuConfig.soundFail);
            return;
        }
        int currentLevel = getPlayerLevel(player, skill);
        if (currentLevel >= MAX_LEVEL) {
            player.sendMessage(colorize("&cYa alcanzaste el nivel máximo de " + skill.getDisplayName() + "."));
            playSound(player, menuConfig.soundFail);
            return;
        }
        double cost = getNextCost(skill, currentLevel);
        if (!economy.has(player, cost)) {
            player.sendMessage(colorize("&cNo tienes suficiente dinero. Necesitas &f$" + formatCost(cost) + "&c."));
            playSound(player, menuConfig.soundFail);
            return;
        }

        economy.withdrawPlayer(player, cost);
        setPlayerLevel(player, skill, currentLevel + 1, true);
        applySkillModifier(player, skill, currentLevel + 1);
        refreshMenuSlot(player, slot);
        refreshSummarySlot(player);
        playSound(player, menuConfig.soundSuccess);
        player.sendActionBar(colorize("&aHas subido &f" + skill.getDisplayName() + " &aal nivel &f" + (currentLevel + 1) + "&a!"));
    }

    private void refreshMenuSlot(Player player, int slot) {
        Inventory view = player.getOpenInventory().getTopInventory();
        if (view == null || !(view.getHolder() instanceof BascuuuMenuHolder)) {
            return;
        }
        ItemStack item = view.getItem(slot);
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(menuSkillKey, PersistentDataType.STRING)) {
            return;
        }
        String rawSkill = pdc.get(menuSkillKey, PersistentDataType.STRING);
        Skill skill = Skill.fromId(rawSkill);
        if (skill == null) {
            return;
        }
        view.setItem(slot, buildMenuItem(player, skill, menuConfig.items.get(skill)));
        player.updateInventory();
    }

    private void refreshSummarySlot(Player player) {
        Inventory view = player.getOpenInventory().getTopInventory();
        if (view == null || !(view.getHolder() instanceof BascuuuMenuHolder)) {
            return;
        }
        for (Map.Entry<Skill, MenuItem> entry : menuConfig.items.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
        }
        for (MenuItem item : menuConfig.items.values()) {
            if (item.isSummary) {
                int slot = item.slot;
                view.setItem(slot, buildSummaryItem(player, item));
                player.updateInventory();
                return;
            }
        }
    }

    private void sendSkillInfo(Player player, Skill skill) {
        int currentLevel = getPlayerLevel(player, skill);
        double nextCost = currentLevel < MAX_LEVEL ? getNextCost(skill, currentLevel) : 0.0;
        player.sendMessage(colorize("&6" + skill.getDisplayName() + "&7 info:"));
        player.sendMessage(colorize(" &fNivel: &a" + currentLevel + "&7/&f" + MAX_LEVEL));
        player.sendMessage(colorize(" &fCosto siguiente nivel: &a$" + (currentLevel < MAX_LEVEL ? formatCost(nextCost) : "0")));
        player.sendMessage(colorize(" &fProgreso: &a" + buildProgressString(currentLevel, MAX_LEVEL, menuConfig.progressBar)));
    }

    private double getNextCost(Skill skill, int currentLevel) {
        CostFormula formula = costFormula.getOrDefault(skill, new CostFormula(100.0, 1.05));
        double cost = formula.baseCost * Math.pow(formula.multiplier, currentLevel);
        return Math.max(1.0, Math.ceil(cost));
    }

    private void applyAllModifiers(Player player) {
        for (Skill skill : Skill.values()) {
            applySkillModifier(player, skill, getPlayerLevel(player, skill));
        }
    }

    private void applySkillModifier(Player player, Skill skill, int level) {
        if (skill == Skill.XP_BOOST) {
            return;
        }
        Attribute attribute = skill.getAttribute(effectSettings.get(skill));
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        UUID modifierId = stableModifierUUID(player.getUniqueId(), skill);
        removeModifier(instance, modifierId);
        double amount = calculateSkillValue(skill, level);
        if (amount == 0.0) {
            return;
        }
        AttributeModifier modifier = new AttributeModifier(modifierId, "bascuuu-leveling-" + skill.getId(), amount, AttributeModifier.Operation.ADD_NUMBER);
        instance.addModifier(modifier);
        if (attribute == Attribute.GENERIC_MAX_HEALTH) {
            double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            if (player.getHealth() > maxHealth) {
                player.setHealth(maxHealth);
            }
        }
    }

    private double calculateSkillValue(Skill skill, int level) {
        EffectSettings effect = effectSettings.getOrDefault(skill, new EffectSettings(0.0, null));
        return level * effect.value;
    }

    private void removeModifier(AttributeInstance instance, UUID uuid) {
        for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
            if (modifier.getUniqueId().equals(uuid)) {
                instance.removeModifier(modifier);
            }
        }
    }

    public void setPlayerLevel(Player player, Skill skill, int level, boolean saveNow) {
        level = Math.max(0, Math.min(MAX_LEVEL, level));
        PlayerSkills skills = playerSkills.computeIfAbsent(player.getUniqueId(), uuid -> new PlayerSkills());
        skills.levels.put(skill, level);
        if (saveNow) {
            savePlayerData(player.getUniqueId());
        }
    }

    private int getPlayerLevel(Player player, Skill skill) {
        return playerSkills.getOrDefault(player.getUniqueId(), new PlayerSkills()).levels.getOrDefault(skill, 0);
    }

    private int getPlayerLevel(UUID uuid, Skill skill) {
        return playerSkills.getOrDefault(uuid, new PlayerSkills()).levels.getOrDefault(skill, 0);
    }

    private void loadPlayerData(Player player) {
        File playerFile = new File(getDataFolder(), PLAYER_DATA_FOLDER + File.separator + player.getUniqueId().toString() + ".yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(playerFile);
        PlayerSkills skills = new PlayerSkills();
        for (Skill skill : Skill.values()) {
            skills.levels.put(skill, cfg.getInt(skill.getId(), 0));
        }
        playerSkills.put(player.getUniqueId(), skills);
    }

    private void savePlayerData(UUID uuid) {
        PlayerSkills skills = playerSkills.get(uuid);
        if (skills == null) {
            return;
        }
        File playerFile = new File(getDataFolder(), PLAYER_DATA_FOLDER + File.separator + uuid.toString() + ".yml");
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<Skill, Integer> entry : skills.levels.entrySet()) {
            cfg.set(entry.getKey().getId(), entry.getValue());
        }
        try {
            cfg.save(playerFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error al guardar datos de jugador " + uuid, e);
        }
    }

    private void sendMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(new BascuuuMenuHolder(), menuConfig.size, colorize(menuConfig.title));
        if (menuConfig.fillItem.enabled) {
            for (int slot : menuConfig.fillItem.slotIndices) {
                if (slot >= 0 && slot < menuConfig.size) {
                    inventory.setItem(slot, createFillerItem());
                }
            }
        }
        for (Map.Entry<Skill, MenuItem> entry : menuConfig.items.entrySet()) {
            Skill skill = entry.getKey();
            MenuItem item = entry.getValue();
            if (!item.enabled) {
                continue;
            }
            if (item.isSummary) {
                inventory.setItem(item.slot, buildSummaryItem(player, item));
            } else if (skill != null) {
                inventory.setItem(item.slot, buildMenuItem(player, skill, item));
            }
        }
        player.openInventory(inventory);
    }

    private ItemStack createFillerItem() {
        ItemStack filler = new ItemStack(menuConfig.fillItem.material);
        ItemMeta meta = filler.getItemMeta();
        if (meta == null) {
            return filler;
        }
        meta.setDisplayName(" ");
        filler.setItemMeta(meta);
        return filler;
    }

    private ItemStack buildMenuItem(Player player, Skill skill, MenuItem itemConfig) {
        ItemStack item = new ItemStack(itemConfig.material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(colorize(replaceInternalPlaceholders(itemConfig.name, player, skill)));
        List<String> lore = new ArrayList<>();
        for (String line : itemConfig.lore) {
            lore.add(colorize(replaceInternalPlaceholders(line, player, skill)));
        }
        meta.setLore(lore);
        if (itemConfig.customModelData != null) {
            meta.setCustomModelData(itemConfig.customModelData);
        }
        if (itemConfig.glowIfMaxed && getPlayerLevel(player, skill) >= MAX_LEVEL) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.getPersistentDataContainer().set(menuSkillKey, PersistentDataType.STRING, skill.getId());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildSummaryItem(Player player, MenuItem itemConfig) {
        ItemStack item = new ItemStack(itemConfig.material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(colorize(replaceInternalPlaceholders(itemConfig.name, player, null)));
        List<String> lore = new ArrayList<>();
        for (String line : itemConfig.lore) {
            lore.add(colorize(replaceInternalPlaceholders(line, player, null)));
        }
        meta.setLore(lore);
        if (itemConfig.customModelData != null) {
            meta.setCustomModelData(itemConfig.customModelData);
        }
        meta.getPersistentDataContainer().set(menuSkillKey, PersistentDataType.STRING, "summary");
        item.setItemMeta(meta);
        return item;
    }

    private String replaceInternalPlaceholders(String text, Player player, Skill skill) {
        if (text == null) {
            return "";
        }
        String result = text;
        if (skill != null) {
            int level = getPlayerLevel(player, skill);
            result = result.replace("%level%", String.valueOf(level));
            result = result.replace("%max_level%", String.valueOf(MAX_LEVEL));
            result = result.replace("%cost%", String.valueOf((int) getNextCost(skill, level)));
            result = result.replace("%progress_bar%", buildProgressString(level, MAX_LEVEL, menuConfig.progressBar));
        }
        result = result.replace("%total_level%", String.valueOf(getTotalLevel(player)));
        result = result.replace("%next_cost_total%", String.valueOf((int) getTotalNextCost(player)));
        if (papiEnabled && player != null) {
            result = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, result);
        }
        return result;
    }

    private int getTotalLevel(Player player) {
        return playerSkills.getOrDefault(player.getUniqueId(), new PlayerSkills()).levels.values().stream().mapToInt(Integer::intValue).sum();
    }

    private double getTotalNextCost(Player player) {
        return Arrays.stream(Skill.values()).mapToDouble(skill -> {
            int lvl = getPlayerLevel(player, skill);
            return lvl >= MAX_LEVEL ? 0.0 : getNextCost(skill, lvl);
        }).sum();
    }

    private String buildProgressString(int level, int maxLevel, ProgressBar progressBar) {
        int filled = maxLevel == 0 ? 0 : (int) Math.round((double) level / maxLevel * progressBar.length);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < progressBar.length; i++) {
            if (i < filled) {
                builder.append(colorize(progressBar.filledColor)).append(progressBar.filled);
            } else {
                builder.append(colorize(progressBar.emptyColor)).append(progressBar.empty);
            }
        }
        return builder.toString();
    }

    private double formatCost(double cost) {
        return Math.max(0.0, Math.ceil(cost));
    }

    private void playSound(Player player, String soundName) {
        try {
            player.playSound(player.getLocation(), org.bukkit.Sound.valueOf(soundName), 1.0f, 1.0f);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Sound " + soundName + " no es válido.");
        }
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private UUID stableModifierUUID(UUID playerUuid, Skill skill) {
        return UUID.nameUUIDFromBytes((playerUuid.toString() + "-" + skill.getId()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) && args.length == 0) {
            sender.sendMessage("Este comando solo puede ejecutarlo un jugador o se requiere argumento.");
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                sendMenu(player);
                return true;
            }
            sender.sendMessage("Uso: /bascuuu <reload|set|info>");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("bascuuu.admin.reload")) {
                    sender.sendMessage(colorize("&cNo tienes permiso para recargar."));
                    return true;
                }
                loadSettings();
                sender.sendMessage(colorize("&aConfiguración recargada."));
                return true;
            }
            case "set" -> {
                if (!sender.hasPermission("bascuuu.admin.set")) {
                    sender.sendMessage(colorize("&cNo tienes permiso para establecer niveles."));
                    return true;
                }
                if (args.length < 4) {
                    sender.sendMessage(colorize("&cUso: /bascuuu set <jugador> <skill> <nivel>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(colorize("&cJugador no encontrado."));
                    return true;
                }
                Skill skill = Skill.fromId(args[2]);
                if (skill == null) {
                    sender.sendMessage(colorize("&cHabilidad desconocida. Usa: jump, strength, resistance, xp_boost."));
                    return true;
                }
                int level;
                try {
                    level = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(colorize("&cNivel inválido."));
                    return true;
                }
                if (level < 0 || level > MAX_LEVEL) {
                    sender.sendMessage(colorize("&cEl nivel debe estar entre 0 y " + MAX_LEVEL + "."));
                    return true;
                }
                setPlayerLevel(target, skill, level, true);
                applySkillModifier(target, skill, level);
                sender.sendMessage(colorize("&aNivel de " + skill.getDisplayName() + " ajustado a " + level + " para " + target.getName() + "."));
                target.sendMessage(colorize("&aTu nivel de " + skill.getDisplayName() + " ahora es &f" + level + "&a."));
                return true;
            }
            case "info" -> {
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(colorize("&cJugador no encontrado."));
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage(colorize("&cUso: /bascuuu info <jugador>"));
                    return true;
                }
                sender.sendMessage(colorize("&6Niveles de " + target.getName() + ":"));
                for (Skill skill : Skill.values()) {
                    sender.sendMessage(colorize(" &f" + skill.getDisplayName() + ": &a" + getPlayerLevel(target, skill) + "&7/" + MAX_LEVEL));
                }
                sender.sendMessage(colorize(" &fTotal: &a" + getTotalLevel(target)));
                return true;
            }
            default -> {
                if (sender instanceof Player player) {
                    sendMenu(player);
                } else {
                    sender.sendMessage(colorize("&cUso: /bascuuu <reload|set|info>"));
                }
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], Arrays.asList("reload", "set", "info"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            return partial(args[1], names);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("info"))) {
            return partial(args[2], Skill.displayNames());
        }
        return Collections.emptyList();
    }

    private List<String> partial(String token, List<String> options) {
        if (token == null) {
            return options;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }

    private static class PlayerSkills {
        private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
    }

    private enum Skill {
        JUMP("jump", "Salto", Attribute.GENERIC_JUMP_STRENGTH),
        STRENGTH("strength", "Fuerza", Attribute.GENERIC_ATTACK_DAMAGE),
        RESISTANCE("resistance", "Resistencia", Attribute.GENERIC_ARMOR_TOUGHNESS),
        XP_BOOST("xp_boost", "Más XP", null);

        private final String id;
        private final String displayName;
        private final Attribute baseAttribute;

        Skill(String id, String displayName, Attribute baseAttribute) {
            this.id = id;
            this.displayName = displayName;
            this.baseAttribute = baseAttribute;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Attribute getAttribute(EffectSettings settings) {
            if (this == RESISTANCE && settings != null && "MAX_HEALTH".equalsIgnoreCase(settings.applyTo)) {
                return Attribute.GENERIC_MAX_HEALTH;
            }
            return baseAttribute;
        }

        public static Skill fromId(String id) {
            if (id == null) {
                return null;
            }
            return Arrays.stream(values())
                    .filter(skill -> skill.id.equalsIgnoreCase(id) || skill.displayName.equalsIgnoreCase(id))
                    .findFirst().orElse(null);
        }

        public static List<String> displayNames() {
            List<String> names = new ArrayList<>();
            for (Skill skill : values()) {
                names.add(skill.id);
            }
            return names;
        }
    }

    private static class CostFormula {
        private final double baseCost;
        private final double multiplier;

        public CostFormula(double baseCost, double multiplier) {
            this.baseCost = baseCost;
            this.multiplier = multiplier;
        }
    }

    private static class EffectSettings {
        private final double value;
        private final String applyTo;

        public EffectSettings(double value, String applyTo) {
            this.value = value;
            this.applyTo = applyTo;
        }
    }

    private static class MenuConfig {
        private String title = "&8Bascuuu Leveling";
        private int size = 54;
        private FillItem fillItem = new FillItem();
        private ProgressBar progressBar = new ProgressBar("|", "-", 20, "&a", "&7");
        private String soundSuccess = "ENTITY_PLAYER_LEVELUP";
        private String soundFail = "ENTITY_VILLAGER_NO";
        private Map<Skill, MenuItem> items = new HashMap<>();

        public static MenuConfig fromConfiguration(FileConfiguration cfg, java.util.logging.Logger logger) {
            MenuConfig config = new MenuConfig();
            config.title = cfg.getString("menu.title", config.title);
            config.size = validateSize(cfg.getInt("menu.size", config.size), logger);
            config.fillItem = FillItem.fromSection(cfg.getConfigurationSection("menu.fill-item"), logger, config.size);
            config.progressBar = ProgressBar.fromSection(cfg.getConfigurationSection("menu.progress-bar"), logger);
            config.soundSuccess = cfg.getString("menu.sound-success", config.soundSuccess);
            config.soundFail = cfg.getString("menu.sound-fail", config.soundFail);
            config.items = MenuItem.fromMenuSection(cfg.getConfigurationSection("menu.items"), logger, config.size);
            return config;
        }

        public static MenuConfig fromMenuConfiguration(YamlConfiguration cfg, java.util.logging.Logger logger, MenuConfig previous) {
            MenuConfig config = previous == null ? new MenuConfig() : previous;
            if (cfg.contains("menu.title")) {
                config.title = cfg.getString("menu.title", config.title);
            }
            config.size = validateSize(cfg.getInt("menu.size", config.size), logger);
            config.fillItem = FillItem.fromSection(cfg.getConfigurationSection("menu.fill-item"), logger, config.size);
            config.items = MenuItem.fromMenuSection(cfg.getConfigurationSection("menu.items"), logger, config.size);
            return config;
        }

        private static int validateSize(int size, java.util.logging.Logger logger) {
            if (size < 9 || size > 54 || size % 9 != 0) {
                logger.warning("Tamaño de menú inválido: " + size + ". Usando 54.");
                return 54;
            }
            return size;
        }
    }

    private static class FillItem {
        private boolean enabled = true;
        private Material material = Material.GRAY_STAINED_GLASS_PANE;
        private List<Integer> slotIndices = new ArrayList<>();

        public static FillItem fromSection(ConfigurationSection section, java.util.logging.Logger logger, int inventorySize) {
            FillItem fill = new FillItem();
            if (section == null) {
                fill.fillBorderSlots(inventorySize);
                return fill;
            }
            fill.enabled = section.getBoolean("enabled", fill.enabled);
            fill.material = parseMaterial(section.getString("material"), Material.GRAY_STAINED_GLASS_PANE, logger);
            List<?> rawSlots = section.getList("slot", Collections.emptyList());
            if (rawSlots.isEmpty()) {
                fill.fillBorderSlots(inventorySize);
            } else {
                for (Object raw : rawSlots) {
                    if (raw instanceof Number number) {
                        int slot = number.intValue();
                        if (slot < 0 || slot >= inventorySize) {
                            logger.warning("Slot de fill-item inválido: " + slot + ". Ignorando.");
                        } else {
                            fill.slotIndices.add(slot);
                        }
                    } else if (raw instanceof String str && str.equalsIgnoreCase("border")) {
                        fill.fillBorderSlots(inventorySize);
                    } else {
                        logger.warning("Valor de slot desconocido en fill-item: " + raw);
                    }
                }
            }
            return fill;
        }

        private void fillBorderSlots(int inventorySize) {
            slotIndices.clear();
            int rows = inventorySize / 9;
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < 9; col++) {
                    if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                        slotIndices.add(row * 9 + col);
                    }
                }
            }
        }
    }

    private static class ProgressBar {
        private final String filled;
        private final String empty;
        private final int length;
        private final String filledColor;
        private final String emptyColor;

        public ProgressBar(String filled, String empty, int length, String filledColor, String emptyColor) {
            this.filled = filled;
            this.empty = empty;
            this.length = Math.max(1, Math.min(50, length));
            this.filledColor = filledColor;
            this.emptyColor = emptyColor;
        }

        public static ProgressBar fromSection(ConfigurationSection section, java.util.logging.Logger logger) {
            if (section == null) {
                return new ProgressBar("|", "-", 20, "&a", "&7");
            }
            String filled = section.getString("filled", "|");
            String empty = section.getString("empty", "-");
            int length = Math.max(1, section.getInt("length", 20));
            String filledColor = section.getString("filled-color", "&a");
            String emptyColor = section.getString("empty-color", "&7");
            return new ProgressBar(filled, empty, length, filledColor, emptyColor);
        }
    }

    private static class MenuItem {
        private boolean enabled = true;
        private int slot = 0;
        private Material material = Material.STONE;
        private String name = "&7Item";
        private List<String> lore = new ArrayList<>();
        private boolean glowIfMaxed = false;
        private Integer customModelData = null;
        private boolean isSummary = false;

        public static Map<Skill, MenuItem> fromMenuSection(ConfigurationSection section, java.util.logging.Logger logger, int inventorySize) {
            Map<Skill, MenuItem> items = new HashMap<>();
            if (section == null) {
                return items;
            }
            for (String key : section.getKeys(false)) {
                ConfigurationSection itemSection = section.getConfigurationSection(key);
                if (itemSection == null) {
                    continue;
                }
                MenuItem item = new MenuItem();
                item.enabled = itemSection.getBoolean("enabled", true);
                item.slot = itemSection.getInt("slot", 0);
                if (item.slot < 0 || item.slot >= inventorySize) {
                    logger.warning("Slot inválido para menu item " + key + ". Usando 0.");
                    item.slot = 0;
                }
                item.material = parseMaterial(itemSection.getString("material"), Material.STONE, logger);
                item.name = itemSection.getString("name", item.name);
                item.lore = itemSection.getStringList("lore");
                item.glowIfMaxed = itemSection.getBoolean("glow-if-maxed", false);
                if (itemSection.contains("custom-model-data")) {
                    item.customModelData = itemSection.getInt("custom-model-data");
                }
                if (key.equalsIgnoreCase("summary")) {
                    item.isSummary = true;
                    items.put(null, item);
                } else {
                    Skill skill = Skill.fromId(key);
                    if (skill == null) {
                        logger.warning("Habilidad desconocida en menu.yml: " + key);
                        continue;
                    }
                    items.put(skill, item);
                }
            }
            return items;
        }
    }

    private static Material parseMaterial(String raw, Material fallback, java.util.logging.Logger logger) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw.trim());
        if (material == null) {
            logger.warning("Material inválido: " + raw + ". Usando " + fallback.name() + ".");
            return fallback;
        }
        return material;
    }

    private static class BascuuuMenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class BascuuuPlaceholderExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {
        private final BascuuuLevelingPlugin plugin;

        public BascuuuPlaceholderExpansion(BascuuuLevelingPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String getIdentifier() {
            return "bascuuu";
        }

        @Override
        public String getAuthor() {
            return "bascuuu";
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (player == null) {
                return "";
            }
            for (Skill skill : Skill.values()) {
                if (identifier.equalsIgnoreCase("level_" + skill.getId())) {
                    return String.valueOf(plugin.getPlayerLevel(player, skill));
                }
                if (identifier.equalsIgnoreCase("maxlevel_" + skill.getId())) {
                    return String.valueOf(MAX_LEVEL);
                }
                if (identifier.equalsIgnoreCase("cost_" + skill.getId())) {
                    return String.valueOf((int) plugin.getNextCost(skill, plugin.getPlayerLevel(player, skill)));
                }
                if (identifier.equalsIgnoreCase("progress_" + skill.getId())) {
                    return String.valueOf((int) Math.round((double) plugin.getPlayerLevel(player, skill) / MAX_LEVEL * 100));
                }
            }
            if (identifier.equalsIgnoreCase("total_level")) {
                return String.valueOf(plugin.getTotalLevel(player));
            }
            if (identifier.equalsIgnoreCase("next_cost_total")) {
                return String.valueOf((int) plugin.getTotalNextCost(player));
            }
            return "";
        }
    }
}
