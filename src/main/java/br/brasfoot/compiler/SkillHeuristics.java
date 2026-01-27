package br.brasfoot.compiler;

import com.google.gson.JsonObject;

final class SkillHeuristics {
  private SkillHeuristics() {}

  // Retorna um inteiro "aceitavel" (0..200) a partir de estatisticas simples.
  static int skillFromStats(JsonObject stats) {
    if (stats == null) return 65;
    int apps = getInt(stats, "apps");
    int goals = getInt(stats, "goals");
    int assists = getInt(stats, "assists");

    // Heuristica simples e deterministica:
    // - base 50
    // - +log(apps)
    // - +gols/assistencias ponderados
    double score = 50.0;
    score += Math.log(1 + apps) * 10.0;
    score += goals * 1.2;
    score += assists * 0.9;

    int s = (int) Math.round(score);
    if (s < 30) s = 30;
    if (s > 200) s = 200;
    return s;
  }

  private static int getInt(JsonObject o, String k) {
    try {
      return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
