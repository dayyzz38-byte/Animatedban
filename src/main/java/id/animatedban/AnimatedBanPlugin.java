package id.animatedban;

import org.bukkit.*;
import org.bukkit.NamespacedKey;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class AnimatedBanPlugin extends JavaPlugin implements Listener {
    private final java.util.Set<java.util.UUID> frozenPlayers = new java.util.HashSet<>();
    private NamespacedKey animKey;

    @Override
    public void onEnable() {
        animKey = new NamespacedKey(this, "animatedban");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Animatedban enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bn")) {
            if (!sender.hasPermission("animatedban.use")) {
                sender.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }
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
            playAnimation(target, "Animatedban Test", sender, false);
            sender.sendMessage(ChatColor.GREEN + "Animatedban test started for " + target.getName());
            return true;
        }

        if (!command.getName().equalsIgnoreCase("banhammer")) return false;
        if (!sender.hasPermission("animatedban.use")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
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

        String reason = args.length >= 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : "Banned by Animatedban";

        playAnimation(target, reason, sender, true);
        return true;
    }

    private void playAnimation(Player target, String reason, CommandSender sender, boolean banAfterAnimation) {
        final World world = target.getWorld();
        final Location anchor = target.getLocation().clone();
        final double groundY = anchor.getY();
        final float fixedYaw = anchor.getYaw();

        // Freeze movement without changing the player's position every tick.
        // AI/client movement is blocked by the plugin's own movement listener state.
        frozenPlayers.add(target.getUniqueId());
        target.setInvulnerable(true);
        target.setVelocity(new Vector(0, 0, 0));

        new BukkitRunnable() {
            int tick = 0;
            final List<Entity> spawned = new ArrayList<>();
            ItemDisplay hammer;
            final List<ItemDisplay> bones = new ArrayList<>();
            TextDisplay logo;

            @Override
            public void run() {
                if (!target.isOnline() || target.getWorld() != world) {
                    cleanup();
                    frozenPlayers.remove(target.getUniqueId());
                    target.setInvulnerable(false);
                    cancel();
                    return;
                }

                // Re-anchor the target so the entire animation stays exactly on the player.
                Location playerLoc = target.getLocation();
                Location center = anchor.clone();
                center.setYaw(fixedYaw);
                center.setPitch(0);

                if (tick <= 35) {
                    target.teleport(center);
                    target.setVelocity(new Vector(0, 0, 0));
                }

                // 0: huge mace appears 10 blocks above the player's head.
                if (tick == 0) {
                    hammer = spawnMace(center.clone().add(0, 11.0, 0));
                    spawned.add(hammer);
                    world.playSound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, .15f, 1.8f);
                    world.playSound(center, Sound.ENTITY_BREEZE_CHARGE, .45f, .65f);
                }

                // 0-28: mace drops straight down, keeping its impact point centered.
                if (tick <= 28 && hammer != null) {
                    double t = tick / 28.0;
                    // Smooth acceleration: slow start, very fast final descent.
                    double y = 11.0 - (11.0 * t * t);
                    hammer.teleport(center.clone().add(0, y, 0));
                    rotateHammer(hammer, tick * 0.30f);

                    if (tick > 8 && tick % 2 == 0) {
                        world.spawnParticle(Particle.CLOUD,
                                center.clone().add(0, y, 0), 5, .22, .12, .22, .025);
                    }
                }

                // Impact: mace hits the exact center of the player.
                if (tick == 29) {
                    if (hammer != null) hammer.teleport(center.clone().add(0, 0.35, 0));
                    impact(world, center);

                    // Scatter broken bones outward from the player.
                    for (int i = 0; i < 12; i++) {
                        double angle = (Math.PI * 2.0 * i) / 12.0;
                        double speed = .16 + (i % 4) * .035;
                        ItemDisplay bone = spawnImpactBone(center.clone().add(0, .75, 0), i);
                        bones.add(bone);
                        spawned.add(bone);

                        Vector v = new Vector(
                                Math.cos(angle) * speed,
                                .16 + (i % 3) * .055,
                                Math.sin(angle) * speed
                        );
                        bone.setVelocity(v);
                    }

                    logo = spawnLogo(center.clone().add(0, 3.25, 0));
                    spawned.add(logo);

                    world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, .75f);
                    world.playSound(center, Sound.ENTITY_SKELETON_DEATH, 1.0f, .7f);
                }

                // 30-100: bones fly outward, tumble, then settle briefly.
                if (tick >= 30 && tick <= 100) {
                    for (int i = 0; i < bones.size(); i++) {
                        ItemDisplay bone = bones.get(i);
                        Vector vel = bone.getVelocity();
                        if (tick < 60) {
                            bone.setVelocity(vel.multiply(.91));
                        } else {
                            bone.setVelocity(vel.multiply(.82));
                        }
                        bone.setRotation((tick * (0.15f + i * .018f)) % 360f, 0);
                    }

                    if (logo != null) {
                        float spin = (tick - 29) * .15f;
                        logo.teleport(center.clone().add(
                                0, 3.25 + Math.sin(spin) * .12, 0));
                        logo.setRotation((float)Math.toDegrees(spin), 0);
                    }

                    if (tick % 3 == 0) {
                        world.spawnParticle(Particle.CRIT,
                                center.clone().add(0, .9, 0), 7, .8, .55, .8, .06);
                    }
                }

                // 101-115: final smoke/debris phase.
                if (tick >= 101 && tick <= 115 && tick % 2 == 0) {
                    world.spawnParticle(Particle.CLOUD,
                            center.clone().add(0, .8, 0), 10, .8, .5, .8, .035);
                    world.spawnParticle(Particle.SOUL,
                            center.clone().add(0, 1.0, 0), 4, .5, .35, .5, .02);
                }

                // Remove ALL animation entities/effects, then ban.
                if (tick == 116) {
                    cleanup();
                    frozenPlayers.remove(target.getUniqueId());
                    target.setGlowing(false);
                    target.setInvulnerable(false);

                    if (!banAfterAnimation) {
                        target.sendMessage(ChatColor.GREEN + "Animatedban test finished.");
                        cancel();
                        return;
                    }

                    Bukkit.getBanList(BanList.Type.NAME).addBan(
                            target.getName(), reason, null, sender.getName());

                    target.kickPlayer(ChatColor.DARK_RED + "" + ChatColor.BOLD + "BANNED"
                            + ChatColor.RESET + "\n" + ChatColor.RED + reason);
                    cancel();
                }

                tick++;
            }

            private void cleanup() {
                for (Entity e : spawned) {
                    if (e != null && !e.isDead()) e.remove();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private ItemDisplay spawnMace(Location loc) {
        ItemDisplay d = loc.getWorld().spawn(loc, ItemDisplay.class);
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        meta.getPersistentDataContainer().set(animKey, PersistentDataType.BYTE, (byte)1);
        mace.setItemMeta(meta);
        d.setItemStack(mace);
        d.setBillboard(Display.Billboard.FIXED);
        d.setBrightness(new Display.Brightness(15, 15));
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(
                new Vector3f(-0.15f, -0.15f, -0.15f),
                new Quaternionf(),
                new Vector3f(1.65f, 1.65f, 1.65f),
                new Quaternionf()
        ));
        return d;
    }

    private void rotateHammer(ItemDisplay d, float angle) {
        d.setTransformation(new Transformation(
                new Vector3f(-0.15f, -0.15f, -0.15f),
                new Quaternionf().rotateY(angle),
                new Vector3f(1.65f, 1.65f, 1.65f),
                new Quaternionf()
        ));
    }

    private ArmorStand spawnSkull(Location loc, Player target) {
        ArmorStand a = loc.getWorld().spawn(loc, ArmorStand.class);
        a.setInvisible(true);
        a.setMarker(true);
        a.setSmall(false);
        a.setGravity(false);
        a.setHelmet(skullItem(target));
        return a;
    }

    private ItemStack skullItem(Player target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(target);
        head.setItemMeta(meta);
        return head;
    }

    private ItemDisplay spawnImpactBone(Location loc, int index) {
        ItemDisplay d = loc.getWorld().spawn(loc, ItemDisplay.class);
        d.setItemStack(new ItemStack(Material.BONE));
        d.setBillboard(Display.Billboard.FIXED);
        d.setBrightness(new Display.Brightness(15, 15));
        d.setInterpolationDuration(1);

        float scale = 0.95f;
        d.setTransformation(new Transformation(
                new Vector3f(-0.5f, -0.5f, -0.05f),
                new Quaternionf().rotateZ((float)Math.toRadians(90)),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        ));
        return d;
    }


    private TextDisplay spawnLogo(Location loc) {
        TextDisplay t = loc.getWorld().spawn(loc, TextDisplay.class);
        t.setText(ChatColor.DARK_RED + "☠" + ChatColor.GRAY + "  X");
        t.setBillboard(Display.Billboard.CENTER);
        t.setAlignment(TextDisplay.TextAlignment.CENTER);
        t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        t.setDefaultBackground(false);
        t.setShadowed(true);
        t.setSeeThrough(false);
        t.setLineWidth(200);
        t.setTextOpacity((byte)255);
        t.setTransformation(new Transformation(
                new Vector3f(-0.5f, 0, 0),
                new Quaternionf(),
                new Vector3f(2.3f, 2.3f, 2.3f),
                new Quaternionf()
        ));
        return t;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!frozenPlayers.contains(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // Allow head rotation only; block all positional movement during animation.
        to.setX(from.getX());
        to.setY(from.getY());
        to.setZ(from.getZ());
        event.setTo(to);
    }


    private void impact(World world, Location loc) {
        world.playSound(loc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.8f);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, .7f, 1.2f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc.clone().add(0, .2, 0), 1);
        world.spawnParticle(Particle.CRIT, loc.clone().add(0, .8, 0), 80, .8, .8, .8, .15);
        world.spawnParticle(Particle.BLOCK, loc.clone().add(0, .15, 0), 80,
                .8, .15, .8, Material.BONE_BLOCK.createBlockData());
        world.spawnParticle(Particle.CLOUD, loc.clone().add(0, .5, 0), 35, .8, .4, .8, .08);
        world.spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 25, .8, .8, .8, .04);
    }
}
