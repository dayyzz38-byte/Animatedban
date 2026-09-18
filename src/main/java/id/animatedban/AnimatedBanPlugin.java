package id.animatedban;

import org.bukkit.BanList;
import org.bukkit.Particle;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AnimatedBanPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final int MACE_START_Y = 10;
    private static final int IMPACT_TICK = 38; // 1.9 seconds
    private static final int POST_BAN_TICKS = 60; // 3.0 seconds after the impact/ban
    private static final int CLEANUP_TICK = IMPACT_TICK + POST_BAN_TICKS;
    private static final double TEXT_Y = 2.55; // directly above the player's head

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Set<UUID> activeAnimations = new HashSet<>();
    private final Map<UUID, Location> frozenAnchors = new HashMap<>();
    private final Set<Entity> animationEntities = new HashSet<>();
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
    public void onDisable() {
        // Every animation is owned by its BukkitRunnable, so cancelling all plugin
        // tasks guarantees no animation loop survives a reload/shutdown.
        Bukkit.getScheduler().cancelTasks(this);
        for (Entity entity : new HashSet<>(animationEntities)) {
            if (entity != null && !entity.isDead()) entity.remove();
        }
        for (UUID id : new HashSet<>(frozenPlayers)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.setVelocity(new Vector());
                player.setFallDistance(0);
                player.setInvulnerable(false);
            }
        }
        animationEntities.clear();
        frozenAnchors.clear();
        frozenPlayers.clear();
        activeAnimations.clear();
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
        frozenAnchors.put(targetId, anchor.clone());

        target.setInvulnerable(true);
        target.setVelocity(new Vector());

        new BukkitRunnable() {
            int tick = 0;
            boolean impacted = false;
            boolean finished = false;

            final List<Entity> spawned = new ArrayList<>();
            final List<BoneFragment> bones = new ArrayList<>();

            ItemDisplay hammer;
            TextDisplay bannedText;

            @Override
            public void run() {
                if (finished) {
                    cancel();
                    return;
                }

                // During a real ban the player is kicked at impact, but the cinematic
                // must continue in the world for another 3 seconds.
                if (target.isOnline() && (target.isDead() || target.getWorld() != world)) {
                    cleanupAndFinish();
                    cancel();
                    return;
                }

                // Hard-lock while the player is still present. After a real ban the
                // player is kicked, so the text/bones are allowed to finish in place.
                if (target.isOnline()) {
                    lockPlayer(target, anchor);
                }

                if (tick == 0) {
                    Location maceSpawn = anchor.clone().add(0, MACE_START_Y, 0);
                    hammer = spawnMace(maceSpawn);
                    spawned.add(hammer);
                    animationEntities.add(hammer);

                    bannedText = spawnBannedText(anchor.clone().add(0, TEXT_Y, 0));
                    spawned.add(bannedText);
                    animationEntities.add(bannedText);

                    world.playSound(anchor, Sound.ENTITY_BREEZE_CHARGE, 0.65f, 0.55f);
                }

                // 0.0s -> 1.9s. Ease-in fall: the mace accelerates toward the anchor.
                // X/Z never change, so the strike remains perfectly vertical.
                if (tick <= IMPACT_TICK && hammer != null && !impacted) {
                    double t = tick / (double) IMPACT_TICK;
                    double y = MACE_START_Y * (1.0 - (t * t));
                    Location maceLoc = anchor.clone().add(0, y, 0);
                    hammer.teleport(maceLoc);

                    // Keep the mace perfectly straight: head down, no spin/tilt.
                    setHammerVertical(hammer);

                    // Lightweight falling trail; no explosion particle is used here.
                    if (tick >= 10 && tick % 4 == 0) {
                        world.spawnParticle(Particle.CLOUD, maceLoc, 4, .10, .08, .10, .01);
                    }
                }

                // Keep the ban text locked directly above the player. It gently
                // floats/pulses instead of spinning, so it stays readable.
                if (bannedText != null && !bannedText.isDead()) {
                    double bob = Math.sin(tick * 0.16) * 0.045;
                    float pulse = 2.55f + (float) (Math.sin(tick * 0.14) * 0.10);
                    bannedText.teleport(anchor.clone().add(0, TEXT_Y + bob, 0));
                    bannedText.setRotation(anchor.getYaw() + 180.0f, 0.0f);
                    bannedText.setTransformation(new Transformation(
                            new Vector3f(-0.5f, 0, 0),
                            new Quaternionf(),
                            new Vector3f(pulse, pulse, pulse),
                            new Quaternionf()
                    ));
                }

                // The impact gate is intentionally one-shot.
                if (tick == IMPACT_TICK && !impacted) {
                    impacted = true;

                    if (hammer != null) {
                        hammer.teleport(anchor.clone().add(0, 0.25, 0));
                        setHammerVertical(hammer);
                    }

                    impact(world, anchor);
                    spawnBones(anchor);
                }

                // Bone physics is the only repeating post-impact animation.
                // There is deliberately NO repeating impact/explosion particle code.
                if (impacted && tick > IMPACT_TICK) {
                    updateBones();

                }

                // The actual ban happens on impact. The player is then kicked while
                // the BANNED text and bone fragments remain for exactly 3 more seconds.
                if (tick == IMPACT_TICK && banAfterAnimation && target.isOnline()) {
                    Bukkit.getBanList(BanList.Type.NAME).addBan(
                            target.getName(), reason, null, sender.getName());

                    target.kickPlayer(ChatColor.DARK_RED + "" + ChatColor.BOLD + "☠ BANNED ☠"
                            + ChatColor.RESET + "\n" + ChatColor.RED + reason);
                }

                if (tick >= CLEANUP_TICK) {
                    cleanupAndFinish();

                    if (!banAfterAnimation) {
                        sender.sendMessage(ChatColor.GREEN + "Animatedban test finished.");
                    }

                    cancel();
                    return;
                }

                tick++;
            }

            private void lockPlayer(Player player, Location fixed) {
                Location locked = fixed.clone();
                player.teleport(locked);
                player.setVelocity(new Vector());
                player.setFallDistance(0);
            }

            private void spawnBones(Location center) {
                for (int i = 0; i < 22; i++) {
                    double angle = (Math.PI * 2.0 * i) / 18.0;
                    double speed = 0.22 + (i % 6) * 0.055;

                    // Radial direction means the fragments distribute around the player
                    // instead of all travelling in one direction.
                    Vector velocity = new Vector(
                            Math.cos(angle) * speed,
                            0.24 + (i % 5) * 0.065,
                            Math.sin(angle) * speed
                    );

                    Location spawn = center.clone().add(
                            0,
                            0.65 + (i % 3) * 0.10,
                            0
                    );

                    ItemDisplay display = spawnImpactBone(spawn, i);
                    spawned.add(display);
                    animationEntities.add(display);
                    bones.add(new BoneFragment(display, velocity, i));
                }
            }

            private void updateBones() {
                for (BoneFragment bone : bones) {
                    if (bone.display.isDead()) continue;

                    Vector v = bone.velocity;
                    Location p = bone.display.getLocation();

                    p.add(v);
                    v.setX(v.getX() * 0.965);
                    v.setY(v.getY() - 0.018); // gravity
                    v.setZ(v.getZ() * 0.965);

                    // Stop at the actual terrain/block surface instead of using the
                    // world's minimum Y. This prevents bones from clipping through ground.
                    Block ground = world.getBlockAt(
                            p.getBlockX(),
                            (int) Math.floor(p.getY() - 0.05),
                            p.getBlockZ()
                    );
                    if (ground.getType().isSolid() && p.getY() <= ground.getY() + 1.05) {
                        p.setY(ground.getY() + 1.05);
                        if (Math.abs(v.getY()) > 0.035) {
                            v.setY(Math.abs(v.getY()) * 0.22);
                            v.setX(v.getX() * 0.82);
                            v.setZ(v.getZ() * 0.82);
                        } else {
                            v.setY(0);
                            v.setX(v.getX() * 0.86);
                            v.setZ(v.getZ() * 0.86);
                        }
                    }

                    bone.display.teleport(p);
                    bone.display.setRotation(
                            (tick * (10.0f + bone.index * 1.7f)) % 360f,
                            (tick * (7.0f + bone.index)) % 360f
                    );
                }
            }

            private void cleanupAndFinish() {
                if (finished) return;
                finished = true;

                for (Entity entity : spawned) {
                    if (entity != null && !entity.isDead()) entity.remove();
                    animationEntities.remove(entity);
                }
                spawned.clear();
                bones.clear();

                activeAnimations.remove(targetId);
                frozenPlayers.remove(targetId);
                frozenAnchors.remove(targetId);

                if (target.isOnline()) {
                    target.setVelocity(new Vector());
                    target.setFallDistance(0);
                    target.setInvulnerable(false);
                }
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
        // Flip the normal upright mace model exactly 180 degrees on X.
        // No Y/Z rotation: the mace stays perfectly vertical and points straight down.
        Quaternionf rotation = new Quaternionf()
                .rotateX((float) Math.PI);

        display.setTransformation(new Transformation(
                new Vector3f(-0.5f, -0.5f, -0.5f),
                rotation,
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

        float scale = 0.70f + (index % 3) * 0.10f;

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

        display.setText(ChatColor.DARK_RED + "" + ChatColor.BOLD + "☠ BANNED ☠");
        display.setBillboard(Display.Billboard.FIXED);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setDefaultBackground(false);
        display.setShadowed(true);
        display.setSeeThrough(false);
        display.setLineWidth(240);
        display.setTextOpacity((byte) 255);
        display.setRotation(loc.getYaw() + 180.0f, 0.0f);

        display.setTransformation(new Transformation(
                new Vector3f(-0.5f, 0, 0),
                new Quaternionf(),
                new Vector3f(2.55f, 2.55f, 2.55f),
                new Quaternionf()
        ));

        return display;
    }

    private void impact(World world, Location loc) {
        Location hit = loc.clone().add(0, 0.20, 0);

        // All impact sounds are one-shot and use Paper 1.21.11 Bukkit Sound names.
        world.playSound(loc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 2.0f, 0.70f);
        world.playSound(loc, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.75f, 0.55f);
        world.playSound(loc, Sound.ENTITY_SKELETON_HURT, 0.80f, 0.65f);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.25f, 0.70f);

        // Single lightweight impact burst. No EXPLOSION / EXPLOSION_EMITTER / FLASH.
        world.spawnParticle(Particle.CRIT, hit.clone().add(0, 0.55, 0),
                45, 0.75, 0.45, 0.75, 0.10);
        world.spawnParticle(Particle.CLOUD, hit.clone().add(0, 0.35, 0),
                22, 0.55, 0.20, 0.55, 0.035);
        world.spawnParticle(Particle.BLOCK, hit,
                30, 0.65, 0.12, 0.65, 0.0,
                Material.BONE_BLOCK.createBlockData());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location anchor = frozenAnchors.get(player.getUniqueId());
        if (anchor == null) return;

        // Hard lock X/Y/Z + yaw/pitch. This blocks ordinary movement packets,
        // jumping and mouse-look changes without relying on a repeating teleport.
        event.setTo(anchor.clone());
        player.setVelocity(new Vector());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location anchor = frozenAnchors.get(event.getPlayer().getUniqueId());
        if (anchor == null) return;

        // Redirect all external teleports back to the cinematic anchor.
        // The animation's own teleport(anchor) is therefore also safe.
        event.setTo(anchor.clone());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerToggleSprint(PlayerToggleSprintEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().setSprinting(false);
        }
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

        if (command.getName().equalsIgnoreCase("banhammer") && args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }

        return List.of();
    }

    private static final class BoneFragment {
        private final ItemDisplay display;
        private final Vector velocity;
        private final int index;

        private BoneFragment(ItemDisplay display, Vector velocity, int index) {
            this.display = display;
            this.velocity = velocity;
            this.index = index;
        }
    }
}
