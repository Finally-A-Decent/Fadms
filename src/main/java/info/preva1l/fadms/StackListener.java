package info.preva1l.fadms;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractCubeMob;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class StackListener implements Listener {
    private final Fadms plugin;
    private final NamespacedKey stackKey;
    private final NamespacedKey fromSpawnerKey;
    private final NamespacedKey playerPlacedKey;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Set<String> wholeStackDamageTypes;

    StackListener(Fadms plugin) {
        this.plugin = plugin;
        this.stackKey = new NamespacedKey(plugin, "stack-size");
        this.fromSpawnerKey = new NamespacedKey(plugin, "from-spawner");
        this.playerPlacedKey = new NamespacedKey(plugin, "player-placed");
        this.wholeStackDamageTypes = resolveWholeStackDamageTypes();
    }

    private FadmsConfig settings() {
        return plugin.settings();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerPlace(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof CreatureSpawner spawner)) return;
        spawner.getPersistentDataContainer().set(playerPlacedKey, PersistentDataType.BYTE, (byte) 1);
        spawner.update();
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        CreatureSpawner spawner = event.getSpawner();
        if (spawner == null || !(event.getEntity() instanceof Mob entity)) return;
        FadmsConfig.Spawner config = settings().spawner;
        if (config.onlyPlayerPlaced && !spawner.getPersistentDataContainer().has(playerPlacedKey)) return;

        double range = spawner.getSpawnRange();
        tryStack(event, entity, spawner.getLocation(), range * config.searchRangeHorizontal, range * config.searchRangeVertical, true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        FadmsConfig.AllMobs config = settings().allMobs;
        if (!config.enabled || config.ignoredSpawnReasons.contains(event.getSpawnReason())) return;
        // spawner spawns fire SpawnerSpawnEvent first, which has already tagged or cancelled them
        if (!(event.getEntity() instanceof Mob entity) || stackSize(entity) > 0) return;

        tryStack(event, entity, entity.getLocation(), config.searchRadiusHorizontal, config.searchRadiusVertical, false);
    }

    private void tryStack(EntitySpawnEvent event, Mob entity, Location center, double horizontal, double vertical, boolean fromSpawner) {
        if (entity.isInsideVehicle() || !entity.getPassengers().isEmpty()) return;

        Mob stack = center.getNearbyEntitiesByType(Mob.class, horizontal, vertical).stream()
            .filter(candidate -> candidate.getType() == entity.getType() && candidate.isValid() && !candidate.isInsideVehicle())
            .filter(candidate -> {
                int size = stackSize(candidate);
                return size > 0 && size < settings().maxStackSize;
            })
            .findFirst()
            .orElse(null);

        if (stack == null) {
            startStack(entity, fromSpawner);
            return;
        }

        event.setCancelled(true);
        setStackSize(stack, stackSize(stack) + 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onStackedMobAttack(EntityDamageByEntityEvent event) {
        if (settings().spawner.preventDamage && event.getDamager().getPersistentDataContainer().has(fromSpawnerKey)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCubeMobSplit(SlimeSplitEvent event) {
        if (!settings().cubeMobs.splitting && stackSize(event.getEntity()) > 0) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStackedMobDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob stack)) return;
        int stackSize = stackSize(stack);
        if (stackSize <= 0) return;
        Player killer = stack.getKiller();

        boolean wholeStack = wholeStackDamageTypes.contains(event.getDamageSource().getDamageType().key().asString())
            || (killer == null && settings().death.naturalDeaths == FadmsConfig.DeathMode.WHOLE_STACK);
        if (wholeStack) {
            // vanilla removes the whole stack; cube mobs copy the name onto their split children
            clearStackName(stack);
            return;
        }

        double maxHealth = stack.getAttribute(Attribute.MAX_HEALTH).getValue();
        KillResult kill = killer != null && settings().death.sweepingKills
            ? sweepKills(stack, killer, stackSize, maxHealth)
            : new KillResult(1, maxHealth);

        int newStackSize = stackSize - kill.amount();
        // the dying entity itself is handled by vanilla when the stack runs out
        int extraDeaths = newStackSize <= 0 ? kill.amount() - 1 : kill.amount();

        if (killer != null) {
            dropLoot(stack, killer, extraDeaths, event.getDroppedExp());
            if (newStackSize <= 0 && rollsAsTinySlime(stack)) event.getDrops().addAll(rollLoot(stack, killer, 1));
        }
        splitCubeMobs(stack, extraDeaths);

        if (newStackSize <= 0) {
            clearStackName(stack);
            return;
        }

        event.setCancelled(true);
        event.setReviveHealth(kill.survivorHealth());
        setStackSize(stack, newStackSize);
        if (event.shouldPlayDeathSound() && event.getDeathSound() != null) {
            stack.getWorld().playSound(
                stack.getLocation(),
                event.getDeathSound(),
                event.getDeathSoundCategory(),
                event.getDeathSoundVolume(),
                event.getDeathSoundPitch()
            );
        }
    }

    private record KillResult(int amount, double survivorHealth) {}

    /** How many mobs the killing blow takes out of the stack and the health the survivor is left with. */
    private KillResult sweepKills(Mob stack, Player killer, int stackSize, double maxHealth) {
        int remaining = stackSize - 1;
        double sweepingRatio = attributeValue(killer, Attribute.SWEEPING_DAMAGE_RATIO);
        EntityDamageEvent lastDamage = stack.getLastDamageCause();
        double baseDamage = lastDamage != null && killer.equals(lastDamage.getDamageSource().getCausingEntity())
            ? lastDamage.getFinalDamage()
            : attributeValue(killer, Attribute.ATTACK_DAMAGE);
        double sweepDamage = 1.0 + baseDamage * sweepingRatio;

        double remainingHealth = remaining * (maxHealth - sweepDamage);
        if (remainingHealth <= 0.0) return new KillResult(stackSize, maxHealth);

        // epsilon keeps FP noise on an exact multiple from counting as an extra survivor
        int survivors = (int) Math.ceil(remainingHealth / maxHealth - 1e-9);
        double survivorHealth = Math.clamp(remainingHealth - (survivors - 1) * maxHealth, 0.0, maxHealth);
        return new KillResult(stackSize - survivors, survivorHealth);
    }

    private static double attributeValue(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance != null ? instance.getValue() : 0.0;
    }

    private void dropLoot(Mob stack, Player killer, int mobs, int expPerMob) {
        if (mobs <= 0) return;
        for (ItemStack drop : rollLoot(stack, killer, mobs)) {
            stack.getWorld().dropItemNaturally(stack.getLocation(), drop);
        }

        int exp = expPerMob * mobs;
        if (exp > 0) stack.getWorld().spawn(stack.getLocation(), ExperienceOrb.class, orb -> orb.setExperience(exp));
    }

    private List<ItemStack> rollLoot(Mob stack, Player killer, int rolls) {
        Slime slime = rollsAsTinySlime(stack) ? (Slime) stack : null;
        int originalSize = slime != null ? slime.getSize() : 0;
        if (slime != null) slime.setSize(1);
        try {
            LootTable table = stack.getLootTable();
            if (table == null) return List.of();
            LootContext context = new LootContext.Builder(stack.getLocation()).lootedEntity(stack).killer(killer).build();
            Random random = ThreadLocalRandom.current();

            List<ItemStack> merged = new ArrayList<>();
            for (int i = 0; i < rolls; i++) {
                for (ItemStack item : table.populateLoot(random, context)) merge(merged, item);
            }

            List<ItemStack> drops = new ArrayList<>();
            for (ItemStack item : merged) splitToStacks(item, drops);
            return drops;
        } finally {
            if (slime != null) slime.setSize(originalSize);
        }
    }

    // Slime loot tables only pay out at size 1
    private boolean rollsAsTinySlime(Mob mob) {
        return mob instanceof Slime slime && settings().cubeMobs.alwaysDropSlimeballs && slime.getSize() > 1;
    }

    private static void merge(List<ItemStack> into, ItemStack item) {
        for (ItemStack existing : into) {
            if (existing.isSimilar(item)) {
                existing.setAmount(existing.getAmount() + item.getAmount());
                return;
            }
        }
        into.add(item.clone());
    }

    private static void splitToStacks(ItemStack item, List<ItemStack> into) {
        int remaining = item.getAmount();
        while (remaining > 0) {
            int size = Math.min(remaining, item.getMaxStackSize());
            into.add(item.asQuantity(size));
            remaining -= size;
        }
    }

    private void splitCubeMobs(Mob stack, int mobs) {
        if (!settings().cubeMobs.splitting || mobs <= 0) return;
        if (!(stack instanceof AbstractCubeMob cube) || cube.getSize() <= 1) return;

        Random random = ThreadLocalRandom.current();
        int childSize = cube.getSize() / 2;
        double spread = cube.getWidth() / 2.0;
        for (int mob = 0; mob < mobs; mob++) {
            int children = 2 + random.nextInt(3);
            for (int i = 0; i < children; i++) {
                Location location = cube.getLocation().add((i % 2 - 0.5) * spread, 0.5, (i / 2 - 0.5) * spread);
                location.setYaw(random.nextFloat() * 360f);
                cube.getWorld().spawnEntity(
                    location,
                    cube.getType(),
                    CreatureSpawnEvent.SpawnReason.SLIME_SPLIT,
                    child -> ((AbstractCubeMob) child).setSize(childSize)
                );
            }
        }
    }

    private int stackSize(Entity entity) {
        return entity.getPersistentDataContainer().getOrDefault(stackKey, PersistentDataType.INTEGER, 0);
    }

    private void startStack(Mob mob, boolean fromSpawner) {
        mob.getPersistentDataContainer().set(stackKey, PersistentDataType.INTEGER, 1);
        if (!fromSpawner) return;

        FadmsConfig.Spawner config = settings().spawner;
        mob.getPersistentDataContainer().set(fromSpawnerKey, PersistentDataType.BYTE, (byte) 1);
        if (config.forceAdult && mob instanceof Ageable ageable) ageable.setAdult();
        if (config.cubeMobSize > 0 && mob instanceof AbstractCubeMob cube) cube.setSize(config.cubeMobSize);
        if (config.disableItemPickup) mob.setCanPickupItems(false);
        if (config.disableAwareness) mob.setAware(false);
    }

    private void setStackSize(Mob stack, int size) {
        stack.getPersistentDataContainer().set(stackKey, PersistentDataType.INTEGER, size);
        if (size > 1 && settings().stack.showName) {
            stack.customName(miniMessage.deserialize(
                settings().stack.nameFormat,
                Placeholder.component("count", Component.text(size)),
                Placeholder.component("type", Component.translatable(stack.getType()))
            ));
            stack.setCustomNameVisible(true);
        } else {
            clearStackName(stack);
        }
    }

    private static void clearStackName(Mob stack) {
        stack.customName(null);
        stack.setCustomNameVisible(false);
    }

    private Set<String> resolveWholeStackDamageTypes() {
        Registry<DamageType> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE);
        Set<String> types = new HashSet<>();
        for (String raw : settings().death.wholeStackDamageTypes) {
            Key key = Key.parseable(raw) ? Key.key(raw) : null;
            if (key == null || registry.get(key) == null) {
                plugin.getLogger().warning("Ignoring unknown damage type '" + raw + "' in whole-stack-damage-types");
                continue;
            }
            types.add(key.asString());
        }
        return Set.copyOf(types);
    }
}
