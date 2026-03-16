package br.brasfoot.compiler;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

public final class StatsReader {

  private StatsReader() {}

  public static final class Stats {
    public int matchesRelated;
    public int matchesPlayed;
    public int goals;
    public int assists;
    public int ownGoals;
    public int fromBench;
    public int substituted;
    public int yellow;
    public int yellowRed;
    public int red;
    public int penaltyGoals;
    public double minutesPerGoal;
    public int minutesPlayed;

    /**
     * Minutos jogados apenas na temporada atual (campo adicionado pelo scraper).
     * Vale -1 quando o campo não está presente no JSON (jogador sem dado de temporada).
     * Usado pelo BanCompiler para ranquear titulares pela temporada atual.
     */
    public int minutesPlayedSeason;

    // GK
    public int goalsConceded;
    public int cleanSheets;

    /**
     * Estatísticas de pênaltis do goleiro (campo gk.penalties no JSON).
     * Populadas pelo módulo fetchPenaltyStats.js.
     * Permanecem zero quando o campo não está presente (JSON antigo ou jogador de linha).
     */
    public int penFaced;   // total de pênaltis enfrentados na carreira
    public int penSaved;   // total de pênaltis defendidos
  }

  public static Stats read(JsonObject player) {
    Stats s = new Stats();
    s.minutesPlayedSeason = -1; // sentinela: campo ausente

    JsonObject stats = asObj(player.get("stats"));
    if (stats == null) {
      return s; // tudo zero
    }

    s.matchesRelated = getInt(stats, "matchesRelated");
    s.matchesPlayed = getInt(stats, "matchesPlayed");
    s.goals = getInt(stats, "goals");
    s.assists = getInt(stats, "assists");
    s.ownGoals = getInt(stats, "ownGoals");
    s.fromBench = getInt(stats, "fromBench");
    s.substituted = getInt(stats, "substituted");
    s.yellow = getInt(stats, "yellow");
    s.yellowRed = getInt(stats, "yellowRed");
    s.red = getInt(stats, "red");
    s.penaltyGoals = getInt(stats, "penaltyGoals");
    s.minutesPerGoal = getDouble(stats, "minutesPerGoal");
    s.minutesPlayed = getInt(stats, "minutesPlayed");

    // minutesPlayedSeason: presente apenas em JSONs gerados com o scraper atualizado.
    // Mantém -1 se ausente para que o BanCompiler saiba usar o fallback.
    if (stats.has("minutesPlayedSeason") && !stats.get("minutesPlayedSeason").isJsonNull()) {
      s.minutesPlayedSeason = getInt(stats, "minutesPlayedSeason");
    }

    JsonObject gk = asObj(stats.get("gk"));
    if (gk != null) {
      s.goalsConceded = getInt(gk, "goalsConceded");
      s.cleanSheets = getInt(gk, "cleanSheets");

      // Campo adicionado por fetchPenaltyStats.js — ausente em JSONs antigos
      JsonObject penalties = asObj(gk.get("penalties"));
      if (penalties != null) {
        s.penFaced = getInt(penalties, "faced");
        s.penSaved = getInt(penalties, "saved");
        // saveRate é derivado no HeuristicsEngine a partir de penFaced/penSaved
      }
    }

    return s;
  }

  public static double readHeightMeters(JsonObject player) {
    JsonElement el = player.get("height");
    if (el == null || el instanceof JsonNull || el.isJsonNull()) return 0.0;

    try {
      if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
        return el.getAsDouble();
      }
      String raw = el.getAsString();
      if (raw == null) return 0.0;
      raw = raw.trim().toLowerCase();
      raw = raw.replace("m", "").replace(" ", "");
      raw = raw.replace(",", ".");
      // ex: "1.92"
      return Double.parseDouble(raw);
    } catch (Exception ignored) {
      return 0.0;
    }
  }

  // -----------------
  // Helpers (sem depender de novos métodos do JsonUtil)
  // -----------------

  private static JsonObject asObj(JsonElement el) {
    if (el == null || el.isJsonNull() || el instanceof JsonNull) return null;
    return el.isJsonObject() ? el.getAsJsonObject() : null;
  }

  private static int getInt(JsonObject obj, String key) {
    try {
      JsonElement el = obj.get(key);
      if (el == null || el.isJsonNull()) return 0;
      if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) return el.getAsInt();
      String s = el.getAsString();
      if (s == null) return 0;
      s = s.trim().replace(".", "").replace(",", ".");
      return (int) Double.parseDouble(s);
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static double getDouble(JsonObject obj, String key) {
    try {
      JsonElement el = obj.get(key);
      if (el == null || el.isJsonNull()) return 0.0;
      if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) return el.getAsDouble();
      String s = el.getAsString();
      if (s == null) return 0.0;
      s = s.trim().replace(",", ".");
      return Double.parseDouble(s);
    } catch (Exception ignored) {
      return 0.0;
    }
  }
}
