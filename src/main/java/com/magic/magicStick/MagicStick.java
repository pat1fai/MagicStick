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
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class MagicStick extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey stickKey;

    @Override
    public void onEnable() {
        this.stickKey = new NamespacedKey(this, "magic_dirt_stick");

        // Регистрируем слушатель событий
        getServer().getPluginManager().registerEvents(this, this);

        // Регистрируем команду /getstick
        PluginCommand command = getCommand("getstick");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        getLogger().info("MagicStick успешно включен!");
    }

    @Override
    public void onDisable() {
        getLogger().info("MagicStick выключен!");
    }

    /**
     * Создает предмет волшебной земляной палочки
     */
    public ItemStack createMagicStick() {
        ItemStack stick = new ItemStack(Material.STICK);
        ItemMeta meta = stick.getItemMeta();
        if (meta != null) {
            meta.displayName(
                    Component.text("Земляная палочка", NamedTextColor.GOLD)
                            .decorate(TextDecoration.BOLD)
            );

            meta.lore(List.of(
                    Component.text("ПКМ: выстрелить блоком земли", NamedTextColor.YELLOW),
                    Component.text("Магический артефакт", NamedTextColor.DARK_PURPLE)
            ));

            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(stickKey, PersistentDataType.BOOLEAN, true);
            stick.setItemMeta(meta);
        }
        return stick;
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

        // Проверка через PersistentDataContainer
        if (Boolean.TRUE.equals(meta.getPersistentDataContainer().get(stickKey, PersistentDataType.BOOLEAN))) {
            return true;
        }

        // Запасная проверка по названию
        if (meta.hasDisplayName()) {
            Component displayName = meta.displayName();
            return displayName != null && displayName.toString().contains("Земляная палочка");
        }

        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("magicstick.use")) {
            sender.sendMessage(Component.text("У вас нет прав на использование этой команды!", NamedTextColor.RED));
            return true;
        }

        Player target;

        if (args.length == 0) {
            if (!(sender instanceof Player playerSender)) {
                sender.sendMessage(Component.text("Использование из консоли: /" + label + " <игрок>", NamedTextColor.RED));
                return true;
            }
            target = playerSender;
        } else {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок '" + args[0] + "' не найден!", NamedTextColor.RED));
                return true;
            }
        }

        ItemStack stick = createMagicStick();
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(stick);
        if (!leftover.isEmpty()) {
            // Если инвентарь полон, выбрасываем под ноги
            target.getWorld().dropItem(target.getLocation(), stick);
            target.sendMessage(Component.text("Ваш инвентарь полон, земляная палочка брошена на землю!", NamedTextColor.GOLD));
        } else {
            target.sendMessage(Component.text("Вам выдана земляная палочка!", NamedTextColor.GREEN));
        }

        if (sender != target) {
            sender.sendMessage(Component.text("Игроку " + target.getName() + " успешно выдана земляная палочка!", NamedTextColor.GREEN));
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
        }
        return Collections.emptyList();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // Проверяем нажатие ПКМ (по воздуху или блоку)
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getHand() == null) {
            return;
        }

        ItemStack item = event.getItem();
        if (!isMagicStick(item)) {
            return;
        }

        // Предотвращаем дублирующий вызов из второй руки
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND && isMagicStick(player.getInventory().getItemInMainHand())) {
            return;
        }

        // Отменяем взаимодействие с блоками (открытие сундуков, нажатие кнопок и т.д.)
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
        }

        // Проверка кулдауна
        if (player.hasCooldown(Material.STICK)) {
            return;
        }
        player.setCooldown(Material.STICK, 35); // 35 тиков (~1.5 сек)

        shootDirt(player);
    }

    /**
     * Стреляет блоком земли от лица игрока
     */
    private void shootDirt(Player player) {
        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection().normalize();

        // Смещение точки появления вперед, чтобы не спавнить внутри игрока
        Location spawnLoc = eyeLoc.clone().add(direction.clone().multiply(0.8));
        if (spawnLoc.getBlock().getType().isSolid()) {
            spawnLoc = eyeLoc.clone();
        }

        World world = player.getWorld();

        // Создаем летящий блок земли
        FallingBlock dirt = world.spawnFallingBlock(spawnLoc, Material.DIRT.createBlockData());
        dirt.setVelocity(direction.multiply(1.6));
        dirt.setDropItem(false);
        dirt.setCancelDrop(true);
        dirt.setHurtEntities(true);
        dirt.setDamagePerBlock(3.0f);
        dirt.setMaxDamage(10);

        // Помечаем блок, чтобы опознавать его при приземлении
        dirt.getPersistentDataContainer().set(stickKey, PersistentDataType.BOOLEAN, true);

        // Звуки и эффекты выстрела
        world.playSound(spawnLoc, Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.6f);
        world.playSound(spawnLoc, Sound.BLOCK_GRASS_PLACE, 0.8f, 1.2f);
        world.spawnParticle(Particle.DUST_PLUME, spawnLoc, 6, 0.1, 0.1, 0.1, 0.05);

        // Отслеживаем полет блока: шлейф частиц и столкновение с сущностями
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!dirt.isValid() || dirt.isDead() || ticks++ > 100) {
                    cancel();
                    return;
                }

                // Шлейф частиц земли во время полета
                world.spawnParticle(
                        Particle.BLOCK,
                        dirt.getLocation(),
                        2, 0.1, 0.1, 0.1, 0.0,
                        Material.DIRT.createBlockData()
                );

                // Проверяем столкновение с мобами и игроками
                for (Entity entity : dirt.getNearbyEntities(0.85, 0.85, 0.85)) {
                    if (entity instanceof LivingEntity target && !target.getUniqueId().equals(player.getUniqueId())) {
                        if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
                            continue;
                        }

                        // Наносим урон и отталкиваем
                        target.damage(5.0, player);
                        Vector knockback = dirt.getVelocity().normalize().multiply(0.5).setY(0.25);
                        target.setVelocity(target.getVelocity().add(knockback));

                        // Звук и взрыв частиц земли при попадании
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

    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock fallingBlock) {
            if (Boolean.TRUE.equals(fallingBlock.getPersistentDataContainer().get(stickKey, PersistentDataType.BOOLEAN))) {
                // Звук приземления блока земли
                fallingBlock.getWorld().playSound(event.getBlock().getLocation(), Sound.BLOCK_GRASS_PLACE, 1.0f, 0.9f);
            }
        }
    }
}
