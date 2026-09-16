package info.preva1l.fadms;

import de.exlll.configlib.NameFormatters;
import de.exlll.configlib.YamlConfigurations;
import org.bukkit.plugin.java.JavaPlugin;

public final class Fadms extends JavaPlugin {
    private FadmsConfig settings;

    @Override
    public void onEnable() {
        settings = YamlConfigurations.update(getDataPath().resolve("config.yml"), FadmsConfig.class, builder -> builder
            .setNameFormatter(NameFormatters.LOWER_KEBAB_CASE)
            .header("FADMS - stacks mobs spawned by spawners."));

        getServer().getPluginManager().registerEvents(new StackListener(this), this);
    }

    public FadmsConfig settings() {
        return settings;
    }
}
