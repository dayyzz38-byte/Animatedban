package id.animatedban;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Display;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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

public final class AnimatedBanPlugin extends JavaPlugin implements Listener {

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Set<UUID> activeAnimations = new HashSet<>();

    private NamespacedKey animationKey;

    @Override
    public void onEnable() {
        animationKey = new NamespacedKey(this, "animatedban");

        getServer()
                .getPluginManager()
                .registerEvents(this, this);

        getLogger().info("Animatedban enabled.");
    }

    @Override
    public void onDisable() {
        frozenPlayers.clear();
        activeAnimations.clear();

        getLogger().info("Animatedban disabled.");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!command.getName().equalsIgnoreCase("banhammer")) {
            return false;
        }

        if (!sender.hasPermission("animatedban.use")) {
            sender.sendMessage(
                    ChatColor.RED + "You don't have permission to use this command."
            );
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(
                    ChatColor.YELLOW
                            + "Usage: /banhammer <player> [reason]"
            );
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);

        if (target == null) {
            sender.sendMessage(
                    ChatColor.RED + "Player tidak online."
            );
            return true;
        }

        if (target.hasPermission("animatedban.bypass")) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Player tersebut memiliki animatedban.bypass."
            );
            return true;
        }

        if (activeAnimations.contains(target.getUniqueId())) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Animatedban sedang berjalan pada player tersebut."
            );
            return true;
        }

        String reason;

        if (args.length >= 2) {
            reason = String.join(
                    " ",
                    Arrays.copyOfRange(args, 1, args.length)
            );
        } else {
            reason = "Banned by Animatedban";
        }

        playAnimation(target, reason, sender);

        return true;
    }

    private void playAnimation(
            Player target,
            String reason,
            CommandSender sender
    ) {

        final World world = target.getWorld();

        final Location anchor =
                target.getLocation().clone();

        final float fixedYaw =
                anchor.getYaw();

        final float fixedPitch =
                anchor.getPitch();

        final UUID uuid =
                target.getUniqueId();

        activeAnimations.add(uuid);
        frozenPlayers.add(uuid);

        target.setInvulnerable(true);
        target.setVelocity(new Vector(0, 0, 0));

        new BukkitRunnable() {

            int tick = 0;

            final List<Entity> spawnedEntities =
                    new ArrayList<>();

            final List<ItemDisplay> bones =
                    new ArrayList<>();

            ItemDisplay hammer;
            TextDisplay logo;

            @Override
            public void run() {

                if (!target.isOnline()
                        || target.getWorld() != world) {

                    cleanup();

                    frozenPlayers.remove(uuid);
                    activeAnimations.remove(uuid);

                    target.setInvulnerable(false);

                    cancel();
                    return;
                }

                Location center =
                        anchor.clone();

                center.setYaw(fixedYaw);
                center.setPitch(fixedPitch);

                /*
                 * FREEZE PLAYER
                 */
                if (tick <= 35) {
                    target.teleport(center);
                    target.setVelocity(
                            new Vector(0, 0, 0)
                    );
                }

                /*
                 * =========================
                 * PHASE 1
                 * MACE APPEARS
                 * =========================
                 */
                if (tick == 0) {

                    hammer =
                            spawnMace(
                                    center.clone()
                                            .add(0, 11.0, 0)
                            );

                    spawnedEntities.add(hammer);

                    world.playSound(
                            center,
                            Sound.ITEM_MACE_SMASH_GROUND_HEAVY,
                            0.15f,
                            1.8f
                    );

                    world.playSound(
                            center,
                            Sound.ENTITY_BREEZE_CHARGE,
                            0.45f,
                            0.65f
                    );
                }

                /*
                 * =========================
                 * PHASE 2
                 * MACE FALL
                 * =========================
                 */
                if (tick <= 28 && hammer != null) {

                    double progress =
                            tick / 28.0;

                    /*
                     * Accelerating fall.
                     */
                    double y =
                            11.0
                                    - (11.0
                                    * progress
                                    * progress);

                    Location hammerLocation =
                            center.clone()
                                    .add(0, y, 0);

                    hammer.teleport(
                            hammerLocation
                    );

                    rotateHammer(
                            hammer,
                            tick * 0.30f
                    );

                    /*
                     * Falling air particles.
                     */
                    if (tick > 8
                            && tick % 2 == 0) {

                        world.spawnParticle(
                                Particle.CLOUD,
                                hammerLocation,
                                5,
                                0.22,
                                0.12,
                                0.22,
                                0.025
                        );
                    }
                }

                /*
                 * =========================
                 * PHASE 3
                 * IMPACT
                 * =========================
                 */
                if (tick == 29) {

                    if (hammer != null) {

                        hammer.teleport(
                                center.clone()
                                        .add(0, 0.35, 0)
                        );
                    }

                    impact(
                            world,
                            center
                    );

                    /*
                     * Bone debris.
                     */
                    for (int i = 0; i < 12; i++) {

                        double angle =
                                (Math.PI * 2.0 * i)
                                        / 12.0;

                        double speed =
                                0.16
                                        + (i % 4)
                                        * 0.035;

                        ItemDisplay bone =
                                spawnImpactBone(
                                        center.clone()
                                                .add(0, 0.75, 0),
                                        i
                                );

                        bones.add(bone);
                        spawnedEntities.add(bone);

                        Vector velocity =
                                new Vector(
                                        Math.cos(angle)
                                                * speed,

                                        0.16
                                                + (i % 3)
                                                * 0.055,

                                        Math.sin(angle)
                                                * speed
                                );

                        bone.setVelocity(
                                velocity
                        );
                    }

                    /*
                     * Skull / X logo.
                     */
                    logo =
                            spawnLogo(
                                    center.clone()
                                            .add(0, 3.25, 0)
                            );

                    spawnedEntities.add(logo);

                    world.playSound(
                            center,
                            Sound.ENTITY_GENERIC_EXPLODE,
                            1.0f,
                            0.75f
                    );

                    world.playSound(
                            center,
                            Sound.ENTITY_SKELETON_DEATH,
                            1.0f,
                            0.7f
                    );
                }

                /*
                 * =========================
                 * PHASE 4
                 * DEBRIS + LOGO
                 * =========================
                 */
                if (tick >= 30
                        && tick <= 100) {

                    for (int i = 0;
                         i < bones.size();
                         i++) {

                        ItemDisplay bone =
                                bones.get(i);

                        Vector velocity =
                                bone.getVelocity();

                        if (tick < 60) {

                            bone.setVelocity(
                                    velocity.multiply(0.91)
                            );

                        } else {

                            bone.setVelocity(
                                    velocity.multiply(0.82)
                            );
                        }

                        bone.setRotation(
                                (tick
                                        * (0.15f
                                        + i * 0.018f))
                                        % 360f,
                                0
                        );
                    }

                    if (logo != null) {

                        float spin =
                                (tick - 29)
                                        * 0.15f;

                        logo.teleport(
                                center.clone()
                                        .add(
                                                0,
                                                3.25
                                                        + Math.sin(spin)
                                                        * 0.12,
                                                0
                                        )
                        );

                        logo.setRotation(
                                (float)
                                        Math.toDegrees(spin),
                                0
                        );
                    }

                    /*
                     * Impact particles.
                     */
                    if (tick % 3 == 0) {

                        world.spawnParticle(
                                Particle.CRIT,
                                center.clone()
                                        .add(0, 0.9, 0),
                                7,
                                0.8,
                                0.55,
                                0.8,
                                0.06
                        );
                    }
                }

                /*
                 * =========================
                 * PHASE 5
                 * FINAL SMOKE
                 * =========================
                 */
                if (tick >= 101
                        && tick <= 115
                        && tick % 2 == 0) {

                    world.spawnParticle(
                            Particle.CLOUD,
                            center.clone()
                                    .add(0, 0.8, 0),
                            10,
                            0.8,
                            0.5,
                            0.8,
                            0.035
                    );

                    world.spawnParticle(
                            Particle.SOUL,
                            center.clone()
                                    .add(0, 1.0, 0),
                            4,
                            0.5,
                            0.35,
                            0.5,
                            0.02
                    );
                }

                /*
                 * =========================
                 * PHASE 6
                 * CLEANUP + BAN
                 * =========================
                 */
                if (tick == 116) {

                    cleanup();

                    frozenPlayers.remove(uuid);
                    activeAnimations.remove(uuid);

                    target.setGlowing(false);
                    target.setInvulnerable(false);

                    Bukkit.getBanList(
                            BanList.Type.NAME
                    ).addBan(
                            target.getName(),
                            reason,
                            null,
                            sender.getName()
                    );

                    target.kickPlayer(
                            ChatColor.DARK_RED
                                    + ChatColor.BOLD.toString()
                                    + "BANNED"
                                    + ChatColor.RESET
                                    + "\n"
                                    + ChatColor.RED
                                    + reason
                    );

                    cancel();
                    return;
                }

                tick++;
            }

            private void cleanup() {

                for (Entity entity :
                        spawnedEntities) {

                    if (entity != null
                            && !entity.isDead()) {

                        entity.remove();
                    }
                }

                spawnedEntities.clear();
                bones.clear();
            }

        }.runTaskTimer(
                this,
                0L,
                1L
        );
    }

    /*
     * =========================
     * SPAWN MACE
     * =========================
     */
    private ItemDisplay spawnMace(
            Location location
    ) {

        ItemDisplay display =
                location.getWorld()
                        .spawn(
                                location,
                                ItemDisplay.class
                        );

        ItemStack mace =
                new ItemStack(Material.MACE);

        ItemMeta meta =
                mace.getItemMeta();

        if (meta != null) {

            meta.getPersistentDataContainer()
                    .set(
                            animationKey,
                            PersistentDataType.BYTE,
                            (byte) 1
                    );

            mace.setItemMeta(meta);
        }

        display.setItemStack(mace);

        display.setBillboard(
                Display.Billboard.FIXED
        );

        display.setBrightness(
                new Display.Brightness(
                        15,
                        15
                )
        );

        display.setInterpolationDuration(2);

        display.setTransformation(
                new Transformation(
                        new Vector3f(
                                -0.15f,
                                -0.15f,
                                -0.15f
                        ),

                        new Quaternionf(),

                        new Vector3f(
                                1.65f,
                                1.65f,
                                1.65f
                        ),

                        new Quaternionf()
                )
        );

        return display;
    }

    /*
     * =========================
     * ROTATE MACE
     * =========================
     */
    private void rotateHammer(
            ItemDisplay display,
            float angle
    ) {

        display.setTransformation(
                new Transformation(
                        new Vector3f(
                                -0.15f,
                                -0.15f,
                                -0.15f
                        ),

                        new Quaternionf()
                                .rotateY(angle),

                        new Vector3f(
                                1.65f,
                                1.65f,
                                1.65f
                        ),

                        new Quaternionf()
                )
        );
    }

    /*
     * =========================
     * SPAWN BONE
     * =========================
     */
    private ItemDisplay spawnImpactBone(
            Location location,
            int index
    ) {

        ItemDisplay display =
                location.getWorld()
                        .spawn(
                                location,
                                ItemDisplay.class
                        );

        display.setItemStack(
                new ItemStack(Material.BONE)
        );

        display.setBillboard(
                Display.Billboard.FIXED
        );

        display.setBrightness(
                new Display.Brightness(
                        15,
                        15
                )
        );

        display.setInterpolationDuration(1);

        float scale = 0.95f;

        display.setTransformation(
                new Transformation(
                        new Vector3f(
                                -0.5f,
                                -0.5f,
                                -0.05f
                        ),

                        new Quaternionf()
                                .rotateZ(
                                        (float)
                                                Math.toRadians(90)
                                ),

                        new Vector3f(
                                scale,
                                scale,
                                scale
                        ),

                        new Quaternionf()
                )
        );

        return display;
    }

    /*
     * =========================
     * SPAWN LOGO
     * =========================
     */
    private TextDisplay spawnLogo(
            Location location
    ) {

        TextDisplay display =
                location.getWorld()
                        .spawn(
                                location,
                                TextDisplay.class
                        );

        display.setText(
                ChatColor.DARK_RED
                        + "☠"
                        + ChatColor.GRAY
                        + "  X"
        );

        display.setBillboard(
                Display.Billboard.CENTER
        );

        display.setAlignment(
                TextDisplay.TextAlignment.CENTER
        );

        display.setBackgroundColor(
                Color.fromARGB(
                        0,
                        0,
                        0,
                        0
                )
        );

        display.setDefaultBackground(false);

        display.setShadowed(true);

        display.setSeeThrough(false);

        display.setLineWidth(200);

        display.setTextOpacity(
                (byte) 255
        );

        display.setTransformation(
                new Transformation(
                        new Vector3f(
                                -0.5f,
                                0,
                                0
                        ),

                        new Quaternionf(),

                        new Vector3f(
                                2.3f,
                                2.3f,
                                2.3f
                        ),

                        new Quaternionf()
                )
        );

        return display;
    }

    /*
     * =========================
     * PLAYER FREEZE
     * =========================
     */
    @EventHandler
    public void onPlayerMove(
            PlayerMoveEvent event
    ) {

        Player player =
                event.getPlayer();

        if (!frozenPlayers.contains(
                player.getUniqueId()
        )) {
            return;
        }

        Location from =
                event.getFrom();

        Location to =
                event.getTo();

        if (to == null) {
            return;
        }

        /*
         * Block position changes.
         * Allow normal head rotation.
         */
        to.setX(from.getX());
        to.setY(from.getY());
        to.setZ(from.getZ());

        event.setTo(to);
    }

    /*
     * =========================
     * IMPACT EFFECT
     * =========================
     */
    private void impact(
            World world,
            Location location
    ) {

        world.playSound(
                location,
                Sound.ITEM_MACE_SMASH_GROUND_HEAVY,
                1.4f,
                0.8f
        );

        world.playSound(
                location,
                Sound.ENTITY_GENERIC_EXPLODE,
                0.7f,
                1.2f
        );

        world.spawnParticle(
                Particle.EXPLOSION_EMITTER,
                location.clone()
                        .add(0, 0.2, 0),
                1
        );

        world.spawnParticle(
                Particle.CRIT,
                location.clone()
                        .add(0, 0.8, 0),
                80,
                0.8,
                0.8,
                0.8,
                0.15
        );

        world.spawnParticle(
                Particle.BLOCK,
                location.clone()
                        .add(0, 0.15, 0),
                80,
                0.8,
                0.15,
                0.8,
                Material.BONE_BLOCK
                        .createBlockData()
        );

        world.spawnParticle(
                Particle.CLOUD,
                location.clone()
                        .add(0, 0.5, 0),
                35,
                0.8,
                0.4,
                0.8,
                0.08
        );

        world.spawnParticle(
                Particle.SOUL,
                location.clone()
                        .add(0, 1, 0),
                25,
                0.8,
                0.8,
                0.8,
                0.04
        );
    }
  }
