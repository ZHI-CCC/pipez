package de.maxhenkel.pipez.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import net.minecraft.core.Direction;
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端侧的管道 GUI 标签页记忆，保存在客户端 config 目录下的 JSON 文件中
 */
public final class LastTabMemory {

    private static final int MAX_ENTRIES = 4096;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static Path file;
    private static Map<String, Integer> memory;
    private static boolean loaded;

    private LastTabMemory() {
    }

    private static Path file() {
        if (file == null) {
            file = FMLPaths.CONFIGDIR.get().resolve("pipez").resolve("last_tab_memory.json");
        }
        return file;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        memory = new LinkedHashMap<>();
        try {
            Path f = file();
            if (Files.exists(f)) {
                try (BufferedReader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed != null && parsed.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                            JsonElement value = entry.getValue();
                            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                                continue;
                            }
                            memory.put(entry.getKey(), value.getAsInt());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            memory = new LinkedHashMap<>();
        }
        trim();
    }

    private static void trim() {
        while (memory.size() > MAX_ENTRIES) {
            Iterator<String> it = memory.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
    }

    private static synchronized void save() {
        Path f = file();
        Path tmp = f.resolveSibling(f.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(f.getParent());
            JsonObject json = new JsonObject();
            for (Map.Entry<String, Integer> entry : memory.entrySet()) {
                json.addProperty(entry.getKey(), entry.getValue());
            }
            Files.write(tmp, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored2) {
            }
        }
    }

    @Nullable
    public static String key(PipeLogicTileEntity pipe, Direction side) {
        if (pipe == null || pipe.getLevel() == null) {
            return null;
        }
        return pipe.getLevel().dimension().location() + "|" + pipe.getBlockPos().asLong() + "|" + side.get3DDataValue();
    }

    @Nullable
    public static Integer get(PipeLogicTileEntity pipe, Direction side) {
        String key = key(pipe, side);
        if (key == null) {
            return null;
        }
        load();
        return memory.get(key);
    }

    public static void put(PipeLogicTileEntity pipe, Direction side, int index) {
        String key = key(pipe, side);
        if (key == null) {
            return;
        }
        load();
        memory.put(key, index);
        trim();
        save();
    }
}
