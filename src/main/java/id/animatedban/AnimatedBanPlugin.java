package id.animatedban;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AnimatedBanPlugin extends JavaPlugin implements Listener, TabExecutor {
    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Set<UUID> activeAnimations = new HashSet<>();
    private NamespacedKey animKey;

    @Override
    public void onEnable() {
        animKey = new NamespacedKey(this, "animatedban");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("banhammer") != null) getCommand("banhammer").setExecutor(this);
        if (getCommand("bn") != null) getCommand("bn").setExecutor(this);
        getLogger().info("Animatedban enabled. Author: KingBrezz");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("animatedban.use")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("bn")) {
            if (args.length < 2 || !args[0].equalsIgnoreCase("test")) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /bn test <player>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player tidak online.");
                return true;
            }
            if (target.hasPermission("animatedban.bypass")) {
                sender.sendMessage(ChatColor.RED + "Player tersebut memiliki animatedban.bypass.");
                return true;
            }
            if (activeAnimations.contains(target.getUniqueId())) {
                sender.sendMessage(ChatColor.RED + "Animation sedang berjalan untuk player tersebut.");
                return true;
            }

            playAnimation(target, "Animatedban Test", sender, false);
            sender.sendMessage(ChatColor.GREEN + "Animatedban test started untuk " + target.getName() + ".");
            return true;
        }

        if (!command.getName().equalsIgnoreCase("banhammer")) return false;

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /banhammer <player> [reason]");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player tidak online.");
            return true;
        }
        if (target.hasPermission("animatedban.bypass")) {
            sender.sendMessage(ChatColor.RED + "Player tersebut memiliki animatedban.bypass.");
            return true;
        }
        if (activeAnimations.contains(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "Animation sedang berjalan untuk player tersebut.");
            return true;
        }

        String reason = args.length >= 2
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "Banned by Animatedban";

        playAnimation(target, reason, sender, true);
        return true;
    }

    private void playAnimation(Player target, String reason, CommandSender sender, boolean banAfterAnimation) {
        final World world = target.getWorld();
        final Location anchor = target.getLocation().clone();
        final UUID targetId = target.getUniqueId();

        activeAnimations.add(targetId);
        frozenPlayers.add(targetId);
        target.setInvulnerable(true);
        target.setVelocity(new Vector(0, 0, 0));

        new BukkitRunnable() {
            int tick = 0;
            final List<Entity> spawned = new ArrayList<>();
            final List<ItemDisplay> bones = new ArrayList<>();
            ItemDisplay hammer;
            TextDisplay bannedText;

            @Override
            public void run() {
                if (!target.isOnline() || target.getWorld() != world) {
                    cleanup();
                    finishState();
                    cancel();
                    return;
                }

                Location center = anchor.clone();
                center.setYaw(target.getLocation().getYaw());
                center.setPitch(target.getLocation().getPitch());

                // Freeze the player at the original position while allowing head rotation.
                if (tick <= 35) {
                    Location locked = anchor.clone();
                    locked.setYaw(target.getLocation().getYaw());
                    locked.setPitch(target.getLocation().getPitch());
                    target.teleport(locked);
                    target.setVelocity(new Vector(0, 0, 0));
                    center = locked;
                }

                // The mace starts high above the player and falls in a perfectly straight line.
                if (tick == 0) {
                    hammer = spawnMace(center.clone().add(0, 11.0, 0));
                    spawned.add(hammer);
                    world.playSound(center, Sound.ENTITY_BREEZE_CHARGE, 0.65f, 0.55f);
                    world.playSound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.15f, 1.8f);
                }

                if (tick <= 28 && hammer != null) {
                    double t = tick / 28.0;
                    double y = 11.0 - (11.0 * t * t);
                    Location hammerLoc = center.clone().add(0, y, 0);
                    hammer.teleport(hammerLoc);
                    // No horizontal spin: it drops like a giant hammer/mace straight down.
                    setHammerVertical(hammer);

                    if (tick >= 8 && tick % 2 == 0) {
                        world.spawnParticle(Particle.CLOUD, hammerLoc, 7, .16, .10, .16, .02);
                        world.spawnParticle(Particle.CRIT, hammerLoc, 3, .10, .10, .10, .03);
                    }
                }

                // BOOM: impact, explosion flash, smoke and bone fragments.
                if (tick == 29) {
                    if (hammer != null) hammer.teleport(center.clone().add(0, 0.25, 0));
                    impact(world, center);

                    for (int i = 0; i < 18; i++) {
                        double angle = (Math.PI * 2.0 * i) / 18.0;
                        double speed = 0.22 + (i % 5) * 0.045;
                        ItemDisplay bone = spawnImpactBone(center.clone().add(0, .65 + (i % 3) * .12, 0), i);
                        bones.add(bone);
                        spawned.add(bone);

                        Vector velocity = new Vector(
                                Math.cos(angle) * speed,
                                0.22 + (i % 4) * 0.065,
                                Math.sin(angle) * speed
                        );
                        bone.setVelocity(velocity);
                    }

                    bannedText = spawnBannedText(center.clone().add(0, 3.0, 0));
                    spawned.add(bannedText);

                    world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.65f);
                    world.playSound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.7f, 0.5f);
                }

                // Bones fly outward, tumble and slowly fall.
                if (tick >= 30 && tick <= 100) {
                    for (int i = 0; i < bones.size(); i++) {
                        ItemDisplay bone = bones.get(i);
                        Vector velocity = bone.getVelocity();
                        double drag = tick < 62 ? 0.93 : 0.84;
                        bone.setVelocity(new Vector(
                                velocity.getX() * drag,
                                velocity.getY() * 0.94 - 0.012,
                                velocity.getZ() * drag
                        ));
                        bone.setRotation((tick * (11.0f + i * 2.2f)) % 360f, (tick * (7.0f + i)) % 360f);
                    }

                    if (bannedText != null) {
                        double bob = Math.sin((tick - 29) * 0.14) * 0.10;
                        bannedText.teleport(center.clone().add(0, 3.0 + bob, 0));
                    }

                    if (tick % 2 == 0) {
                        world.spawnParticle(Particle.CRIT, center.clone().add(0, .8, 0), 9, .9, .55, .9, .08);
                        world.spawnParticle(Particle.CLOUD, center.clone().add(0, .65, 0), 5, .7, .25, .7, .025);
                    }
                }

                if (tick >= 101 && tick <= 115 && tick % 2 == 0) {
                    world.spawnParticle(Particle.CLOUD, center.clone().add(0, .8, 0), 12, .85, .5, .85, .04);
                    world.spawnParticle(Particle.SOUL, center.clone().add(0, 1.0, 0), 5, .5, .35, .5, .02);
                }

                // Remove every animation entity/effect first, then ban OR finish test.
                if (tick == 116) {
                    cleanup();
                    finishState();

                    if (banAfterAnimation) {
                        Bukkit.getBanList(BanList.Type.NAME).addBan(
                                target.getName(), reason, null, sender.getName());
                        target.kickPlayer(ChatColor.DARK_RED + "" + ChatColor.BOLD + "BANNED"
                                + ChatColor.RESET + "\n" + ChatColor.RED + reason);
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Animatedban test finished.");
                    }

                    cancel();
                }

                tick++;
            }

            private void finishState() {
                activeAnimations.remove(targetId);
                frozenPlayers.remove(targetId);
                if (target.isOnline()) {
                    target.setInvulnerable(false);
                    target.setVelocity(new Vector(0, 0, 0));
                }
            }

            private void cleanup() {
                for (Entity entity : spawned) {
                    if (entity != null && !entity.isDead()) entity.remove();
                }
                spawned.clear();
                bones.clear();
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private ItemDisplay spawnMace(Location loc) {
        ItemDisplay display = loc.getWorld().spawn(loc, ItemDisplay.class);
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        meta.getPersistentDataContainer().set(animKey, PersistentDataType.BYTE, (byte) 1);
        mace.setItemMeta(meta);
        display.setItemStack(mace);
        display.setBillboard(Display.Billboard.FIXED);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setInterpolationDuration(1);
        setHammerVertical(display);
        return display;
    }

    private void setHammerVertical(ItemDisplay display) {
        display.setTransformation(new Transformation(
                new Vector3f(-0.15f, -0.15f, -0.15f),
                new Quaternionf(),
                new Vector3f(1.75f, 1.75f, 1.75f),
                new Quaternionf()
        ));
    }

    private ItemDisplay spawnImpactBone(Location loc, int index) {
        ItemDisplay display = loc.getWorld().spawn(loc, ItemDisplay.class);
        display.setItemStack(new ItemStack(Material.BONE));
        display.setBillboard(Display.Billboard.FIXED);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setInterpolationDuration(1);
        float scale = 0.9f + (index % 3) * 0.12f;
        display.setTransformation(new Transformation(
                new Vector3f(-0.5f, -0.5f, -0.05f),
                new Quaternionf().rotateZ((float) Math.toRadians(90)),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        ));
        return display;
    }

    private TextDisplay spawnBannedText(Location loc) {
        TextDisplay display = loc.getWorld().spawn(loc, TextDisplay.class);
        display.setText(ChatColor.RED + "" + ChatColor.BOLD + "BANNED");
        display.setBillboard(Display.Billboard.CENTER);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setDefaultBackground(false);
        display.setShadowed(true);
        display.setSeeThrough(false);
        display.setLineWidth(200);
        display.setTextOpacity((byte) 255);
        display.setTransformation(new Transformation(
                new Vector3f(-0.5f, 0, 0),
                new Quaternionf(),
                new Vector3f(2.6f, 2.6f, 2.6f),
                new Quaternionf()
        ));
        return display;
    }

    private void impact(World world, Location loc) {
        Location blast = loc.clone().add(0, 0.2, 0);

        world.playSound(loc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 2.0f, 0.7f);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.65f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, blast, 2, .15, .15, .15, 0);
        world.spawnParticle(Particle.FLASH, blast, 2, .1, .1, .1, 0);
        world.spawnParticle(Particle.EXPLOSION, blast, 12, .5, .3, .5, .02);
        world.spawnParticle(Particle.CRIT, loc.clone().add(0, .8, 0), 110, 1.0, .75, 1.0, .20);
        world.spawnParticle(Particle.BLOCK, loc.clone().add(0, .15, 0), 120,
                1.0, .20, 1.0, .0, Material.BONE_BLOCK.createBlockData());
        world.spawnParticle(Particle.CLOUD, loc.clone().add(0, .6, 0), 55, .9, .5, .9, .10);
        world.spawnParticle(Particle.SOUL, loc.clone().add(0, 1.0, 0), 30, .8, .8, .8, .05);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!frozenPlayers.contains(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // Position is locked; head rotation remains available.
        to.setX(from.getX());
        to.setY(from.getY());
        to.setZ(from.getZ());
        event.setTo(to);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("bn")) {
            if (args.length == 1) return List.of("test");
            if (args.length == 2) {
                String prefix = args[1].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(prefix))
                        .toList();
            }
        }
        return List.of();
    }
}
