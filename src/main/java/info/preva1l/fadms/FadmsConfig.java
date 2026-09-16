package info.preva1l.fadms;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;

import java.util.List;

@Configuration
public class FadmsConfig {
    @Comment("Maximum number of mobs a single stack can hold.")
    public int maxStackSize = 50;

    public Spawner spawner = new Spawner();
    public Stack stack = new Stack();
    public CubeMobs cubeMobs = new CubeMobs();
    public Death death = new Death();

    @Configuration
    public static class Spawner {
        @Comment({
            "Only stack mobs from spawners placed by players. Spawners placed before this plugin",
            "was installed (and naturally generated ones) are treated as not player-placed.",
        })
        public boolean onlyPlayerPlaced = false;

        @Comment({
            "How far around the spawner to look for an existing stack to merge into,",
            "as a multiple of the spawner's spawn range (4 blocks by default).",
        })
        public double searchRangeHorizontal = 2.0;
        public double searchRangeVertical = 4.5;
    }

    @Configuration
    public static class Stack {
        @Comment("Whether stacks show a name tag above them with the mob count.")
        public boolean showName = true;

        @Comment("MiniMessage format of the stack name tag. Placeholders: <count>, <type>")
        public String nameFormat = "<gold><b><count></b></gold> <yellow><type>s</yellow>";

        @Comment("Turn baby mobs into adults when they start a stack.")
        public boolean forceAdult = true;

        @Comment("Size given to slimes and magma cubes when they start a stack. 0 leaves the size untouched.")
        public int cubeMobSize = 4;

        @Comment("Stop stacked mobs picking up items.")
        public boolean disableItemPickup = true;

        @Comment("Stacked mobs ignore their surroundings: they don't wander, target or attack on their own.")
        public boolean disableAwareness = true;

        @Comment("Stacked mobs cannot deal damage to other entities.")
        public boolean preventDamage = true;
    }

    @Configuration
    public static class CubeMobs {
        @Comment({
            "Let stacked slimes and magma cubes split when killed. Each killed mob splits into its own",
            "smaller, unstacked mobs, exactly like a wild one would. When disabled, stacked ones never split.",
        })
        public boolean splitting = false;

        @Comment({
            "Roll slime loot as if the slime were size 1, so every kill drops slimeballs",
            "regardless of the stack's actual size.",
        })
        public boolean alwaysDropSlimeballs = true;
    }

    @Configuration
    public static class Death {
        @Comment({
            "Player kills use the killer's sweeping damage ratio to kill extra mobs from the stack",
            "(and leave the survivor with the leftover health). When disabled, each kill removes exactly one mob.",
        })
        public boolean sweepingKills = true;

        @Comment({
            "What happens when a stacked mob dies without a player killer (fall, fire, lava, etc.).",
            "SINGLE removes one mob from the stack, WHOLE_STACK kills the entire stack.",
        })
        public DeathMode naturalDeaths = DeathMode.SINGLE;

        @Comment({
            "Damage types that always kill the entire stack, whatever the setting above.",
            "Remove an entry to have that damage type only kill one mob at a time.",
        })
        public List<String> wholeStackDamageTypes = List.of("minecraft:cramming", "minecraft:generic_kill");
    }

    public enum DeathMode { SINGLE, WHOLE_STACK }
}
