package com.magic.magicStick;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MagicStick extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    public enum WandMode {
        DIRT(
                "Стихия Земли",
                NamedTextColor.GOLD,
                "Стреляет блоками земли",
                "Временная стена укрытия",
                "Стойкость I (сопротивление урону)"
        ),
        WIND(
                "Стихия Воздуха",
                NamedTextColor.AQUA,
                "Шлейф ветра (отталкивание)",
                "Взлет в воздух (ветряной прыжок)",
                "Скорость I и Прыгучесть I"
        ),
        FIRE(
                "Стихия Огня",
                NamedTextColor.RED,
                "Огненный шар (взрыв и поджог)",
                "Огненный рывок (стремительный дэш)",
                "Огнестойкость (иммунитет к огню и лаве)"
        ),
        WATER(
                "Стихия Воды",
                NamedTextColor.BLUE,
                "Водная струя (пронзание и замедление)",
                "Приливная волна (исцеление и очищение)",
                "Подводное дыхание, грация дельфина и автотушение"
        );

        private final String displayName;
        private final NamedTextColor color;
        private final String rmbDescription;
        private final String lmbDescription;
        private final String passiveDescription;

        WandMode(String displayName, NamedTextColor color, String rmbDescription, String lmbDescription, String passiveDescription) {
            this.displayName = displayName;
            this.color = color;
            this.rmbDescription = rmbDescription;
            this.lmbDescription = lmbDescription;
            this.passiveDescription = passiveDescription;
        }

        public String getDisplayName() {
            return displayName;
        }

        public NamedTextColor getColor() {
            return color;
        }

        public String getRmbDescription() {
            return rmbDescription;
        }

        public String getLmbDescription() {
            return lmbDescription;
        }

        public String getPassiveDescription() {
            return passiveDescription;
        }

        public WandMode next() {
            WandMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public static WandMode fromString(String name) {
            if (name == null) {
                return DIRT;
            }
            for (WandMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name) || mode.getDisplayName().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            String lower = name.toLowerCase();
            return switch (lower) {
                case "dirt", "earth", "земля", "блок", "бросок земли", "стихия земли" -> DIRT;
                case "wind", "air", "ветер", "воздух", "порыв ветра", "стихия воздуха" -> WIND;
                case "fire", "огонь", "пламя", "огненный", "стихия огня" -> FIRE;
                case "water", "вода", "аква", "водный", "стихия воды" -> WATER;
                default -> DIRT;
            };
        }
    }

    private NamespacedKey stickKey;
    private NamespacedKey modeKey;

    // Временные блоки созданных стен земли (Блок -> Исходное состояние)
    private final Map<Block, BlockData> temporaryWallBlocks = new HashMap<>();

    // Игроки, защищенные от урона при падении (после прыжка ветра или огненного рывка)
    private final Set<UUID> fallImmunePlayers = new HashSet<>();

    // Кулдауны на спец-способности (ЛКМ)
    private final Map<UUID, Long> wallCooldowns = new HashMap<>();
    private final Map<UUID, Long> jumpCooldowns = new HashMap<>();
    private final Map<UUID, Long> fireDashCooldowns = new HashMap<>();
    private final Map<UUID, Long> waterWaveCooldowns = new HashMap<>();

    // Фоновая задача пассивных эффектов стихий
    private BukkitRunnable passiveTask;

    @Override
    public void onEnable() {
        this.stickKey = new NamespacedKey(this, "magic_dirt_stick");
        this.modeKey = new NamespacedKey(this, "wand_mode");

        // Регистрируем слушатель событий
        getServer().getPluginManager().registerEvents(this, this);

        // Регистрируем команду /getstick
        PluginCommand command = getCommand("getstick");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        // Запуск пассивных эффектов при удержании палочки в руке
        startPassiveTask();

        getLogger().info("MagicStick успешно включен со всеми 4 стихиями!");
    }

    @Override
    public void onDisable() {
        if (passiveTask != null) {
            passiveTask.cancel();
        }

        // Восстанавливаем все временные блоки стен при выключении плагина
        for (Map.Entry<Block, BlockData> entry : temporaryWallBlocks.entrySet()) {
            Block b = entry.getKey();
            if (b.getType() == Material.DIRT || b.getType() == Material.COARSE_DIRT) {
                b.setBlockData(entry.getValue());
            }
        }
        temporaryWallBlocks.clear();
        fallImmunePlayers.clear();
        wallCooldowns.clear();
        jumpCooldowns.clear();
        fireDashCooldowns.clear();
        waterWaveCooldowns.clear();

        getLogger().info("MagicStick выключен!");
    }

    /**
     * Создает предмет волшебной палочки в указанном режиме
     */
    public ItemStack createMagicStick(WandMode mode) {
        ItemStack stick = new ItemStack(Material.STICK);
        applyModeToItem(stick, mode);
        return stick;
    }

    /**
     * Обновляет метаданные, название, лор и теги предмета в соответствии с режимом
     */
    public void applyModeToItem(ItemStack stick, WandMode mode) {
        ItemMeta meta = stick.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.displayName(
                Component.text("Волшебная палочка: ", NamedTextColor.WHITE)
                        .append(Component.text(mode.getDisplayName(), mode.getColor()).decorate(TextDecoration.BOLD))
        );

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Текущая стихия: ", NamedTextColor.GRAY)
                .append(Component.text(mode.getDisplayName(), mode.getColor()).decorate(TextDecoration.BOLD)));
        lore.add(Component.empty());
        lore.add(Component.text("ПКМ: ", NamedTextColor.YELLOW)
                .append(Component.text(mode.getRmbDescription(), NamedTextColor.WHITE)));
        lore.add(Component.text("ЛКМ: ", NamedTextColor.GREEN)
                .append(Component.text(mode.getLmbDescription(), NamedTextColor.WHITE)));
        lore.add(Component.text("Пассивно: ", NamedTextColor.AQUA)
                .append(Component.text(mode.getPassiveDescription(), NamedTextColor.WHITE)));
        lore.add(Component.empty());
        lore.add(Component.text("Shift + ПКМ / [F]: ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("Сменить стихию", NamedTextColor.GRAY)));

        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(stickKey, PersistentDataType.BOOLEAN, true);
        meta.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode.name());

        stick.setItemMeta(meta);
    }

    /**
     * Получает текущий режим палочки
     */
    public WandMode getMode(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return WandMode.DIRT;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return WandMode.DIRT;
        }
        String modeName = meta.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
        return WandMode.fromString(modeName);
    }

    /**
     * Проверяет, является ли предмет волшебной палочкой
     */
    public boolean isMagicStick(ItemStack item) {
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        if (Boolean.TRUE.equals(meta.getPersistentDataContainer().get(stickKey, PersistentDataType.BOOLEAN))) {
            return true;
        }
        if (meta.getPersistentDataContainer().has(modeKey, PersistentDataType.STRING)) {
            return true;
        }

        if (meta.hasDisplayName()) {
            Component displayName = meta.displayName();
            if (displayName != null) {
                String str = displayName.toString();
                return str.contains("палочка") || str.contains("Земляная палочка") || str.contains("Палочка");
            }
        }

        return false;
    }

    /**
     * Переключает стихию палочки с аудио-визуальными эффектами
     */
    public void cycleMode(Player player, ItemStack item) {
        if (player.hasCooldown(Material.STICK)) {
            return;
        }
        player.setCooldown(Material.STICK, 4);

        WandMode currentMode = getMode(item);
        WandMode nextMode = currentMode.next();
        applyModeToItem(item, nextMode);

        Location eye = player.getEyeLocation();
        World world = player.getWorld();

        switch (nextMode) {
            case DIRT -> {
                world.playSound(eye, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.0f, 1.0f);
                world.playSound(eye, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.0f);
                world.spawnParticle(Particle.BLOCK, eye, 15, 0.25, 0.25, 0.25, 0.05, Material.DIRT.createBlockData());
            }
            case WIND -> {
                world.playSound(eye, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 1.3f);
                world.playSound(eye, Sound.ENTITY_WIND_CHARGE_THROW, 0.8f, 1.4f);
                world.spawnParticle(Particle.GUST_EMITTER_SMALL, eye, 1);
                world.spawnParticle(Particle.SMALL_GUST, eye, 8, 0.25, 0.25, 0.25, 0.05);
            }
            case FIRE -> {
                world.playSound(eye, Sound.ITEM_FIRECHARGE_USE, 1.0f, 1.2f);
                world.playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1.3f);
                world.spawnParticle(Particle.FLAME, eye, 16, 0.25, 0.25, 0.25, 0.05);
                world.spawnParticle(Particle.LAVA, eye, 4, 0.2, 0.2, 0.2, 0.0);
            }
            case WATER -> {
                world.playSound(eye, Sound.ITEM_BUCKET_EMPTY, 1.0f, 1.2f);
                world.playSound(eye, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.8f, 1.4f);
                world.spawnParticle(Particle.SPLASH, eye, 20, 0.25, 0.25, 0.25, 0.1);
                world.spawnParticle(Particle.BUBBLE_POP, eye, 8, 0.25, 0.25, 0.25, 0.05);
            }
        }

        player.sendActionBar(
                Component.text("Стихия выбрана: ", NamedTextColor.GRAY)
                        .append(Component.text(nextMode.getDisplayName(), nextMode.getColor()).decorate(TextDecoration.BOLD))
        );
    }

    /**
     * Фоновая задача пассивных способностей стихий
     */
    private void startPassiveTask() {
        passiveTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    ItemStack main = player.getInventory().getItemInMainHand();
                    ItemStack off = player.getInventory().getItemInOffHand();

                    ItemStack stick = isMagicStick(main) ? main : (isMagicStick(off) ? off : null);
                    if (stick == null) {
                        continue;
                    }

                    WandMode mode = getMode(stick);
                    World world = player.getWorld();
                    Location loc = player.getLocation();

                    switch (mode) {
                        case DIRT -> {
                            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 0, false, false, true));
                            world.spawnParticle(Particle.DUST_PLUME, loc.clone().add(0, 0.1, 0), 1, 0.2, 0.0, 0.2, 0.0);
                        }
                        case WIND -> {
                            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 0, false, false, true));
                            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 40, 0, false, false, true));
                            world.spawnParticle(Particle.SMALL_GUST, loc.clone().add(0, 0.1, 0), 1, 0.2, 0.0, 0.2, 0.0);
                        }
                        case FIRE -> {
                            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 40, 0, false, false, true));
                            world.spawnParticle(Particle.SMALL_FLAME, loc.clone().add(0, 0.1, 0), 2, 0.25, 0.05, 0.25, 0.0);
                        }
                        case WATER -> {
                            player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 40, 0, false, false, true));
                            player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 40, 0, false, false, true));
                            world.spawnParticle(Particle.DRIPPING_WATER, loc.clone().add(0, 1.4, 0), 1, 0.2, 0.0, 0.2, 0.0);

                            // Автоматическое тушение игрока при воспламенении
                            if (player.getFireTicks() > 0) {
                                player.setFireTicks(0);
                                world.playSound(loc, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.4f);
                                world.spawnParticle(Particle.SPLASH, loc.clone().add(0, 1.0, 0), 10, 0.25, 0.25, 0.25, 0.05);
                            }
                        }
                    }
                }
            }
        };
        passiveTask.runTaskTimer(this, 20L, 20L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("magicstick.use")) {
            sender.sendMessage(Component.text("У вас нет прав на использование этой команды!", NamedTextColor.RED));
            return true;
        }

        Player target;
        WandMode mode = WandMode.DIRT;

        if (args.length == 0) {
            if (!(sender instanceof Player playerSender)) {
                sender.sendMessage(Component.text("Использование из консоли: /" + label + " <игрок> [dirt|wind|fire|water]", NamedTextColor.RED));
                return true;
            }
            target = playerSender;
        } else {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок '" + args[0] + "' не найден!", NamedTextColor.RED));
                return true;
            }
            if (args.length >= 2) {
                mode = WandMode.fromString(args[1]);
            }
        }

        ItemStack stick = createMagicStick(mode);
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(stick);
        if (!leftover.isEmpty()) {
            target.getWorld().dropItem(target.getLocation(), stick);
            target.sendMessage(Component.text("Инвентарь полон, волшебная палочка брошена на землю!", NamedTextColor.GOLD));
        } else {
            target.sendMessage(Component.text("Вам выдана волшебная палочка (Режим: " + mode.getDisplayName() + ")!", NamedTextColor.GREEN));
        }

        if (sender != target) {
            sender.sendMessage(Component.text("Игроку " + target.getName() + " успешно выдана волшебная палочка!", NamedTextColor.GREEN));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    players.add(p.getName());
                }
            }
            return players;
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            List<String> modes = new ArrayList<>();
            for (WandMode m : WandMode.values()) {
                if (m.name().toLowerCase().startsWith(prefix)) {
                    modes.add(m.name().toLowerCase());
                }
            }
            for (String aliasMode : List.of("earth", "air", "fire", "water")) {
                if (aliasMode.startsWith(prefix) && !modes.contains(aliasMode)) {
                    modes.add(aliasMode);
                }
            }
            return modes;
        }
        return Collections.emptyList();
    }

    /**
     * Быстрая смена стихии на клавишу 'F' (Swap Hand)
     */
    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack mainItem = player.getInventory().getItemInMainHand();
        if (isMagicStick(mainItem)) {
            event.setCancelled(true);
            cycleMode(player, mainItem);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        boolean isRightClick = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean isLeftClick = event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK;

        if (!isRightClick && !isLeftClick) {
            return;
        }

        if (event.getHand() == null) {
            return;
        }

        ItemStack item = event.getItem();
        if (!isMagicStick(item)) {
            return;
        }

        Player player = event.getPlayer();

        // Предотвращаем дублирующий вызов из второй руки
        if (event.getHand() == EquipmentSlot.OFF_HAND && isMagicStick(player.getInventory().getItemInMainHand())) {
            return;
        }

        // Смена способности при зажатом Shift + ПКМ
        if (player.isSneaking() && isRightClick) {
            event.setCancelled(true);
            cycleMode(player, item);
            return;
        }

        // --- ЛКМ (Вторая тактическая способность для каждой стихии) ---
        if (isLeftClick) {
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true);
            }

            WandMode mode = getMode(item);
            switch (mode) {
                case DIRT -> createDirtWall(player);
                case WIND -> windJump(player);
                case FIRE -> flameDash(player);
                case WATER -> cleanseWave(player);
            }
            return;
        }

        // --- ПКМ (Основная боевая способность для каждой стихии) ---
        if (isRightClick) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
            }

            if (player.hasCooldown(Material.STICK)) {
                return;
            }

            WandMode mode = getMode(item);
            switch (mode) {
                case DIRT -> {
                    player.setCooldown(Material.STICK, 30); // 1.5 сек
                    shootDirt(player);
                }
                case WIND -> {
                    player.setCooldown(Material.STICK, 30); // 1.5 сек
                    shootWind(player);
                }
                case FIRE -> {
                    player.setCooldown(Material.STICK, 30); // 1.5 сек
                    shootFireball(player);
                }
                case WATER -> {
                    player.setCooldown(Material.STICK, 25); // 1.25 сек
                    shootWaterStream(player);
                }
            }
        }
    }

    /**
     * Стихия Земли (ПКМ): Стреляет блоком земли от лица игрока
     */
    private void shootDirt(Player player) {
        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection().normalize();

        Location spawnLoc = eyeLoc.clone().add(direction.clone().multiply(0.8));
        if (spawnLoc.getBlock().getType().isSolid()) {
            spawnLoc = eyeLoc.clone();
        }

        World world = player.getWorld();

        FallingBlock dirt = world.spawnFallingBlock(spawnLoc, Material.DIRT.createBlockData());
        dirt.setVelocity(direction.multiply(1.6));
        dirt.setDropItem(false);
        dirt.setCancelDrop(true);
        dirt.setHurtEntities(true);
        dirt.setDamagePerBlock(3.0f);
        dirt.setMaxDamage(10);

        dirt.getPersistentDataContainer().set(stickKey, PersistentDataType.BOOLEAN, true);

        world.playSound(spawnLoc, Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.6f);
        world.playSound(spawnLoc, Sound.BLOCK_GRASS_PLACE, 0.8f, 1.2f);
        world.spawnParticle(Particle.DUST_PLUME, spawnLoc, 6, 0.1, 0.1, 0.1, 0.05);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!dirt.isValid() || dirt.isDead() || ticks++ > 100) {
                    cancel();
                    return;
                }

                world.spawnParticle(
                        Particle.BLOCK,
                        dirt.getLocation(),
                        2, 0.1, 0.1, 0.1, 0.0,
                        Material.DIRT.createBlockData()
                );

                for (Entity entity : dirt.getNearbyEntities(0.85, 0.85, 0.85)) {
                    if (entity instanceof LivingEntity target && !target.getUniqueId().equals(player.getUniqueId())) {
                        if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
                            continue;
                        }

                        target.damage(5.0, player);
                        Vector knockback = dirt.getVelocity().normalize().multiply(0.5).setY(0.25);
                        target.setVelocity(target.getVelocity().add(knockback));

                        world.playSound(dirt.getLocation(), Sound.BLOCK_GRASS_BREAK, 1.2f, 0.8f);
                        world.spawnParticle(
                                Particle.BLOCK,
                                dirt.getLocation(),
                                18, 0.3, 0.3, 0.3, 0.1,
                                Material.DIRT.createBlockData()
                        );

                        dirt.remove();
                        cancel();
                        return;
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /**
     * Стихия Земли (ЛКМ): Создает временную защитную стену из земли (3x3 на 6 секунд)
     */
    private void createDirtWall(Player player) {
        long now = System.currentTimeMillis();
        long lastUse = wallCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long cooldownMillis = 6000L; // 6 секунд кулдаун

        if (now - lastUse < cooldownMillis) {
            long remainingSec = (cooldownMillis - (now - lastUse) + 999) / 1000;
            player.sendActionBar(Component.text("Стена земли перезаряжается: " + remainingSec + " сек.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.6f);
            return;
        }

        wallCooldowns.put(player.getUniqueId(), now);
        player.setCooldown(Material.STICK, 20); // 1 сек общий кулдаун палочки

        Location playerLoc = player.getLocation();
        World world = player.getWorld();

        // Направление перед игроком
        Vector dir = playerLoc.getDirection();
        Vector forward = new Vector(dir.getX(), 0, dir.getZ());
        if (forward.lengthSquared() < 0.001) {
            double yawRad = Math.toRadians(playerLoc.getYaw());
            forward = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
        }
        forward.normalize();

        // Перпендикулярный вектор ширины стены (влево/вправо)
        Vector right = new Vector(-forward.getZ(), 0, forward.getX()).normalize();

        // Центр стены на расстоянии 2.5 блоков
        Location center = playerLoc.clone().add(forward.clone().multiply(2.5));

        // Поиск уровня земли
        int baseY = center.getBlockY();
        for (int dy = 0; dy >= -3; dy--) {
            Block check = world.getBlockAt(center.getBlockX(), baseY + dy, center.getBlockZ());
            if (check.getType().isSolid()) {
                baseY = baseY + dy + 1;
                break;
            }
        }

        Map<Block, BlockData> currentWall = new HashMap<>();

        // Строим стену 3 блока в ширину и 3 блока в высоту
        for (int w = -1; w <= 1; w++) {
            Location colLoc = center.clone().add(right.clone().multiply(w));
            int x = colLoc.getBlockX();
            int z = colLoc.getBlockZ();

            for (int h = 0; h < 3; h++) {
                int y = baseY + h;
                Block b = world.getBlockAt(x, y, z);

                // Заменяем только проходимые/неплотные блоки (воздух, траву и т.д.)
                if (b.getType().isAir() || b.isPassable() || !b.getType().isSolid()) {
                    if (!temporaryWallBlocks.containsKey(b)) {
                        currentWall.put(b, b.getBlockData());
                        temporaryWallBlocks.put(b, b.getBlockData());

                        if ((w + h) % 2 == 0) {
                            b.setType(Material.DIRT);
                        } else {
                            b.setType(Material.COARSE_DIRT);
                        }

                        world.spawnParticle(
                                Particle.BLOCK,
                                b.getLocation().add(0.5, 0.5, 0.5),
                                8, 0.2, 0.2, 0.2, 0.05,
                                Material.DIRT.createBlockData()
                        );
                    }
                }
            }
        }

        // Звуки возведения стены
        world.playSound(center, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.3f, 0.8f);
        world.playSound(center, Sound.BLOCK_GRAVEL_PLACE, 1.2f, 0.9f);
        world.playSound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 0.7f);

        player.sendActionBar(Component.text("Возведена защитная стена земли! (6 сек.)", NamedTextColor.GOLD));

        // Таймер исчезновения стены через 6 секунд (120 тиков)
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<Block, BlockData> entry : currentWall.entrySet()) {
                    Block b = entry.getKey();
                    BlockData original = temporaryWallBlocks.remove(b);
                    if (original != null && (b.getType() == Material.DIRT || b.getType() == Material.COARSE_DIRT)) {
                        b.setBlockData(original);
                        world.spawnParticle(
                                Particle.BLOCK,
                                b.getLocation().add(0.5, 0.5, 0.5),
                                6, 0.2, 0.2, 0.2, 0.05,
                                Material.DIRT.createBlockData()
                        );
                    }
                }
                world.playSound(center, Sound.BLOCK_GRASS_BREAK, 1.1f, 0.8f);
            }
        }.runTaskLater(this, 120L);
    }

    /**
     * Стихия Воздуха (ПКМ): Шлейф ветра, сдувающий существ от игрока
     */
    private void shootWind(Player player) {
        Location startLoc = player.getEyeLocation().clone().subtract(0, 0.2, 0);
        Vector direction = player.getEyeLocation().getDirection().normalize();
        World world = player.getWorld();

        world.playSound(startLoc, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.2f, 1.0f);
        world.playSound(startLoc, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 1.2f);

        world.spawnParticle(Particle.GUST_EMITTER_SMALL, startLoc, 1);
        world.spawnParticle(Particle.CLOUD, startLoc, 10, 0.3, 0.3, 0.3, 0.05);

        Set<UUID> affectedEntities = new HashSet<>();
        affectedEntities.add(player.getUniqueId());

        new BukkitRunnable() {
            int step = 0;
            final int maxSteps = 15;
            Location currentLoc = startLoc.clone();

            @Override
            public void run() {
                if (step >= maxSteps) {
                    cancel();
                    return;
                }

                currentLoc.add(direction.clone().multiply(1.1));

                if (currentLoc.getBlock().getType().isSolid()) {
                    world.spawnParticle(Particle.GUST, currentLoc, 1);
                    world.spawnParticle(Particle.CLOUD, currentLoc, 12, 0.3, 0.3, 0.3, 0.08);
                    world.playSound(currentLoc, Sound.ENTITY_WIND_CHARGE_THROW, 0.8f, 1.3f);
                    cancel();
                    return;
                }

                world.spawnParticle(Particle.GUST, currentLoc, 1);
                world.spawnParticle(Particle.SMALL_GUST, currentLoc, 2, 0.2, 0.2, 0.2, 0.02);
                world.spawnParticle(Particle.CLOUD, currentLoc, 4, 0.25, 0.25, 0.25, 0.02);

                if (step % 3 == 0) {
                    world.playSound(currentLoc, Sound.ENTITY_WIND_CHARGE_THROW, 0.6f, 1.4f);
                }

                for (Entity entity : world.getNearbyEntities(currentLoc, 2.0, 2.0, 2.0)) {
                    if (!(entity instanceof LivingEntity target)) {
                        continue;
                    }

                    if (affectedEntities.contains(target.getUniqueId())) {
                        continue;
                    }

                    if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
                        continue;
                    }

                    if (target.isInvulnerable()) {
                        continue;
                    }

                    affectedEntities.add(target.getUniqueId());

                    Vector pushVec = target.getLocation().toVector().subtract(player.getLocation().toVector());
                    if (pushVec.lengthSquared() < 0.01) {
                        pushVec = direction.clone();
                    }
                    pushVec.normalize();

                    pushVec.add(direction.clone().multiply(1.4)).normalize();

                    // Синергия стихий: если враг намочен/замедлен водой, отлетает в 1.5 раза дальше!
                    boolean isSlowed = target.hasPotionEffect(PotionEffectType.SLOWNESS);
                    double multiplier = isSlowed ? 2.8 : 1.9;
                    double yBoost = isSlowed ? 0.6 : 0.48;

                    pushVec.multiply(multiplier).setY(yBoost);
                    target.setVelocity(pushVec);

                    target.damage(2.0, player);

                    world.playSound(target.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.1f, 1.3f);
                    world.spawnParticle(isSlowed ? Particle.GUST_EMITTER_LARGE : Particle.GUST_EMITTER_SMALL, target.getLocation().add(0, 1.0, 0), 1);
                    world.spawnParticle(Particle.CLOUD, target.getLocation().add(0, 1.0, 0), 10, 0.35, 0.35, 0.35, 0.08);
                }

                step++;
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /**
     * Стихия Воздуха (ЛКМ): Подлет вверх с помощью порыва ветра под ногами и защитой от падения
     */
    private void windJump(Player player) {
        long now = System.currentTimeMillis();
        long lastUse = jumpCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long cooldownMillis = 4000L; // 4 секунды кулдаун

        if (now - lastUse < cooldownMillis) {
            long remainingSec = (cooldownMillis - (now - lastUse) + 999) / 1000;
            player.sendActionBar(Component.text("Взлет на перезарядке: " + remainingSec + " сек.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.6f);
            return;
        }

        jumpCooldowns.put(player.getUniqueId(), now);
        player.setCooldown(Material.STICK, 15); // 0.75 сек общий кулдаун

        World world = player.getWorld();
        Location loc = player.getLocation();

        // Импульс взлета: подброс вверх с небольшим движением по направлению взгляда
        Vector dir = loc.getDirection();
        Vector velocity = new Vector(dir.getX() * 0.45, 1.25, dir.getZ() * 0.45);
        player.setVelocity(velocity);

        // Иммунитет к падению
        fallImmunePlayers.add(player.getUniqueId());

        // Снятие иммунитета через 15 секунд на случай зависания
        Bukkit.getScheduler().runTaskLater(this, () -> fallImmunePlayers.remove(player.getUniqueId()), 300L);

        // Звуки и эффекты взлета
        world.playSound(loc, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.3f, 1.1f);
        world.playSound(loc, Sound.ENTITY_BREEZE_JUMP, 1.2f, 1.2f);
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, loc, 1);
        world.spawnParticle(Particle.CLOUD, loc, 16, 0.4, 0.1, 0.4, 0.08);
        world.spawnParticle(Particle.SMALL_GUST, loc, 6, 0.3, 0.1, 0.3, 0.05);

        player.sendActionBar(Component.text("Ветряной прыжок!", NamedTextColor.AQUA));

        // Отталкивание ближайших существ ударной волной от взлета
        for (Entity entity : world.getNearbyEntities(loc, 3.2, 2.0, 3.2)) {
            if (entity instanceof LivingEntity target && !target.getUniqueId().equals(player.getUniqueId())) {
                if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
                    continue;
                }
                Vector push = target.getLocation().toVector().subtract(loc.toVector());
                if (push.lengthSquared() < 0.01) {
                    push = new Vector(1, 0, 0);
                }
                push.setY(0).normalize().multiply(1.4).setY(0.35);
                target.setVelocity(push);
                target.damage(1.0, player);
            }
        }
    }

    /**
     * Стихия Огня (ПКМ): Стремительный огненный шар, взрывающийся при попадании
     */
    private void shootFireball(Player player) {
        Location startLoc = player.getEyeLocation().clone().subtract(0, 0.15, 0);
        Vector direction = player.getEyeLocation().getDirection().normalize();
        World world = player.getWorld();

        world.playSound(startLoc, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 1.0f);
        world.playSound(startLoc, Sound.ITEM_FIRECHARGE_USE, 1.0f, 1.1f);
        world.spawnParticle(Particle.FLAME, startLoc, 8, 0.1, 0.1, 0.1, 0.05);

        new BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = 35; // до 50 блоков
            Location currentLoc = startLoc.clone();
            final double speed = 1.5;

            @Override
            public void run() {
                if (ticks++ > maxTicks) {
                    cancel();
                    return;
                }

                currentLoc.add(direction.clone().multiply(speed));

                // Частицы летящего огненного снаряда
                world.spawnParticle(Particle.FLAME, currentLoc, 4, 0.08, 0.08, 0.08, 0.01);
                world.spawnParticle(Particle.SMALL_FLAME, currentLoc, 3, 0.05, 0.05, 0.05, 0.01);
                world.spawnParticle(Particle.SMOKE, currentLoc, 1, 0.02, 0.02, 0.02, 0.0);

                Block block = currentLoc.getBlock();
                boolean hitBlock = block.getType().isSolid();

                LivingEntity hitTarget = null;
                for (Entity entity : world.getNearbyEntities(currentLoc, 1.2, 1.2, 1.2)) {
                    if (entity instanceof LivingEntity target && !target.getUniqueId().equals(player.getUniqueId())) {
                        if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
                            continue;
                        }
                        if (target.isInvulnerable()) {
                            continue;
                        }
                        hitTarget = target;
                        break;
                    }
                }

                if (hitBlock || hitTarget != null) {
                    explodeFireball(currentLoc, player);
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /**
     * Взрыв огненного шара с взаимодействием с окружением и врагами
     */
    private void explodeFireball(Location loc, Player player) {
        World world = loc.getWorld();

        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1.3f);
        world.playSound(loc, Sound.ITEM_FIRECHARGE_USE, 1.2f, 0.8f);

        world.spawnParticle(Particle.EXPLOSION, loc, 1);
        world.spawnParticle(Particle.FLAME, loc, 25, 0.5, 0.5, 0.5, 0.1);
        world.spawnParticle(Particle.LAVA, loc, 8, 0.35, 0.35, 0.35, 0.0);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 6, 0.3, 0.3, 0.3, 0.03);

        Block centerBlock = loc.getBlock();

        // Взаимодействие с миром: растапливание снега и льда
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = centerBlock.getRelative(dx, dy, dz);
                    if (b.getType() == Material.ICE) {
                        b.setType(Material.WATER);
                    } else if (b.getType() == Material.SNOW || b.getType() == Material.SNOW_BLOCK) {
                        b.setType(Material.AIR);
                    }
                }
            }
        }

        // Взаимодействие со стеной земли: раскаленные осколки магмы!
        boolean hitWall = false;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block b = centerBlock.getRelative(dx, dy, dz);
                    if (temporaryWallBlocks.containsKey(b)) {
                        hitWall = true;
                        break;
                    }
                }
                if (hitWall) break;
            }
            if (hitWall) break;
        }

        if (hitWall) {
            world.playSound(loc, Sound.BLOCK_LAVA_EXTINGUISH, 1.2f, 0.9f);
            world.spawnParticle(Particle.LAVA, loc, 16, 0.6, 0.6, 0.6, 0.1);
        }

        double radius = hitWall ? 3.5 : 2.8;
        double baseDamage = hitWall ? 8.0 : 6.0;

        for (Entity entity : world.getNearbyEntities(loc, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || target.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
                continue;
            }
            if (target.isInvulnerable()) {
                continue;
            }

            target.damage(baseDamage, player);
            target.setFireTicks(100); // 5 секунд горения

            Vector push = target.getLocation().toVector().subtract(loc.toVector());
            if (push.lengthSquared() < 0.01) {
                push = new Vector(0, 0.3, 0);
            } else {
                push.normalize().multiply(0.7).setY(0.3);
            }
            target.setVelocity(target.getVelocity().add(push));
        }
    }

    /**
     * Стихия Огня (ЛКМ): Стремительный огненный рывок сквозь врагов
     */
    private void flameDash(Player player) {
        long now = System.currentTimeMillis();
        long lastUse = fireDashCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long cooldownMillis = 5000L; // 5 секунд кулдаун

        if (now - lastUse < cooldownMillis) {
            long remainingSec = (cooldownMillis - (now - lastUse) + 999) / 1000;
            player.sendActionBar(Component.text("Огненный рывок перезаряжается: " + remainingSec + " сек.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.6f);
            return;
        }

        fireDashCooldowns.put(player.getUniqueId(), now);
        player.setCooldown(Material.STICK, 15);

        World world = player.getWorld();
        Location loc = player.getLocation();

        // Импульс рывка вперед
        Vector dir = loc.getDirection();
        Vector dashVec = dir.clone().multiply(1.8).setY(Math.max(0.18, dir.getY() * 0.4 + 0.18));
        player.setVelocity(dashVec);

        // Временная защита от урона при падении
        fallImmunePlayers.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> fallImmunePlayers.remove(player.getUniqueId()), 120L);

        world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2f, 0.8f);
        world.playSound(loc, Sound.ITEM_FIRECHARGE_USE, 1.3f, 1.1f);
        world.spawnParticle(Particle.FLAME, loc, 20, 0.4, 0.4, 0.4, 0.08);

        player.sendActionBar(Component.text("Огненный рывок!", NamedTextColor.RED));

        Set<UUID> hitTargets = new HashSet<>();

        // Огненный след за игроком и урон врагам по пути
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks++ > 8 || !player.isOnline()) {
                    cancel();
                    return;
                }

                Location pLoc = player.getLocation().add(0, 0.4, 0);
                world.spawnParticle(Particle.FLAME, pLoc, 10, 0.3, 0.3, 0.3, 0.04);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, pLoc, 3, 0.2, 0.2, 0.2, 0.02);
                world.spawnParticle(Particle.SMOKE, pLoc, 4, 0.2, 0.2, 0.2, 0.02);

                for (Entity entity : world.getNearbyEntities(pLoc, 1.8, 1.8, 1.8)) {
                    if (entity instanceof LivingEntity target && !target.getUniqueId().equals(player.getUniqueId())) {
                        if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
                            continue;
                        }
                        if (target.isInvulnerable() || hitTargets.contains(target.getUniqueId())) {
                            continue;
                        }

                        hitTargets.add(target.getUniqueId());
                        target.damage(5.0, player);
                        target.setFireTicks(80); // 4 сек огня
                        target.setVelocity(target.getVelocity().add(dir.clone().multiply(0.5).setY(0.25)));

                        world.playSound(target.getLocation(), Sound.ENTITY_BLAZE_HURT, 1.0f, 1.2f);
                        world.spawnParticle(Particle.LAVA, target.getLocation().add(0, 1.0, 0), 4, 0.2, 0.2, 0.2, 0.0);
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /**
     * Стихия Воды (ПКМ): Пронзающая водная струя с замедлением и реакцией 'Испарение'
     */
    private void shootWaterStream(Player player) {
        Location startLoc = player.getEyeLocation().clone().subtract(0, 0.2, 0);
        Vector direction = player.getEyeLocation().getDirection().normalize();
        World world = player.getWorld();

        world.playSound(startLoc, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.2f, 1.2f);
        world.playSound(startLoc, Sound.ITEM_BUCKET_EMPTY, 0.9f, 1.4f);

        world.spawnParticle(Particle.SPLASH, startLoc, 10, 0.2, 0.2, 0.2, 0.05);
        world.spawnParticle(Particle.BUBBLE_POP, startLoc, 5, 0.1, 0.1, 0.1, 0.02);

        Set<UUID> hitTargets = new HashSet<>();

        new BukkitRunnable() {
            int step = 0;
            final int maxSteps = 16;
            Location currentLoc = startLoc.clone();

            @Override
            public void run() {
                if (step++ >= maxSteps) {
                    cancel();
                    return;
                }

                currentLoc.add(direction.clone().multiply(1.2));

                Block block = currentLoc.getBlock();
                if (block.getType().isSolid()) {
                    world.spawnParticle(Particle.SPLASH, currentLoc, 20, 0.3, 0.3, 0.3, 0.1);
                    world.playSound(currentLoc, Sound.ENTITY_PLAYER_SPLASH, 0.9f, 1.3f);
                    cancel();
                    return;
                }

                // Тушение огня на пути
                if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) {
                    block.setType(Material.AIR);
                    world.playSound(currentLoc, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.2f);
                }

                // Частицы струи воды
                world.spawnParticle(Particle.SPLASH, currentLoc, 6, 0.15, 0.15, 0.15, 0.05);
                world.spawnParticle(Particle.BUBBLE, currentLoc, 3, 0.1, 0.1, 0.1, 0.01);
                world.spawnParticle(Particle.BUBBLE_POP, currentLoc, 2, 0.1, 0.1, 0.1, 0.01);

                for (Entity entity : world.getNearbyEntities(currentLoc, 1.3, 1.3, 1.3)) {
                    if (!(entity instanceof LivingEntity target) || target.getUniqueId().equals(player.getUniqueId())) {
                        continue;
                    }
                    if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
                        continue;
                    }
                    if (target.isInvulnerable() || hitTargets.contains(target.getUniqueId())) {
                        continue;
                    }

                    hitTargets.add(target.getUniqueId());

                    boolean wasBurning = target.getFireTicks() > 0;

                    // Базовый урон и замедление
                    target.damage(5.0, player);
                    target.setFireTicks(0); // Тушит огонь
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1)); // Замедление II на 3 сек

                    Vector pushVec = direction.clone().multiply(0.7).setY(0.2);
                    target.setVelocity(target.getVelocity().add(pushVec));

                    // Элементальная реакция "Испарение" (Vaporize)
                    if (wasBurning) {
                        target.damage(7.0, player); // Дополнительный сокрушительный урон паром!
                        world.playSound(target.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1.4f, 0.9f);
                        world.playSound(target.getLocation(), Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 1.2f, 1.1f);
                        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, target.getLocation().add(0, 1.0, 0), 16, 0.3, 0.5, 0.3, 0.04);
                        world.spawnParticle(Particle.POOF, target.getLocation().add(0, 1.0, 0), 8, 0.2, 0.3, 0.2, 0.02);

                        player.sendActionBar(
                                Component.text("✦ ИСПАРЕНИЕ! +7 к урону паром! ✦", NamedTextColor.AQUA)
                                        .decorate(TextDecoration.BOLD)
                        );
                    }

                    world.playSound(target.getLocation(), Sound.ENTITY_PLAYER_SPLASH, 1.1f, 1.1f);
                    world.spawnParticle(Particle.SPLASH, target.getLocation().add(0, 1.0, 0), 16, 0.3, 0.4, 0.3, 0.08);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /**
     * Стихия Воды (ЛКМ): Приливная волна исцеления, очищения и смыва врагов
     */
    private void cleanseWave(Player player) {
        long now = System.currentTimeMillis();
        long lastUse = waterWaveCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long cooldownMillis = 6000L; // 6 секунд кулдаун

        if (now - lastUse < cooldownMillis) {
            long remainingSec = (cooldownMillis - (now - lastUse) + 999) / 1000;
            player.sendActionBar(Component.text("Приливная волна перезаряжается: " + remainingSec + " сек.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.6f);
            return;
        }

        waterWaveCooldowns.put(player.getUniqueId(), now);
        player.setCooldown(Material.STICK, 20);

        World world = player.getWorld();
        Location center = player.getLocation();

        // Звуки прилива
        world.playSound(center, Sound.ITEM_BUCKET_EMPTY, 1.2f, 0.9f);
        world.playSound(center, Sound.ENTITY_DOLPHIN_SPLASH, 1.3f, 1.1f);
        world.playSound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.1f, 1.0f);

        // Очищение самого игрока от негативных эффектов и огня
        player.setFireTicks(0);
        player.removePotionEffect(PotionEffectType.POISON);
        player.removePotionEffect(PotionEffectType.WITHER);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);

        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 1)); // Регенерация II на 4 сек (~3-4 сердца)
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 140, 0));  // Золотые сердца на 7 сек

        player.sendActionBar(Component.text("Приливная волна: Очищение и исцеление!", NamedTextColor.BLUE));

        // Расширяющаяся круговая волна
        new BukkitRunnable() {
            int step = 1;
            final int maxStep = 4;

            @Override
            public void run() {
                if (step > maxStep) {
                    cancel();
                    return;
                }

                double radius = step * 1.2; // расширение до 4.8 блоков
                int points = 24 + step * 8;

                for (int i = 0; i < points; i++) {
                    double angle = 2 * Math.PI * i / points;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    Location particleLoc = center.clone().add(x, 0.3, z);

                    world.spawnParticle(Particle.SPLASH, particleLoc, 2, 0.1, 0.2, 0.1, 0.05);
                    world.spawnParticle(Particle.BUBBLE_POP, particleLoc, 1, 0.05, 0.1, 0.05, 0.01);
                    world.spawnParticle(Particle.BUBBLE_COLUMN_UP, particleLoc, 1, 0.05, 0.1, 0.05, 0.01);
                }

                step++;
            }
        }.runTaskTimer(this, 0L, 1L);

        // Воздействие на окружающих существ
        for (Entity entity : world.getNearbyEntities(center, 4.8, 3.0, 4.8)) {
            if (!(entity instanceof LivingEntity target) || target.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }

            // Союзные игроки тушатся и получают исцеление
            if (target instanceof Player ally) {
                ally.setFireTicks(0);
                ally.removePotionEffect(PotionEffectType.POISON);
                ally.removePotionEffect(PotionEffectType.WITHER);
                ally.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0));
                ally.getWorld().playSound(ally.getLocation(), Sound.ENTITY_PLAYER_SPLASH, 0.8f, 1.2f);
                continue;
            }

            if (target.isInvulnerable()) {
                continue;
            }

            // Враги получают урон, смываются назад и замедляются
            target.damage(3.0, player);
            target.setFireTicks(0);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 0));

            Vector push = target.getLocation().toVector().subtract(center.toVector());
            if (push.lengthSquared() < 0.01) {
                push = new Vector(1, 0, 0);
            }
            push.setY(0).normalize().multiply(1.5).setY(0.4);
            target.setVelocity(push);

            world.spawnParticle(Particle.SPLASH, target.getLocation().add(0, 1.0, 0), 12, 0.3, 0.3, 0.3, 0.05);
        }
    }

    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock fallingBlock) {
            if (Boolean.TRUE.equals(fallingBlock.getPersistentDataContainer().get(stickKey, PersistentDataType.BOOLEAN))) {
                fallingBlock.getWorld().playSound(event.getBlock().getLocation(), Sound.BLOCK_GRASS_PLACE, 1.0f, 0.9f);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        // Поглощение урона от падения после ветряного прыжка или огненного рывка
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) {
            if (fallImmunePlayers.remove(player.getUniqueId())) {
                event.setCancelled(true);
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_LAND, 1.2f, 1.2f);
                player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 12, 0.3, 0.1, 0.3, 0.05);
                player.getWorld().spawnParticle(Particle.SMALL_GUST, player.getLocation(), 4, 0.2, 0.1, 0.2, 0.02);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        // Предотвращение дюпа блоков из временной стены
        if (temporaryWallBlocks.containsKey(event.getBlock())) {
            event.setDropItems(false);
            temporaryWallBlocks.remove(event.getBlock());
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        // Очистка блоков при взрывах рядом со стеной
        for (Block b : event.blockList()) {
            temporaryWallBlocks.remove(b);
        }
    }
}
