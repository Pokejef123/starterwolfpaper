package org.pokejef.starterwolfpaper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.*;

public final class Starterwolfpaper extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // --- NAMESPACED KEYS (Data Vault) ---
    private NamespacedKey KEY_IS_STARTER;
    private NamespacedKey KEY_IS_RPG;
    private NamespacedKey KEY_CLASS;
    private NamespacedKey KEY_LEVEL;
    private NamespacedKey KEY_XP;
    private NamespacedKey KEY_SKILL_POINTS;
    private NamespacedKey KEY_VITALITY;
    private NamespacedKey KEY_FEROCITY;
    private NamespacedKey KEY_SWIFTNESS;
    private NamespacedKey KEY_IS_GHOST;
    private NamespacedKey KEY_IS_BERSERK;
    private NamespacedKey KEY_SATCHEL;
    private NamespacedKey KEY_PLAYER_HAS_WOLF;
    private NamespacedKey KEY_GUI_WOLF_UUID;
    private NamespacedKey KEY_GEM_SOCKETED;
    private NamespacedKey KEY_GEM_LIFESTEAL;
    private NamespacedKey KEY_GEM_RESIST;
    private NamespacedKey KEY_SCENT_TARGET;

    // Active GUI & Action Tracking
    private final Map<UUID, UUID> openMenus = new HashMap<>();
    private final Map<UUID, UUID> openSatchels = new HashMap<>();
    private final Set<UUID> openPackMenus = new HashSet<>();
    private final Map<UUID, UUID> pendingRenames = new HashMap<>();
    private final Map<UUID, Location> activeScentTrails = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        KEY_IS_STARTER = new NamespacedKey(this, "is_starter");
        KEY_IS_RPG = new NamespacedKey(this, "is_rpg");
        KEY_CLASS = new NamespacedKey(this, "archetype");
        KEY_LEVEL = new NamespacedKey(this, "rpg_level");
        KEY_XP = new NamespacedKey(this, "rpg_xp");
        KEY_SKILL_POINTS = new NamespacedKey(this, "skill_points");
        KEY_VITALITY = new NamespacedKey(this, "stat_vitality");
        KEY_FEROCITY = new NamespacedKey(this, "stat_ferocity");
        KEY_SWIFTNESS = new NamespacedKey(this, "stat_swiftness");
        KEY_IS_GHOST = new NamespacedKey(this, "is_ghost");
        KEY_IS_BERSERK = new NamespacedKey(this, "is_berserk");
        KEY_SATCHEL = new NamespacedKey(this, "satchel_data");
        KEY_PLAYER_HAS_WOLF = new NamespacedKey(this, "has_starter_wolf");
        KEY_GUI_WOLF_UUID = new NamespacedKey(this, "gui_wolf_uuid");
        KEY_GEM_SOCKETED = new NamespacedKey(this, "gem_socketed");
        KEY_GEM_LIFESTEAL = new NamespacedKey(this, "gem_lifesteal");
        KEY_GEM_RESIST = new NamespacedKey(this, "gem_resist");
        KEY_SCENT_TARGET = new NamespacedKey(this, "scent_target");

        getServer().getPluginManager().registerEvents(this, this);

        org.bukkit.command.PluginCommand cmd = getCommand("starterwolf");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                handleWolfTick();
            }
        }.runTaskTimer(this, 20L, 20L);

        new BukkitRunnable() {
            @Override
            public void run() {
                handleRidingPhysics();
            }
        }.runTaskTimer(this, 1L, 1L);

        getLogger().info("StarterWolfPaper 2.0.0 completely optimized and verified!");
    }

    // =========================================================================
    // 1. STATS, SCALING & HIERARCHY
    // =========================================================================
    private String getWolfRank(int level, String archetype) {
        if (!"UNAWAKENED".equals(archetype)) return archetype;
        return switch (level) {
            case 1 -> "Pup";
            case 2 -> "Juvenile";
            case 3 -> "Omega";
            case 4 -> "Kappa";
            case 5 -> "Iota";
            case 6 -> "Zeta";
            case 7 -> "Epsilon";
            case 8 -> "Delta";
            case 9 -> "Gamma";
            default -> "Beta";
        };
    }

    private int getMaxLevel(boolean isAlpha, String archetype) {
        if (isAlpha) return getConfig().getInt("levels.alpha-max-level", 20);
        if ("UNAWAKENED".equals(archetype)) return getConfig().getInt("levels.unawakened-max-level", 10);
        return getConfig().getInt("levels.archetype-max-level", 20);
    }

    public void applyStats(Wolf wolf, int level) {
        PersistentDataContainer pdc = wolf.getPersistentDataContainer();
        boolean isAlpha = pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false);
        String archetype = pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, "UNAWAKENED");
        int vit = pdc.getOrDefault(KEY_VITALITY, PersistentDataType.INTEGER, 0);
        int fero = pdc.getOrDefault(KEY_FEROCITY, PersistentDataType.INTEGER, 0);
        int spd = pdc.getOrDefault(KEY_SWIFTNESS, PersistentDataType.INTEGER, 0);

        int bonusLevels = Math.max(0, level - 1);
        double classMult = isAlpha ? 1.0 : 0.5;

        AttributeInstance maxHpAttr = wolf.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHpAttr != null) {
            double targetHp = getConfig().getDouble("stats.base-health", 40.0)
                    + (bonusLevels * getConfig().getDouble("stats.health-per-level", 2.0) * classMult)
                    + (vit * getConfig().getDouble("stats.health-per-vitality-point", 4.0));
            maxHpAttr.setBaseValue(targetHp);
        }

        AttributeInstance atkAttr = wolf.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        if (atkAttr != null) {
            double targetAtk = getConfig().getDouble("stats.base-damage", 4.0)
                    + (bonusLevels * getConfig().getDouble("stats.damage-per-level", 0.5) * classMult)
                    + (fero * getConfig().getDouble("stats.damage-per-ferocity-point", 0.75));
            atkAttr.setBaseValue(targetAtk);
        }

        AttributeInstance spdAttr = wolf.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (spdAttr != null) {
            double targetSpd = getConfig().getDouble("stats.base-speed", 0.30)
                    + (spd * getConfig().getDouble("stats.speed-per-swiftness-point", 0.02));
            spdAttr.setBaseValue(targetSpd);
        }

        AttributeInstance scaleAttr = wolf.getAttribute(Attribute.GENERIC_SCALE);
        if (scaleAttr != null) {
            double targetScale;
            if (isAlpha) {
                targetScale = 1.0 + (bonusLevels * (4.0 / 19.0));
                scaleAttr.setBaseValue(Math.min(getConfig().getDouble("stats.alpha-max-scale", 5.0), targetScale));
            } else if ("UNAWAKENED".equals(archetype)) {
                if (level < 3) wolf.setBaby();
                else wolf.setAdult();
                wolf.setAgeLock(true);
                double pupBase = getConfig().getDouble("stats.pup-base-scale", 0.50);
                targetScale = pupBase + (bonusLevels * ((1.0 - pupBase) / 9.0));
                scaleAttr.setBaseValue(Math.min(1.0, targetScale));
            } else {
                wolf.setAdult();
                int archLevels = Math.max(0, level - 10);
                targetScale = 1.0 + (archLevels * 0.30);
                scaleAttr.setBaseValue(Math.min(getConfig().getDouble("stats.archetype-max-scale", 4.0), targetScale));
            }
        }

        int alphaMax = getConfig().getInt("levels.alpha-max-level", 20);
        if (level >= alphaMax) wolf.setCollarColor(DyeColor.PURPLE);
        else if (level >= (alphaMax * 0.75)) wolf.setCollarColor(DyeColor.LIGHT_BLUE);
        else if (level >= (alphaMax * 0.5)) wolf.setCollarColor(DyeColor.YELLOW);
        else if (level >= (alphaMax * 0.25)) wolf.setCollarColor(DyeColor.GREEN);
        else wolf.setCollarColor(DyeColor.RED);
    }

    public void addXP(Wolf wolf, int amount) {
        PersistentDataContainer pdc = wolf.getPersistentDataContainer();
        if (pdc.getOrDefault(KEY_IS_GHOST, PersistentDataType.BOOLEAN, false)) return;

        boolean isAlpha = pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false);
        String archetype = pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, "UNAWAKENED");
        int maxLvl = getMaxLevel(isAlpha, archetype);
        int level = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1);
        if (level >= maxLvl) return;

        int currentXp = pdc.getOrDefault(KEY_XP, PersistentDataType.INTEGER, 0) + amount;
        int needed = getConfig().getInt("levels.xp-base-requirement", 50) + (level * getConfig().getInt("levels.xp-per-level-multiplier", 25));
        boolean leveledUp = false;

        while (currentXp >= needed && level < maxLvl) {
            currentXp -= needed;
            level++;
            int pts = pdc.getOrDefault(KEY_SKILL_POINTS, PersistentDataType.INTEGER, 0);
            pdc.set(KEY_SKILL_POINTS, PersistentDataType.INTEGER, pts + 1);
            needed = getConfig().getInt("levels.xp-base-requirement", 50) + (level * getConfig().getInt("levels.xp-per-level-multiplier", 25));
            leveledUp = true;
        }

        pdc.set(KEY_LEVEL, PersistentDataType.INTEGER, level);
        pdc.set(KEY_XP, PersistentDataType.INTEGER, currentXp);

        if (leveledUp) {
            applyStats(wolf, level);
            if (wolf.getOwner() instanceof Player p) {
                if ("UNAWAKENED".equals(archetype)) {
                    p.sendMessage(Component.text("⭐ " + wolf.getName() + " reached Level " + level + " and ranked up to " + getWolfRank(level, archetype) + "! (+1 Skill Point)", NamedTextColor.GREEN));
                    if (level == 10) p.sendMessage(Component.text("✨ " + wolf.getName() + " is now a Beta! It is ready to awaken its Archetype!", NamedTextColor.GOLD));
                } else {
                    p.sendMessage(Component.text("⭐ " + wolf.getName() + " reached Level " + level + "! (+1 Skill Point)", NamedTextColor.GREEN));
                }
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

                if (!isAlpha) {
                    for (World w : Bukkit.getWorlds()) {
                        for (Wolf alphaCandidate : w.getEntitiesByClass(Wolf.class)) {
                            if (alphaCandidate.isTamed() && p.equals(alphaCandidate.getOwner())) {
                                PersistentDataContainer alphaPdc = alphaCandidate.getPersistentDataContainer();
                                if (alphaPdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false)) {
                                    int alphaPts = alphaPdc.getOrDefault(KEY_SKILL_POINTS, PersistentDataType.INTEGER, 0);
                                    alphaPdc.set(KEY_SKILL_POINTS, PersistentDataType.INTEGER, alphaPts + 1);
                                    p.sendMessage(Component.text("👑 Your Alpha gained a bonus Skill Point from the pack's growth!", NamedTextColor.GOLD));
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 2. GHOST, TAME, RENAME & ARMOR SOCKETING
    // =========================================================================
    private void enterGhostState(Wolf wolf, Player owner) {
        PersistentDataContainer pdc = wolf.getPersistentDataContainer();
        pdc.set(KEY_IS_GHOST, PersistentDataType.BOOLEAN, true);
        pdc.set(KEY_IS_BERSERK, PersistentDataType.BOOLEAN, false);
        wolf.setAngry(false);

        int lvl = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1);
        int newLvl = Math.max(1, lvl - 1);
        pdc.set(KEY_LEVEL, PersistentDataType.INTEGER, newLvl);
        pdc.set(KEY_XP, PersistentDataType.INTEGER, 0);
        applyStats(wolf, newLvl);

        wolf.setHealth(1.0);
        wolf.setTarget(null);
        wolf.setSitting(false);
        wolf.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));

        if (owner != null) {
            owner.sendMessage(Component.text("☠ Your Alpha has fallen and become a wandering spirit! (-1 Level penalty)", NamedTextColor.RED));
            owner.playSound(owner.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 0.5f);
        }
    }

    private void reviveAlpha(Wolf wolf, Player owner) {
        PersistentDataContainer pdc = wolf.getPersistentDataContainer();
        pdc.set(KEY_IS_GHOST, PersistentDataType.BOOLEAN, false);
        wolf.removePotionEffect(PotionEffectType.INVISIBILITY);

        AttributeInstance maxHp = wolf.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHp != null) wolf.setHealth(maxHp.getValue());

        wolf.teleport(owner.getLocation());
        wolf.getWorld().spawnParticle(Particle.LARGE_SMOKE, wolf.getLocation().add(0, 0.5, 0), 25, 0.5, 0.5, 0.5, 0.05);
        wolf.getWorld().playSound(wolf.getLocation(), Sound.ENTITY_WOLF_HOWL, 1.0f, 1.0f);
        owner.sendMessage(Component.text("✨ Your Alpha has been restored to the physical realm!", NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PersistentDataContainer pData = player.getPersistentDataContainer();

        if (!pData.getOrDefault(KEY_PLAYER_HAS_WOLF, PersistentDataType.BOOLEAN, false)) {
            Wolf alpha = (Wolf) player.getWorld().spawnEntity(player.getLocation(), EntityType.WOLF);
            alpha.setTamed(true);
            alpha.setOwner(player);
            alpha.customName(Component.text(player.getName() + "'s Alpha Wolf", NamedTextColor.GOLD, TextDecoration.BOLD));
            alpha.setCustomNameVisible(true);

            PersistentDataContainer wData = alpha.getPersistentDataContainer();
            wData.set(KEY_IS_STARTER, PersistentDataType.BOOLEAN, true);
            wData.set(KEY_IS_RPG, PersistentDataType.BOOLEAN, true);
            wData.set(KEY_CLASS, PersistentDataType.STRING, "ALPHA");
            wData.set(KEY_LEVEL, PersistentDataType.INTEGER, 1);
            wData.set(KEY_XP, PersistentDataType.INTEGER, 0);
            wData.set(KEY_SKILL_POINTS, PersistentDataType.INTEGER, 0);

            applyStats(alpha, 1);
            pData.set(KEY_PLAYER_HAS_WOLF, PersistentDataType.BOOLEAN, true);
            player.sendMessage(Component.text("✨ Your immortal Starter Alpha Wolf has bound to your soul!", NamedTextColor.GREEN));
        }
    }

    @EventHandler
    public void onTame(EntityTameEvent event) {
        if (event.getEntity() instanceof Wolf wolf && event.getOwner() instanceof Player player) {
            PersistentDataContainer pdc = wolf.getPersistentDataContainer();
            if (!pdc.has(KEY_IS_RPG, PersistentDataType.BOOLEAN)) {
                pdc.set(KEY_IS_RPG, PersistentDataType.BOOLEAN, true);
                pdc.set(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false);
                pdc.set(KEY_CLASS, PersistentDataType.STRING, "UNAWAKENED");
                pdc.set(KEY_LEVEL, PersistentDataType.INTEGER, 1);
                pdc.set(KEY_XP, PersistentDataType.INTEGER, 0);
                pdc.set(KEY_SKILL_POINTS, PersistentDataType.INTEGER, 0);
                applyStats(wolf, 1);
                player.sendMessage(Component.text("🐾 You tamed a wild Pup! Raise it to Beta (Level 10) to awaken its true power.", NamedTextColor.YELLOW));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (pendingRenames.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            UUID wolfId = pendingRenames.remove(player.getUniqueId());
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());

            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(Component.text("Renaming cancelled.", NamedTextColor.RED));
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                Entity entity = Bukkit.getEntity(wolfId);
                if (entity instanceof Wolf wolf && wolf.isTamed() && player.equals(wolf.getOwner())) {
                    wolf.customName(Component.text(message, NamedTextColor.GOLD, TextDecoration.BOLD));
                    player.sendMessage(Component.text("✅ Wolf successfully renamed to " + message + "!", NamedTextColor.GREEN));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f);
                }
            });
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        Inventory inv = event.getInventory();
        ItemStack base = inv.getItem(0);
        ItemStack mat = inv.getItem(1);

        if (base != null && base.getType() == Material.WOLF_ARMOR && mat != null) {
            ItemStack result = base.clone();
            ItemMeta meta = result.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();

            if (pdc.has(KEY_GEM_SOCKETED, PersistentDataType.BOOLEAN)) return;

            List<Component> lore = new ArrayList<>();
            NamespacedKey attrKey = new NamespacedKey(this, "gem_modifier");

            if (mat.getType() == Material.DIAMOND) {
                meta.addAttributeModifier(Attribute.GENERIC_ARMOR, new AttributeModifier(attrKey, 10.0, AttributeModifier.Operation.ADD_NUMBER));
                lore.add(Component.text("✦ Socket: Pristine Diamond (+10 Armor)", NamedTextColor.AQUA));
            } else if (mat.getType() == Material.REDSTONE_BLOCK) {
                meta.addAttributeModifier(Attribute.GENERIC_MAX_HEALTH, new AttributeModifier(attrKey, 15.0, AttributeModifier.Operation.ADD_NUMBER));
                lore.add(Component.text("✦ Socket: Blood Ruby (+15 Max HP)", NamedTextColor.RED));
            } else if (mat.getType() == Material.GOLD_INGOT) {
                meta.addAttributeModifier(Attribute.GENERIC_MOVEMENT_SPEED, new AttributeModifier(attrKey, 0.05, AttributeModifier.Operation.ADD_NUMBER));
                lore.add(Component.text("✦ Socket: Topaz (+Speed)", NamedTextColor.YELLOW));
            } else if (mat.getType() == Material.EMERALD) {
                pdc.set(KEY_GEM_LIFESTEAL, PersistentDataType.BOOLEAN, true);
                lore.add(Component.text("✦ Socket: Emerald (+5% Lifesteal)", NamedTextColor.GREEN));
            } else if (mat.getType() == Material.AMETHYST_SHARD) {
                pdc.set(KEY_GEM_RESIST, PersistentDataType.BOOLEAN, true);
                lore.add(Component.text("✦ Socket: Amethyst (25% Magic/Poison Resist)", NamedTextColor.DARK_PURPLE));
            } else {
                return;
            }

            pdc.set(KEY_GEM_SOCKETED, PersistentDataType.BOOLEAN, true);
            meta.lore(lore);
            result.setItemMeta(meta);
            event.setResult(result);

            event.getInventory().setRepairCost(1);
        }
    }

    // =========================================================================
    // 3. COMBAT, ULTIMATES, LIFESTEAL & RESIST
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Wolf wolf) {
            PersistentDataContainer pdc = wolf.getPersistentDataContainer();
            if (!pdc.getOrDefault(KEY_IS_RPG, PersistentDataType.BOOLEAN, false)) return;
            if (pdc.getOrDefault(KEY_IS_GHOST, PersistentDataType.BOOLEAN, false)) {
                event.setCancelled(true);
                return;
            }

            ItemStack armor = wolf.getEquipment().getItem(EquipmentSlot.BODY);
            if (armor.getType() == Material.WOLF_ARMOR) {
                if (armor.getItemMeta().getPersistentDataContainer().has(KEY_GEM_RESIST, PersistentDataType.BOOLEAN)) {
                    if (event.getCause() == EntityDamageEvent.DamageCause.MAGIC ||
                            event.getCause() == EntityDamageEvent.DamageCause.POISON ||
                            event.getCause() == EntityDamageEvent.DamageCause.DRAGON_BREATH) {
                        event.setDamage(event.getDamage() * 0.75);
                    }
                }
            }

            if (event.getFinalDamage() >= wolf.getHealth()) {
                if (pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false)) {
                    event.setCancelled(true);
                    enterGhostState(wolf, (Player) wolf.getOwner());
                } else {
                    if (wolf.getOwner() instanceof Player owner) {
                        owner.sendMessage(Component.text("☠ " + wolf.getName() + " has fallen permanently in battle!", NamedTextColor.DARK_RED));
                    }
                }
            }
            return;
        }

        if (event.getEntity() instanceof Player player && event.getFinalDamage() >= player.getHealth()) {
            for (Entity e : player.getNearbyEntities(15, 15, 15)) {
                if (e instanceof Wolf wolf && wolf.isTamed() && player.equals(wolf.getOwner())) {
                    PersistentDataContainer pdc = wolf.getPersistentDataContainer();
                    if ("SHAMAN".equalsIgnoreCase(pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, ""))
                            && pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1) >= 20
                            && !pdc.getOrDefault(KEY_IS_GHOST, PersistentDataType.BOOLEAN, false)) {

                        event.setCancelled(true);

                        AttributeInstance pMaxHp = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                        player.setHealth(pMaxHp != null ? pMaxHp.getValue() * 0.5 : 10.0);

                        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 2));
                        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 2));
                        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
                        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
                        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation(), 100, 0.5, 1, 0.5, 0.1);

                        wolf.setHealth(1.0);
                        player.sendMessage(Component.text("✨ Your Spirit Shaman sacrificed its energy to save your life!", NamedTextColor.GREEN));
                        break;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onCombat(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Wolf wolf) {
            PersistentDataContainer pdc = wolf.getPersistentDataContainer();
            if (pdc.getOrDefault(KEY_IS_GHOST, PersistentDataType.BOOLEAN, false)) {
                event.setCancelled(true);
                return;
            }

            String archetype = pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, "UNAWAKENED");
            int lvl = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1);

            ItemStack armor = wolf.getEquipment().getItem(EquipmentSlot.BODY);
            if (armor.getType() == Material.WOLF_ARMOR) {
                if (armor.getItemMeta().getPersistentDataContainer().has(KEY_GEM_LIFESTEAL, PersistentDataType.BOOLEAN)) {
                    double heal = event.getDamage() * 0.05;
                    AttributeInstance wMaxHp = wolf.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    double wCap = wMaxHp != null ? wMaxHp.getValue() : 40.0;
                    wolf.setHealth(Math.min(wCap, wolf.getHealth() + heal));
                }
            }

            if ("SHADOWFANG".equalsIgnoreCase(archetype) && event.getEntity() instanceof LivingEntity victim) {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
                victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 10);

                if (lvl >= 20) {
                    Location behind = victim.getLocation().subtract(victim.getLocation().getDirection().normalize().multiply(1.5));
                    behind.setYaw(wolf.getLocation().getYaw());
                    wolf.teleport(behind);
                    wolf.getWorld().spawnParticle(Particle.LARGE_SMOKE, wolf.getLocation(), 15, 0.2, 0.2, 0.2, 0.05);

                    AttributeInstance vMaxHp = victim.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    double maxVictimHp = vMaxHp != null ? vMaxHp.getValue() : 20.0;
                    double execThreshold = getConfig().getDouble("abilities.shadowfang-execute-threshold", 0.15);
                    if (victim.getHealth() <= maxVictimHp * execThreshold) {
                        event.setDamage(victim.getHealth() + 100);
                        victim.getWorld().spawnParticle(Particle.SOUL, victim.getLocation(), 20, 0.5, 0.5, 0.5, 0.1);
                    }
                }
            }

            if (wolf.getOwner() instanceof Player owner) {
                int livingPack = getLivingPackCount(owner);
                if (livingPack > 1) {
                    double bonus = getConfig().getDouble("abilities.pack-tactics-damage-bonus-per-wolf", 0.75);
                    event.setDamage(event.getDamage() + (livingPack * bonus));
                }
            }
        }
    }

    @EventHandler
    public void onKill(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Player killerPlayer = victim.getKiller();
        Wolf killerWolf = null;

        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent dmgEvent) {
            if (dmgEvent.getDamager() instanceof Wolf w) killerWolf = w;
        }

        Player packOwner = killerPlayer != null ? killerPlayer : (killerWolf != null && killerWolf.getOwner() instanceof Player p ? p : null);

        if (packOwner != null) {
            AttributeInstance hpAttr = victim.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            double maxHp = hpAttr != null ? hpAttr.getValue() : 20.0;
            int xpReward = Math.max(5, (int) (maxHp * 1.5));

            if (killerWolf != null) {
                PersistentDataContainer pdc = killerWolf.getPersistentDataContainer();
                int lvl = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1);

                if (pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false) && pdc.getOrDefault(KEY_IS_BERSERK, PersistentDataType.BOOLEAN, false) && lvl >= getConfig().getInt("levels.alpha-max-level", 20)) {
                    double healPercent = getConfig().getDouble("abilities.devour-heal-percentage", 0.30);
                    double heal = maxHp * healPercent;
                    AttributeInstance alphaMaxHp = killerWolf.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    double cap = alphaMaxHp != null ? alphaMaxHp.getValue() : 40.0;
                    killerWolf.setHealth(Math.min(cap, killerWolf.getHealth() + heal));
                    killerWolf.getWorld().playSound(killerWolf.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 1f);
                    killerWolf.getWorld().spawnParticle(Particle.HEART, killerWolf.getLocation().add(0, 1, 0), 5);
                }
            }

            for (Entity e : packOwner.getNearbyEntities(32, 32, 32)) {
                if (e instanceof Wolf packWolf && packWolf.isTamed() && packOwner.equals(packWolf.getOwner())) {
                    addXP(packWolf, xpReward);
                }
            }
        }
    }

    @EventHandler
    public void onOreMine(BlockBreakEvent event) {
        int exp = event.getExpToDrop();
        if (exp > 0) {
            Player player = event.getPlayer();
            for (Entity e : player.getNearbyEntities(12, 12, 12)) {
                if (e instanceof Wolf packWolf && packWolf.isTamed() && player.equals(packWolf.getOwner())) {
                    addXP(packWolf, exp * 2);
                }
            }
        }
    }

    // =========================================================================
    // 4. INTERACTION & EMPTY HAND MOUNTING
    // =========================================================================
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item != null && item.getType() == Material.BONE && player.isSneaking() && event.getAction().isRightClick()) {
            event.setCancelled(true);
            boolean found = false;

            for (World w : Bukkit.getWorlds()) {
                for (Wolf wolf : w.getEntitiesByClass(Wolf.class)) {
                    if (wolf.isTamed() && player.equals(wolf.getOwner())) {
                        PersistentDataContainer pdc = wolf.getPersistentDataContainer();
                        if (pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false)) {
                            if (pdc.getOrDefault(KEY_IS_GHOST, PersistentDataType.BOOLEAN, false)) {
                                reviveAlpha(wolf, player);
                            } else {
                                wolf.teleport(player.getLocation());
                                player.sendMessage(Component.text("✨ Your Alpha rushed to your side!", NamedTextColor.GREEN));
                            }
                            found = true;
                        } else if (!pdc.getOrDefault(KEY_IS_GHOST, PersistentDataType.BOOLEAN, false)) {
                            wolf.teleport(player.getLocation());
                        }
                    }
                }
            }
            if (!found) player.sendMessage(Component.text("⚠ No active wolves found bound to your soul.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Wolf wolf)) return;

        Player player = event.getPlayer();
        if (!wolf.isTamed() || !player.equals(wolf.getOwner())) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        PersistentDataContainer pdc = wolf.getPersistentDataContainer();
        int lvl = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1);

        if (hand.getType().isAir() && !player.isSneaking()) {
            if (pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false) && lvl >= 20) {
                event.setCancelled(true);
                wolf.addPassenger(player);
                player.sendMessage(Component.text("♞ Alpha Mounted! Look forward to run, look down to stop.", NamedTextColor.LIGHT_PURPLE));
                return;
            }
        }

        if (hand.getType() == Material.STICK) {
            event.setCancelled(true);
            String archetype = pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, "UNAWAKENED");

            if ("UNAWAKENED".equals(archetype) && lvl >= 10) openArchetypeSelector(player, wolf);
            else openWolfMenu(player, wolf);
            return;
        }

        if (hand.getType() == Material.BONE && !player.isSneaking()) {
            event.setCancelled(true);
            boolean berserk = !pdc.getOrDefault(KEY_IS_BERSERK, PersistentDataType.BOOLEAN, false);
            pdc.set(KEY_IS_BERSERK, PersistentDataType.BOOLEAN, berserk);
            wolf.setAngry(berserk);

            if (berserk) {
                player.sendMessage(Component.text("⚔ " + wolf.getName() + " has entered BERSERK MODE!", NamedTextColor.DARK_RED));
                wolf.getWorld().playSound(wolf.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.0f, 0.7f);
            } else {
                player.sendMessage(Component.text("☮ " + wolf.getName() + " has calmed down.", NamedTextColor.GREEN));
                wolf.setTarget(null);
            }
        }
    }

    // =========================================================================
    // 5. IN-PLACE GUI UPDATES & STATUS CARD
    // =========================================================================

    private boolean hasArchetype(Player player, String archetype) {
        for (World w : Bukkit.getWorlds()) {
            for (Wolf wolf : w.getEntitiesByClass(Wolf.class)) {
                if (wolf.isTamed() && player.equals(wolf.getOwner())) {
                    if (archetype.equalsIgnoreCase(wolf.getPersistentDataContainer().getOrDefault(KEY_CLASS, PersistentDataType.STRING, ""))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void openPackMenu(Player player) {
        Inventory inv;
        boolean isNew = false;

        if (openPackMenus.contains(player.getUniqueId()) && player.getOpenInventory().getTopInventory().getSize() == 54) {
            inv = player.getOpenInventory().getTopInventory();
            inv.clear();
        } else {
            inv = Bukkit.createInventory(null, 54, Component.text("Your Wolf Pack", NamedTextColor.DARK_GREEN));
            isNew = true;
        }

        int slot = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Wolf wolf : w.getEntitiesByClass(Wolf.class)) {
                if (wolf.isTamed() && player.equals(wolf.getOwner())) {
                    PersistentDataContainer pdc = wolf.getPersistentDataContainer();
                    if (pdc.getOrDefault(KEY_IS_RPG, PersistentDataType.BOOLEAN, false)) {
                        boolean isAlpha = pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false);
                        String archetype = pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, "UNAWAKENED");
                        int lvl = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1);
                        int pts = pdc.getOrDefault(KEY_SKILL_POINTS, PersistentDataType.INTEGER, 0);

                        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                        ItemMeta meta = head.getItemMeta();
                        meta.displayName(Component.text(wolf.getName(), isAlpha ? NamedTextColor.GOLD : NamedTextColor.AQUA, TextDecoration.BOLD));

                        List<Component> lore = new ArrayList<>();
                        if (isAlpha) lore.add(Component.text("⭐ The Alpha", NamedTextColor.YELLOW));
                        else if ("UNAWAKENED".equals(archetype)) lore.add(Component.text("Rank: " + getWolfRank(lvl, archetype), NamedTextColor.GRAY));
                        else lore.add(Component.text("Class: " + archetype, NamedTextColor.GRAY));

                        lore.add(Component.text("Level: " + lvl, NamedTextColor.GREEN));
                        lore.add(Component.text("Unspent Points: " + pts, NamedTextColor.LIGHT_PURPLE));
                        lore.add(Component.text(""));
                        lore.add(Component.text("Click to Manage", NamedTextColor.YELLOW));

                        meta.lore(lore);
                        meta.getPersistentDataContainer().set(KEY_GUI_WOLF_UUID, PersistentDataType.STRING, wolf.getUniqueId().toString());
                        head.setItemMeta(meta);

                        inv.setItem(slot++, head);
                        if (slot >= 54) break;
                    }
                }
            }
        }

        if (isNew) {
            openPackMenus.add(player.getUniqueId());
            player.openInventory(inv);
        } else {
            player.updateInventory();
        }
    }

    private void openWolfMenu(Player player, Wolf wolf) {
        PersistentDataContainer pdc = wolf.getPersistentDataContainer();
        boolean isAlpha = pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false);
        int lvl = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1);
        int xp = pdc.getOrDefault(KEY_XP, PersistentDataType.INTEGER, 0);
        int pts = pdc.getOrDefault(KEY_SKILL_POINTS, PersistentDataType.INTEGER, 0);
        String archetype = pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, "UNAWAKENED");
        int maxLvl = getMaxLevel(isAlpha, archetype);
        String displayRank = getWolfRank(lvl, archetype);
        boolean isSitting = wolf.isSitting();
        boolean isBerserk = pdc.getOrDefault(KEY_IS_BERSERK, PersistentDataType.BOOLEAN, false);

        Inventory inv;
        boolean isNew = false;

        if (openMenus.containsKey(player.getUniqueId()) && player.getOpenInventory().getTopInventory().getSize() == 27) {
            inv = player.getOpenInventory().getTopInventory();
            inv.clear();
        } else {
            inv = Bukkit.createInventory(null, 27, Component.text("Wolf Management: " + wolf.getName(), NamedTextColor.DARK_GRAY));
            isNew = true;
        }

        ItemStack info = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(Component.text(wolf.getName(), NamedTextColor.GOLD, TextDecoration.BOLD));

        List<Component> loreLines = new ArrayList<>();
        if ("UNAWAKENED".equals(archetype)) {
            loreLines.add(Component.text("Rank: ", NamedTextColor.GRAY).append(Component.text(displayRank, NamedTextColor.AQUA)));
            if (lvl < 10) loreLines.add(Component.text("Goal: Reach Beta (Lvl 10) to Ascend", NamedTextColor.YELLOW));
        } else {
            loreLines.add(Component.text("Archetype: ", NamedTextColor.GRAY).append(Component.text(archetype, NamedTextColor.AQUA)));
        }

        loreLines.add(Component.text("Level: ", NamedTextColor.GRAY).append(Component.text(lvl + (lvl >= maxLvl ? " (MAX)" : ""), NamedTextColor.GREEN)));
        loreLines.add(Component.text("EXP: ", NamedTextColor.GRAY).append(Component.text(xp + " / " + (lvl >= maxLvl ? "MAX" : (getConfig().getInt("levels.xp-base-requirement", 50) + (lvl * getConfig().getInt("levels.xp-per-level-multiplier", 25)))), NamedTextColor.YELLOW)));
        loreLines.add(Component.text("Available Points: ", NamedTextColor.GRAY).append(Component.text(pts, NamedTextColor.LIGHT_PURPLE)));
        loreLines.add(Component.text("--------------------", NamedTextColor.DARK_GRAY));
        loreLines.add(Component.text("Active Abilities:", NamedTextColor.GOLD));

        if (isAlpha) {
            loreLines.add(Component.text("• Soul Bound: Immortal (Ghost State)", NamedTextColor.GRAY));
            loreLines.add(Component.text("• Canopy Satchel: Storage grows with pack", NamedTextColor.GRAY));
            loreLines.add(Component.text("• Symbiosis: Gains SP when pack levels up", NamedTextColor.GRAY));
            if (lvl >= 20) {
                loreLines.add(Component.text("• Mount: Right-click with empty hand to ride", NamedTextColor.LIGHT_PURPLE));
                loreLines.add(Component.text("• Devour: Kills heal 30% Max HP", NamedTextColor.LIGHT_PURPLE));
            }
        } else if ("SHADOWFANG".equalsIgnoreCase(archetype)) {
            loreLines.add(Component.text("• Shadow Strike: Wither bleed on hit", NamedTextColor.GRAY));
            loreLines.add(Component.text("• Nocturnal: Infinite shared night vision", NamedTextColor.GRAY));
            if (lvl >= 20) {
                loreLines.add(Component.text("• Shadow Step: Teleport execute under 15% HP", NamedTextColor.LIGHT_PURPLE));
            }
        } else if ("SHAMAN".equalsIgnoreCase(archetype)) {
            loreLines.add(Component.text("• Healing Pulse: Restores HP to owner & pack", NamedTextColor.GRAY));
            if (lvl >= 20) {
                loreLines.add(Component.text("• Spirit Ward: Totem sacrifice on fatal damage", NamedTextColor.LIGHT_PURPLE));
            }
        } else if ("BLOODHOUND".equalsIgnoreCase(archetype)) {
            loreLines.add(Component.text("• Auto-Loot: Vacuums ground items directly", NamedTextColor.GRAY));
            if (lvl >= 20) {
                loreLines.add(Component.text("• Predator Mark: Glowing aura on monsters", NamedTextColor.LIGHT_PURPLE));
                loreLines.add(Component.text("• Smart Ore Sonar: Leads you to highest value ores", NamedTextColor.LIGHT_PURPLE));
                loreLines.add(Component.text("• Custom Scent: Tracks any block given in GUI", NamedTextColor.LIGHT_PURPLE));
            }
        }

        infoMeta.lore(loreLines);
        info.setItemMeta(infoMeta);
        inv.setItem(4, info);

        if ("BLOODHOUND".equalsIgnoreCase(archetype) && lvl >= 20) {
            String scentName = pdc.getOrDefault(KEY_SCENT_TARGET, PersistentDataType.STRING, "");
            Material scentMat = scentName.isEmpty() ? null : Material.getMaterial(scentName);
            if (scentMat != null && scentMat.isBlock()) {
                inv.setItem(2, createGuiItem(scentMat, "§6Target Scent: " + scentMat.name().replace("_", " "), "§eShift-Click to retrieve this block"));
            } else {
                inv.setItem(2, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, "§7Empty Scent Slot", "§eShift-Click a Block from your", "§einventory to track its scent"));
            }
        }

        inv.setItem(8, createGuiItem(Material.NAME_TAG, "§eRename Wolf", "§7Click to rename this wolf via chat"));
        inv.setItem(11, createGuiItem(Material.RED_DYE, "§c+1 Vitality (Health)", "§7Current: §f" + pdc.getOrDefault(KEY_VITALITY, PersistentDataType.INTEGER, 0), "§eClick to allocate 1 Skill Point"));
        inv.setItem(13, createGuiItem(Material.IRON_SWORD, "§4+1 Ferocity (Attack)", "§7Current: §f" + pdc.getOrDefault(KEY_FEROCITY, PersistentDataType.INTEGER, 0), "§eClick to allocate 1 Skill Point"));
        inv.setItem(15, createGuiItem(Material.SUGAR, "§b+1 Swiftness (Speed)", "§7Current: §f" + pdc.getOrDefault(KEY_SWIFTNESS, PersistentDataType.INTEGER, 0), "§eClick to allocate 1 Skill Point"));
        inv.setItem(19, createGuiItem(isSitting ? Material.STRING : Material.LEAD, "§bBehavior: " + (isSitting ? "Sitting" : "Following"), "§7Click to toggle Sit/Stand"));

        if (isAlpha) {
            int livingPack = getLivingPackCount(player);
            int baseSlots = getConfig().getInt("satchel.base-slots", 9);
            int perWolf = getConfig().getInt("satchel.slots-gained-per-pack-member", 3);
            int maxSlots = getConfig().getInt("satchel.max-total-slots", 54);
            int activeSlots = Math.clamp(baseSlots + (livingPack * perWolf), baseSlots, maxSlots);
            inv.setItem(21, createGuiItem(Material.CHEST, "§6Canine Satchel", "§7Pack Size: §f" + livingPack + " Wolves", "§7Capacity: §a" + activeSlots + " / " + maxSlots + " Slots", "§eClick to open storage"));
        }

        inv.setItem(23, createGuiItem(isBerserk ? Material.BLAZE_POWDER : Material.IRON_CHESTPLATE, "§cCombat Mode: " + (isBerserk ? "Berserk (Hunting)" : "Protect (Defending)"), "§7Click to toggle aggressive hunt mode"));

        if (isNew) {
            openMenus.put(player.getUniqueId(), wolf.getUniqueId());
            player.openInventory(inv);
        } else {
            player.updateInventory();
        }
    }

    private void openArchetypeSelector(Player player, Wolf wolf) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Awaken Archetype", NamedTextColor.DARK_PURPLE));
        boolean hasShadowfang = hasArchetype(player, "SHADOWFANG");
        boolean hasShaman = hasArchetype(player, "SHAMAN");
        boolean hasBloodhound = hasArchetype(player, "BLOODHOUND");

        inv.setItem(11, createGuiItem(Material.IRON_SWORD, "§bThe Shadowfang", "§7Role: Fast Assassin", "§c+ Bleed Debuff & Night Vision", "§5+ Max Lvl 20: Shadow Step Execute", "", hasShadowfang ? "§c⚠ ALREADY IN PACK (LOCKED)" : "§a✅ AVAILABLE TO AWAKEN"));
        inv.setItem(13, createGuiItem(Material.TOTEM_OF_UNDYING, "§aSpirit Shaman", "§7Role: Pack Support", "§e+ Periodic Healing Pulses", "§5+ Max Lvl 20: Player Resurrection", "", hasShaman ? "§c⚠ ALREADY IN PACK (LOCKED)" : "§a✅ AVAILABLE TO AWAKEN"));
        inv.setItem(15, createGuiItem(Material.RECOVERY_COMPASS, "§eBloodhound", "§7Role: Scout & Utility", "§a+ Auto-Loot Drop Vacuum", "§5+ Max Lvl 20: Smart Ore Sonar", "", hasBloodhound ? "§c⚠ ALREADY IN PACK (LOCKED)" : "§a✅ AVAILABLE TO AWAKEN"));

        openMenus.put(player.getUniqueId(), wolf.getUniqueId());
        player.openInventory(inv);
    }

    private void openSatchel(Player player, Wolf alpha) {
        int livingPack = getLivingPackCount(player);
        int baseSlots = getConfig().getInt("satchel.base-slots", 9);
        int perWolf = getConfig().getInt("satchel.slots-gained-per-pack-member", 3);
        int maxSlots = getConfig().getInt("satchel.max-total-slots", 54);
        int activeSlots = Math.clamp(baseSlots + (livingPack * perWolf), baseSlots, maxSlots);

        Inventory satchel = Bukkit.createInventory(null, 54, Component.text("Canine Satchel (" + activeSlots + " Slots)", NamedTextColor.DARK_BLUE));

        PersistentDataContainer pdc = alpha.getPersistentDataContainer();
        byte[] data = pdc.getOrDefault(KEY_SATCHEL, PersistentDataType.BYTE_ARRAY, new byte[0]);
        if (data.length > 0) {
            ItemStack[] stored = deserializeInventory(data);
            for (int i = 0; i < Math.min(stored.length, 54); i++) {
                if (stored[i] != null) satchel.setItem(i, stored[i]);
            }
        }

        ItemStack barrier = createGuiItem(Material.BARRIER, "§cLocked Slot", "§7Tame more wolves to re-unlock this slot!");
        for (int i = activeSlots; i < 54; i++) {
            satchel.setItem(i, barrier);
        }

        openSatchels.put(player.getUniqueId(), alpha.getUniqueId());
        player.openInventory(satchel);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // 1. Pack Overview GUI Lock
        if (openPackMenus.contains(player.getUniqueId())) {
            event.setCancelled(true);
            if (event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getTopInventory())) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && clicked.hasItemMeta()) {
                    String uuidStr = clicked.getItemMeta().getPersistentDataContainer().get(KEY_GUI_WOLF_UUID, PersistentDataType.STRING);
                    if (uuidStr != null) {
                        Entity entity = Bukkit.getEntity(UUID.fromString(uuidStr));
                        if (entity instanceof Wolf wolf) {
                            Bukkit.getScheduler().runTask(this, () -> {
                                player.closeInventory();
                                PersistentDataContainer pdc = wolf.getPersistentDataContainer();
                                if ("UNAWAKENED".equals(pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, "")) && pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1) >= 10) {
                                    openArchetypeSelector(player, wolf);
                                } else {
                                    openWolfMenu(player, wolf);
                                }
                            });
                        }
                    }
                }
            }
            return;
        }

        // 2. Wolf Management / Archetype GUI Strict Lock
        UUID wolfUUID = openMenus.get(player.getUniqueId());
        if (wolfUUID != null) {
            event.setCancelled(true);

            Entity entity = Bukkit.getEntity(wolfUUID);
            if (!(entity instanceof Wolf wolf)) return;
            PersistentDataContainer pdc = wolf.getPersistentDataContainer();
            String archetype = pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, "UNAWAKENED");
            int lvl = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1);

            // Give Scent (Shift-Click from bottom inventory)
            if (event.isShiftClick() && event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem != null && clickedItem.getType().isBlock()) {
                    if ("BLOODHOUND".equalsIgnoreCase(archetype) && lvl >= 20) {
                        if (pdc.getOrDefault(KEY_SCENT_TARGET, PersistentDataType.STRING, "").isEmpty()) {
                            pdc.set(KEY_SCENT_TARGET, PersistentDataType.STRING, clickedItem.getType().name());
                            clickedItem.setAmount(clickedItem.getAmount() - 1);
                            player.playSound(player.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 1f, 1f);
                            Bukkit.getScheduler().runTask(this, () -> openWolfMenu(player, wolf));
                            return;
                        }
                    }
                }
            }

            if (event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getTopInventory())) {

                if ("UNAWAKENED".equals(archetype) && lvl >= 10) {
                    if (event.getSlot() == 11) Bukkit.getScheduler().runTask(this, () -> attemptAssignClass(wolf, "SHADOWFANG", player));
                    else if (event.getSlot() == 13) Bukkit.getScheduler().runTask(this, () -> attemptAssignClass(wolf, "SHAMAN", player));
                    else if (event.getSlot() == 15) Bukkit.getScheduler().runTask(this, () -> attemptAssignClass(wolf, "BLOODHOUND", player));
                }
                else {
                    // Remove Scent (Shift-Click the scent slot itself)
                    if (event.getSlot() == 2 && "BLOODHOUND".equalsIgnoreCase(archetype) && lvl >= 20) {
                        String scentName = pdc.getOrDefault(KEY_SCENT_TARGET, PersistentDataType.STRING, "");

                        if (event.isShiftClick() && !scentName.isEmpty()) {
                            Material oldScent = Material.getMaterial(scentName);
                            if (oldScent != null) {
                                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(oldScent));
                                if (!leftover.isEmpty()) {
                                    player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
                                }
                            }
                            pdc.remove(KEY_SCENT_TARGET);

                            activeScentTrails.remove(wolf.getUniqueId());
                            wolf.setSitting(false);
                            wolf.removePotionEffect(PotionEffectType.GLOWING);

                            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1f);
                            Bukkit.getScheduler().runTask(this, () -> openWolfMenu(player, wolf));
                        }
                        return;
                    }

                    if (event.getSlot() == 8) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            player.closeInventory();
                            pendingRenames.put(player.getUniqueId(), wolf.getUniqueId());
                            player.sendMessage(Component.text("📝 Type your wolf's new name in chat, or type 'cancel'.", NamedTextColor.YELLOW));
                        });
                        return;
                    }

                    if (event.getSlot() == 19) {
                        wolf.setSitting(!wolf.isSitting());
                        Bukkit.getScheduler().runTask(this, () -> openWolfMenu(player, wolf));
                        return;
                    }

                    if (event.getSlot() == 23) {
                        boolean b = !pdc.getOrDefault(KEY_IS_BERSERK, PersistentDataType.BOOLEAN, false);
                        pdc.set(KEY_IS_BERSERK, PersistentDataType.BOOLEAN, b);
                        wolf.setAngry(b);
                        Bukkit.getScheduler().runTask(this, () -> openWolfMenu(player, wolf));
                        return;
                    }

                    int points = pdc.getOrDefault(KEY_SKILL_POINTS, PersistentDataType.INTEGER, 0);
                    if (points > 0) {
                        if (event.getSlot() == 11) {
                            pdc.set(KEY_VITALITY, PersistentDataType.INTEGER, pdc.getOrDefault(KEY_VITALITY, PersistentDataType.INTEGER, 0) + 1);
                            pdc.set(KEY_SKILL_POINTS, PersistentDataType.INTEGER, points - 1);
                            applyStats(wolf, lvl);
                            Bukkit.getScheduler().runTask(this, () -> openWolfMenu(player, wolf));
                        } else if (event.getSlot() == 13) {
                            pdc.set(KEY_FEROCITY, PersistentDataType.INTEGER, pdc.getOrDefault(KEY_FEROCITY, PersistentDataType.INTEGER, 0) + 1);
                            pdc.set(KEY_SKILL_POINTS, PersistentDataType.INTEGER, points - 1);
                            applyStats(wolf, lvl);
                            Bukkit.getScheduler().runTask(this, () -> openWolfMenu(player, wolf));
                        } else if (event.getSlot() == 15) {
                            pdc.set(KEY_SWIFTNESS, PersistentDataType.INTEGER, pdc.getOrDefault(KEY_SWIFTNESS, PersistentDataType.INTEGER, 0) + 1);
                            pdc.set(KEY_SKILL_POINTS, PersistentDataType.INTEGER, points - 1);
                            applyStats(wolf, lvl);
                            Bukkit.getScheduler().runTask(this, () -> openWolfMenu(player, wolf));
                        }
                    }

                    if (event.getSlot() == 21 && pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false)) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            player.closeInventory();
                            openSatchel(player, wolf);
                        });
                    }
                }
            }
            return;
        }

        // 3. Canine Satchel Lock
        UUID satchelAlphaUUID = openSatchels.get(player.getUniqueId());
        if (satchelAlphaUUID != null && event.getClickedInventory() != null) {
            if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.BARRIER) {
                event.setCancelled(true);
                player.sendMessage(Component.text("⚠ This slot is locked until you tame more wolves!", NamedTextColor.RED));
            }
        }
    }

    private void attemptAssignClass(Wolf wolf, String className, Player player) {
        if (hasArchetype(player, className)) {
            player.sendMessage(Component.text("⚠ You already have an active " + className + " in your pack! If it dies, you can replace it.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        } else {
            PersistentDataContainer pdc = wolf.getPersistentDataContainer();
            pdc.set(KEY_CLASS, PersistentDataType.STRING, className);
            applyStats(wolf, pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 10));
            player.closeInventory();
            player.sendMessage(Component.text("⚡ " + wolf.getName() + " has ascended as a " + className + "!", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.7f, 1.2f);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        openMenus.remove(player.getUniqueId());
        openPackMenus.remove(player.getUniqueId());

        UUID satchelAlpha = openSatchels.remove(player.getUniqueId());
        if (satchelAlpha != null) {
            Entity entity = Bukkit.getEntity(satchelAlpha);
            if (entity instanceof Wolf alpha) {
                Inventory inv = event.getInventory();
                ItemStack[] items = new ItemStack[54];
                for (int i = 0; i < 54; i++) {
                    ItemStack it = inv.getItem(i);
                    if (it != null && it.getType() != Material.BARRIER) {
                        items[i] = it;
                    }
                }
                alpha.getPersistentDataContainer().set(KEY_SATCHEL, PersistentDataType.BYTE_ARRAY, serializeInventory(items));
            }
        }
    }

    // =========================================================================
    // 6. BACKGROUND AI, SEAMLESS ORE SONAR, STEERING, & PASSIVES
    // =========================================================================

    private int getOrePriority(Material mat) {
        if (mat == Material.ANCIENT_DEBRIS) return 1;
        if (mat == Material.DIAMOND_ORE || mat == Material.DEEPSLATE_DIAMOND_ORE) return 2;
        if (mat == Material.EMERALD_ORE || mat == Material.DEEPSLATE_EMERALD_ORE) return 3;
        if (mat == Material.GOLD_ORE || mat == Material.DEEPSLATE_GOLD_ORE) return 4;
        return -1;
    }

    private void handleRidingPhysics() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getVehicle() instanceof Wolf wolf) {
                PersistentDataContainer pdc = wolf.getPersistentDataContainer();
                if (pdc.getOrDefault(KEY_IS_STARTER, PersistentDataType.BOOLEAN, false)) {

                    wolf.setRotation(player.getLocation().getYaw(), wolf.getLocation().getPitch());

                    if (player.getLocation().getPitch() < 40.0) {
                        Vector direction = player.getLocation().getDirection().setY(0).normalize();
                        AttributeInstance wSpd = wolf.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
                        double speed = (wSpd != null ? wSpd.getValue() : 0.3) * 1.5;
                        wolf.setVelocity(direction.multiply(speed).setY(wolf.getVelocity().getY()));
                    }
                }
            }
        }
    }

    private void handleWolfTick() {
        for (World world : Bukkit.getWorlds()) {
            for (Wolf wolf : world.getEntitiesByClass(Wolf.class)) {
                if (!wolf.isTamed() || !(wolf.getOwner() instanceof Player owner)) continue;

                PersistentDataContainer pdc = wolf.getPersistentDataContainer();
                if (!pdc.getOrDefault(KEY_IS_RPG, PersistentDataType.BOOLEAN, false)) continue;
                if (pdc.getOrDefault(KEY_IS_GHOST, PersistentDataType.BOOLEAN, false)) {
                    world.spawnParticle(Particle.SOUL, wolf.getLocation().add(0, 0.5, 0), 2, 0.2, 0.2, 0.2, 0.0);
                    continue;
                }

                if (pdc.getOrDefault(KEY_IS_BERSERK, PersistentDataType.BOOLEAN, false)) {
                    world.spawnParticle(Particle.ANGRY_VILLAGER, wolf.getLocation().add(0, 0.8, 0), 1, 0.2, 0.2, 0.2, 0.0);
                    if (wolf.getTarget() == null || !wolf.getTarget().isValid()) {
                        for (Entity nearby : wolf.getNearbyEntities(16, 8, 16)) {
                            if (nearby instanceof Monster target && !(target instanceof Creeper)) {
                                wolf.setTarget(target);
                                break;
                            }
                        }
                    }
                }

                int lvl = pdc.getOrDefault(KEY_LEVEL, PersistentDataType.INTEGER, 1);
                String arch = pdc.getOrDefault(KEY_CLASS, PersistentDataType.STRING, "UNAWAKENED");

                if ("SHADOWFANG".equalsIgnoreCase(arch)) {
                    wolf.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 600, 0, false, false, false));
                    if (wolf.getLocation().distanceSquared(owner.getLocation()) <= 225) {
                        owner.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 600, 0, false, false, true));
                    }
                }

                if ("SHAMAN".equalsIgnoreCase(arch)) {
                    int interval = getConfig().getInt("abilities.shaman-heal-interval-seconds", 4);
                    if (world.getFullTime() % (interval * 20L) < 20L) {
                        AttributeInstance maxHpAttr = owner.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                        double ownerMaxHp = maxHpAttr != null ? maxHpAttr.getValue() : 20.0;
                        owner.setHealth(Math.min(ownerMaxHp, owner.getHealth() + 2.0));

                        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                        owner.getWorld().spawnParticle(Particle.HEART, owner.getLocation().add(0, 1.5, 0), 5, 0.5, 0.5, 0.5, 0.0);

                        for (Entity nearby : wolf.getNearbyEntities(12, 6, 12)) {
                            if (nearby instanceof Wolf packWolf && packWolf.isTamed() && owner.equals(packWolf.getOwner())) {
                                AttributeInstance wMax = packWolf.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                                double wCap = wMax != null ? wMax.getValue() : 40.0;
                                packWolf.setHealth(Math.min(wCap, packWolf.getHealth() + 3.0));
                                packWolf.getWorld().spawnParticle(Particle.HEART, packWolf.getLocation().add(0, 0.8, 0), 2, 0.2, 0.2, 0.2, 0.0);
                            }
                        }
                    }
                }

                if ("BLOODHOUND".equalsIgnoreCase(arch)) {
                    double lootRadius = getConfig().getDouble("abilities.bloodhound-loot-radius-blocks", 8.0);
                    for (Entity e : wolf.getNearbyEntities(lootRadius, lootRadius / 2, lootRadius)) {
                        if (e instanceof Item item && item.isValid() && !item.isDead()) {
                            ItemStack stack = item.getItemStack();
                            HashMap<Integer, ItemStack> leftover = owner.getInventory().addItem(stack);
                            if (leftover.isEmpty()) {
                                item.remove();
                                owner.playSound(owner.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2f, 1.5f);
                                wolf.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, wolf.getLocation().add(0, 0.5, 0), 3, 0.2, 0.2, 0.2, 0.0);
                            } else {
                                item.setItemStack(leftover.values().iterator().next());
                            }
                        }
                    }

                    if (lvl >= 20) {
                        for (Entity e : wolf.getNearbyEntities(32, 32, 32)) {
                            if (e instanceof Monster monster) {
                                monster.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false, false));
                            }
                        }

                        Location activeTarget = activeScentTrails.get(wolf.getUniqueId());
                        String scentName = pdc.getOrDefault(KEY_SCENT_TARGET, PersistentDataType.STRING, "");
                        Material customTarget = scentName.isEmpty() ? null : Material.getMaterial(scentName);

                        boolean forceRescan = false;

                        // Phase 1: Validate current target block
                        if (activeTarget != null) {
                            Block b = activeTarget.getBlock();
                            boolean isValid = false;

                            if (customTarget != null && b.getType() == customTarget) {
                                isValid = true;
                            } else if (customTarget == null && getOrePriority(b.getType()) != -1) {
                                isValid = true;
                            }

                            if (owner.getLocation().distance(activeTarget) > 20.0) {
                                // Player abandoned the search. Drop the trail.
                                activeScentTrails.remove(wolf.getUniqueId());
                                activeTarget = null;
                                wolf.setSitting(false);
                                wolf.removePotionEffect(PotionEffectType.GLOWING);
                                owner.sendActionBar(Component.text("🧭 Scent faded... you moved too far away.", NamedTextColor.GRAY, TextDecoration.ITALIC));
                            } else if (!isValid) {
                                // THE FIX: The block was mined! Immediately drop it and force a silent rescan to snap to the next ore in the vein.
                                activeScentTrails.remove(wolf.getUniqueId());
                                activeTarget = null;
                                forceRescan = true;
                            }
                        }

                        // Phase 2: Scanning (Either on normal interval, or instantly if a block was just mined)
                        if (activeTarget == null && (forceRescan || (world.getFullTime() % 80L < 20L && !wolf.isSitting()))) {
                            int radius = 12;
                            Location wl = wolf.getLocation();
                            Block bestOre = null;
                            double bestDist = Double.MAX_VALUE;
                            int bestPriority = Integer.MAX_VALUE;

                            for (int x = -radius; x <= radius; x++) {
                                for (int y = -radius; y <= radius; y++) {
                                    for (int z = -radius; z <= radius; z++) {
                                        Block b = wl.clone().add(x, y, z).getBlock();
                                        Material t = b.getType();

                                        int priority = -1;
                                        if (customTarget != null && t == customTarget) {
                                            priority = 0;
                                        } else {
                                            priority = getOrePriority(t);
                                        }

                                        if (priority != -1) {
                                            double dist = wl.distance(b.getLocation());
                                            if (priority < bestPriority || (priority == bestPriority && dist < bestDist)) {
                                                bestPriority = priority;
                                                bestDist = dist;
                                                bestOre = b;
                                            }
                                        }
                                    }
                                }
                            }

                            if (bestOre != null) {
                                // New target found! Update memory.
                                activeTarget = bestOre.getLocation();
                                activeScentTrails.put(wolf.getUniqueId(), activeTarget);

                                // Only howl if this was a brand new search, not a silent vein snap
                                if (!forceRescan) {
                                    wolf.getWorld().playSound(wolf.getLocation(), Sound.ENTITY_WOLF_HOWL, 0.3f, 1.5f);
                                }
                            } else if (forceRescan) {
                                // We rescanned because a block broke, but found NOTHING left. The vein is fully mined.
                                wolf.setSitting(false);
                                wolf.removePotionEffect(PotionEffectType.GLOWING);
                                owner.sendActionBar(Component.text("🧭 Scent lost... target was removed.", NamedTextColor.GRAY, TextDecoration.ITALIC));
                            }
                        }

                        // Phase 3: Execution (Sit, Glow, and Beam)
                        if (activeTarget != null) {
                            wolf.setSitting(true);
                            wolf.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, false, false, false));

                            Block targetBlock = activeTarget.getBlock();
                            String oreName = targetBlock.getType().name().replace("_ORE", "").replace("DEEPSLATE_", "").replace("_", " ").toLowerCase();
                            if (oreName.length() > 0) oreName = oreName.substring(0, 1).toUpperCase() + oreName.substring(1);

                            String prefix = (customTarget != null && targetBlock.getType() == customTarget) ? "🎯 Tracking Target: " : "🐾 Tracking Scent: ";
                            owner.sendActionBar(Component.text(prefix + oreName + " (" + (int)owner.getLocation().distance(activeTarget) + " blocks away)", NamedTextColor.AQUA, TextDecoration.BOLD));

                            Location startLoc = wolf.getEyeLocation();
                            Location targetCenter = activeTarget.clone().add(0.5, 0.5, 0.5);
                            Vector dir = targetCenter.toVector().subtract(startLoc.toVector());
                            double dist = dir.length();

                            if (dist > 0) {
                                dir.normalize();
                                Particle.DustOptions dust = new Particle.DustOptions(Color.AQUA, 1.0f);
                                for (double i = 0; i < dist; i += 0.5) {
                                    Location point = startLoc.clone().add(dir.clone().multiply(i));
                                    wolf.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
                                }
                            }
                        }
                    }
                }

                double auraRadius = getConfig().getDouble("abilities.blessing-aura-radius-blocks", 10.0);
                if (wolf.getLocation().distanceSquared(owner.getLocation()) <= (auraRadius * auraRadius)) {
                    int alphaMax = getConfig().getInt("levels.alpha-max-level", 20);
                    int hasteAmp = lvl >= alphaMax ? 2 : (lvl >= (alphaMax / 2) ? 1 : 0);
                    owner.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 40, hasteAmp, false, false, true));

                    int livingPack = getLivingPackCount(owner);
                    if (livingPack > 1) {
                        owner.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 40, 0, false, false, true));
                        owner.setAbsorptionAmount(Math.min(10.0f, (livingPack - 1) * 2.0f));
                    }
                }
            }
        }
    }

    private int getLivingPackCount(Player player) {
        int count = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Wolf wolf : w.getEntitiesByClass(Wolf.class)) {
                if (wolf.isTamed() && player.equals(wolf.getOwner())) {
                    if (!wolf.getPersistentDataContainer().getOrDefault(KEY_IS_GHOST, PersistentDataType.BOOLEAN, false)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // =========================================================================
    // 7. COMMANDS, AUTO-COMPLETE, & SERIALIZATION
    // =========================================================================
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("pack")) {
                openPackMenu(player);
                return true;
            }

            if (!player.hasPermission("starterwolf.admin")) {
                player.sendMessage(Component.text("You do not have permission to use admin commands.", NamedTextColor.RED));
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                player.sendMessage(Component.text("✅ StarterWolf configuration reloaded successfully!", NamedTextColor.GREEN));
                return true;
            }

            if (args[0].equalsIgnoreCase("addxp") && args.length > 1) {
                try {
                    int amt = Integer.parseInt(args[1]);
                    for (Entity e : player.getNearbyEntities(16, 16, 16)) {
                        if (e instanceof Wolf w && w.isTamed() && player.equals(w.getOwner())) {
                            addXP(w, amt);
                        }
                    }
                    player.sendMessage(Component.text("Added " + amt + " XP to nearby wolves!", NamedTextColor.GREEN));
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("Invalid amount number.", NamedTextColor.RED));
                }
                return true;
            }
        }

        player.sendMessage(Component.text("Usage: /starterwolf <pack|reload|addxp <amount>>", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = Arrays.asList("pack", "reload", "addxp");
            for (String option : options) {
                if (option.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(option);
                }
            }
        }
        return completions;
    }

    private ItemStack createGuiItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s));
        meta.lore(l);
        item.setItemMeta(meta);
        return item;
    }

    private byte[] serializeInventory(ItemStack[] items) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(items.length);
            for (ItemStack item : items) {
                if (item == null || item.getType() == Material.AIR) {
                    dos.writeInt(0);
                } else {
                    byte[] bytes = item.serializeAsBytes();
                    dos.writeInt(bytes.length);
                    dos.write(bytes);
                }
            }
            dos.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private ItemStack[] deserializeInventory(byte[] data) {
        if (data == null || data.length == 0) return new ItemStack[0];
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bis);
            int length = dis.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                int byteLength = dis.readInt();
                if (byteLength == 0) {
                    items[i] = null;
                } else {
                    byte[] bytes = new byte[byteLength];
                    dis.readFully(bytes);
                    items[i] = ItemStack.deserializeBytes(bytes);
                }
            }
            dis.close();
            return items;
        } catch (Exception e) {
            return new ItemStack[0];
        }
    }
}