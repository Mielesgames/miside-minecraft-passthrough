package dev.skycraft.config;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Editable text files in Minecraft's config folder, written with defaults on first use. */
public final class Config {
	private Config() {
	}

	/** The file's text, or {@code defaults} (written to the file if it's missing). */
	public static String read(String file, String defaults) {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(file);
		try {
			if (Files.exists(path)) {
				return Files.readString(path);
			}
			Files.createDirectories(path.getParent());
			Files.writeString(path, defaults);
			SkyCraft.LOG.info("SkyCraft: wrote defaults to {}", path);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}, using defaults: {}", path, e.toString());
		}
		return defaults;
	}
}
