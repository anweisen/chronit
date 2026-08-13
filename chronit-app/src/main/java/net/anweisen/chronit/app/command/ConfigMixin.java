package net.anweisen.chronit.app.command;

import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.ConfigLoader;
import picocli.CommandLine;

import java.nio.file.Path;

/** Shared {@code --config} option and loading, so every subcommand resolves it the same way. */
public class ConfigMixin {

    @CommandLine.Option(
            names = {"-c", "--config"},
            description = "Configuration file. Defaults to $CHRONIT_CONFIG, then ./chronit.yml.")
    Path configFile;

    public Path resolveConfigFile() {
        if (configFile != null) {
            return configFile;
        }
        String fromEnvironment = System.getenv("CHRONIT_CONFIG");
        return fromEnvironment != null ? Path.of(fromEnvironment) : Path.of("chronit.yml");
    }

    public ChronitConfig load() {
        return new ConfigLoader().load(resolveConfigFile());
    }
}
