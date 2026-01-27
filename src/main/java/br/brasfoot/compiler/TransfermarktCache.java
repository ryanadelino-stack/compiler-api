package br.brasfoot.compiler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Cache determinístico:
 * cache/transfermarkt/<country>/<teamId>/<season>.json
 */
public final class TransfermarktCache {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private final Path baseDir;

  public TransfermarktCache(Path baseDir) {
    this.baseDir = Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath().normalize();
  }

  public Path baseDir() {
    return baseDir;
  }

  public Path resolveJsonPath(TeamIdentity team) {
    Objects.requireNonNull(team, "team");
    return baseDir
        .resolve(team.country())
        .resolve(String.valueOf(team.teamId()))
        .resolve(team.season() + ".json");
  }

  public boolean exists(TeamIdentity team) {
    return Files.isRegularFile(resolveJsonPath(team));
  }

  public JsonArray read(TeamIdentity team) throws IOException {
    Path p = resolveJsonPath(team);
    try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
      return GSON.fromJson(r, JsonArray.class);
    }
  }

  public void write(TeamIdentity team, JsonArray players) throws IOException {
    Objects.requireNonNull(players, "players");
    Path p = resolveJsonPath(team);
    Files.createDirectories(p.getParent());
    try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
      GSON.toJson(players, w);
    }
  }
}