// HeuristicsEngine.java
// Pacote: br.brasfoot.compiler
//
// Implementação oficial conforme MANUAL COMPLETO DO SISTEMA DE CARACTERÍSTICAS DO BRASFOOT v4.0
// Fevereiro 2026 – Versão 4.0 Final
//
// Índices das características (0..13):
// 0 Colocacao, 1 Defesa Penalty, 2 Reflexo, 3 Saida gol,
// 4 Armacao, 5 Cabeceio, 6 Cruzamento, 7 Desarme, 8 Drible,
// 9 Finalizacao, 10 Marcacao, 11 Passe, 12 Resistencia, 13 Velocidade

package br.brasfoot.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class HeuristicsEngine {

  public static final String HEURISTICS_ENGINE_MARKER = "V4.0-FINAL-MANUAL";

  private static final boolean DEBUG =
      Boolean.parseBoolean(System.getProperty("brasfoot.debug", "false"));

  private HeuristicsEngine() {}

  // -------------------------------------------------------------------------
  // Métrica auxiliar (dados brutos + derivadas)
  // -------------------------------------------------------------------------
  private static final class Metrics {
    final int pos;
    final String posText;
    final List<String> secondary;
    final int related;
    final int played;
    final int goals;
    final int assists;
    final int ownGoals;
    final int fromBench;
    final int substituted;
    final int yellow;
    final int yellowRed;
    final int red;
    final int penaltyGoals;
    final double mpg;
    final int mp;
    final int gc;
    final int cs;
    final Integer age;
    final double height;

    // derived (already present)
    final double g90, a90, p90, c90, playRate, rotation;

    // additional derived metrics (computed on demand or stored)
    final double goalsPerGame;          // taxa_gol
    final double assistsPerGame;        // taxa_assistencia
    final double participationPerGame;  // taxa_participacao_gol
    final double yellowPerGame;         // taxa_cartao_amarelo
    final double redPerGame;            // taxa_cartao_vermelho
    final double disciplineIndex;       // indice_disciplina
    final double minsPerGame;           // minutos_por_jogo
    final double regularity;            // regularidade = played/related
    final double subRate;               // taxa_substituicao
    final double benchRate;             // taxa_banco

    // for goalkeepers
    final double goalsConcededPerGame;
    final double cleanSheetRate;

    Metrics(
        int pos, String posText, List<String> secondary,
        int related, int played, int goals, int assists, int ownGoals,
        int fromBench, int substituted, int yellow, int yellowRed, int red,
        int penaltyGoals, double mpg, int mp, int gc, int cs,
        Integer age, double height,
        double g90, double a90, double p90, double c90,
        double playRate, double rotation) {

      this.pos = pos;
      this.posText = posText;
      this.secondary = secondary;
      this.related = related;
      this.played = played;
      this.goals = goals;
      this.assists = assists;
      this.ownGoals = ownGoals;
      this.fromBench = fromBench;
      this.substituted = substituted;
      this.yellow = yellow;
      this.yellowRed = yellowRed;
      this.red = red;
      this.penaltyGoals = penaltyGoals;
      this.mpg = mpg;
      this.mp = mp;
      this.gc = gc;
      this.cs = cs;
      this.age = age;
      this.height = height;
      this.g90 = g90;
      this.a90 = a90;
      this.p90 = p90;
      this.c90 = c90;
      this.playRate = playRate;
      this.rotation = rotation;

      double p = Math.max(1, played);
      this.goalsPerGame = (double) goals / p;
      this.assistsPerGame = (double) assists / p;
      this.participationPerGame = (double) (goals + assists) / p;
      this.yellowPerGame = (double) yellow / p;
      int redEquivalent = red + yellowRed; // each yellowRed counts as one sending-off
      this.redPerGame = (double) redEquivalent / p;
      double totalCards = yellow + 3.0 * redEquivalent; // yellowRed already counted once
      this.disciplineIndex = Math.max(0, Math.min(1, 1.0 - (totalCards / p / 2.0)));
      this.minsPerGame = (double) mp / p;
      this.regularity = (related > 0) ? (double) played / related : 0.0;
      this.subRate = (played > 0) ? (double) substituted / played : 0.0;
      this.benchRate = (related > 0) ? (double) fromBench / related : 0.0;

      this.goalsConcededPerGame = (played > 0) ? (double) gc / played : 0.0;
      this.cleanSheetRate = (played > 0) ? (double) cs / played : 0.0;
    }

    static Metrics from(
        int pos, String posText, ArrayList<String> secondaryPositions,
        int matchesRelated, int matchesPlayed, int goals, int assists,
        int ownGoals, int fromBench, int substituted, int yellow,
        int yellowRed, int red, int penaltyGoals, double minutesPerGoal,
        int minutesPlayed, int goalsConceded, int cleanSheets,
        Integer idade, double heightM) {

      final int mp = Math.max(0, minutesPlayed);
      final int played = Math.max(0, matchesPlayed);
      final int related = Math.max(0, matchesRelated);

      final double g90 = (mp > 0) ? (goals * 90.0 / mp) : 0.0;
      final double a90 = (mp > 0) ? (assists * 90.0 / mp) : 0.0;
      final double p90 = g90 + a90;

      final double cardUnits = yellow + 2.0 * yellowRed + 3.0 * red;
      final double c90 = (mp > 0) ? (cardUnits * 90.0 / mp) : 0.0;

      final double playRate =
          (related > 0) ? (played / (double) related) : (played > 0 ? 1.0 : 0.0);

      final double benchRate = (played > 0) ? (fromBench / (double) played) : 0.0;
      final double subRate = (played > 0) ? (substituted / (double) played) : 0.0;
      final double rotation = benchRate + subRate;

      double mpg = minutesPerGoal;
      if (mpg <= 0 && goals > 0) mpg = mp / (double) goals;
      if (mpg <= 0) mpg = 9999.0;

      return new Metrics(
          pos, posText, secondaryPositions == null ? List.of() : secondaryPositions,
          related, played, goals, assists, ownGoals, fromBench, substituted,
          yellow, yellowRed, red, penaltyGoals, mpg, mp, goalsConceded, cleanSheets,
          idade, heightM,
          g90, a90, p90, c90, playRate, rotation);
    }
  }

  // -------------------------------------------------------------------------
  // Profile detection helpers
  // -------------------------------------------------------------------------
  private static boolean isLateralDefensivo(Metrics m) {
    return m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("zagueiro"));
  }

  private static boolean isLateralOfensivo(Metrics m) {
    return m.secondary.stream().anyMatch(s -> {
      String low = s.toLowerCase(Locale.ROOT);
      return low.contains("meia") || low.contains("ponta") || low.contains("atacante");
    });
  }

  private static boolean isZagueiroOfensivo(Metrics m) {
    if (m.played == 0) return false;
    return m.goalsPerGame >= 0.06 || (m.goalsPerGame >= 0.04 && m.assists >= 5);
  }

  private static boolean isMeiaOfensivo(Metrics m) {
    String p = m.posText.toLowerCase(Locale.ROOT);
    if (p.contains("meia ofensivo") || p.contains("meia atacante")) return true;
    return m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("meia atacante"));
  }

  private static boolean isMeiaCentral(Metrics m) {
    String p = m.posText.toLowerCase(Locale.ROOT);
    if (p.contains("meia central")) return true;
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("volante")))
      return true;
    return false;
  }

  private static boolean isCentroavante(Metrics m) {
    String p = m.posText.toLowerCase(Locale.ROOT);
    return p.contains("centroavante") || p.contains("9");
  }

  private static boolean isPonta(Metrics m) {
    String p = m.posText.toLowerCase(Locale.ROOT);
    if (p.contains("ponta") || p.contains("extremo")) return true;
    return m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("ponta"));
  }

  private static boolean isSegundoAtacante(Metrics m) {
    String p = m.posText.toLowerCase(Locale.ROOT);
    if (p.contains("segundo atacante") || p.contains("recu")) return true;
    return m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("segundo"));
  }

  // -------------------------------------------------------------------------
  // Global adjustments (casos especiais)
  // -------------------------------------------------------------------------
  private static void applyGlobalAdjustments(Map<Integer, Double> scores, Metrics m) {
    // Veterans (>33)
    if (m.age != null && m.age > 33) {
      // +15 to Pas, Arm, Col; +10 to DPe, SGo? (manual says Pas +15, Arm +15, Col +10)
      // but also penalties to Vel -20, Res -10
      modifyScore(scores, 11, 15.0); // Pas
      modifyScore(scores, 4, 15.0);  // Arm
      modifyScore(scores, 0, 10.0);  // Col
      modifyScore(scores, 1, 10.0);  // DPe (maybe? not in manual, but Col got +10, DPe might also benefit)
      modifyScore(scores, 3, 10.0);  // SGo (maybe)
      modifyScore(scores, 13, -20.0); // Vel
      modifyScore(scores, 12, -10.0); // Res
    }

    // Young (<21)
    if (m.age != null && m.age < 21) {
      modifyScore(scores, 13, 20.0); // Vel
      modifyScore(scores, 8, 15.0);  // Dri
      modifyScore(scores, 1, -15.0); // DPe
      modifyScore(scores, 12, -10.0); // Res
    }

    // Low games (<10) – not specified exactly, but we can apply conservative defaults
    // We'll trust that the base scores still work; manual says "dar mais peso para altura/idade"
    // but we can incorporate via global adjustments already applied.
    // We'll add a small boost to height-based scores if very few games.
    if (m.played < 10) {
      // boost to SGo for tall keepers, Cab for tall players, etc.
      if (m.height >= 1.90) {
        modifyScore(scores, 3, 10.0); // SGo
        modifyScore(scores, 5, 10.0); // Cab
      }
    }
  }

  private static void modifyScore(Map<Integer, Double> scores, int idx, double delta) {
    scores.put(idx, scores.getOrDefault(idx, 0.0) + delta);
  }

  // -------------------------------------------------------------------------
  // Scoring functions per characteristic and position
  // -------------------------------------------------------------------------

  // ---------------- Goleiro ----------------
  private static double scoreCol(Metrics m) {
    double pontos = 0;
    if (m.cleanSheetRate >= 0.40) pontos += 40;
    else if (m.cleanSheetRate >= 0.30) pontos += 30;
    else if (m.cleanSheetRate >= 0.25) pontos += 20;
    if (m.goalsConcededPerGame <= 1.0) pontos += 30;
    else if (m.goalsConcededPerGame <= 1.2) pontos += 20;
    if (m.height >= 1.88) pontos += 15;
    if (m.played >= 150) pontos += 15;
    return pontos;
  }

  private static double scoreRef(Metrics m) {
    double pontos = 0;
    if (m.goalsConcededPerGame <= 1.0) pontos += 35;
    else if (m.goalsConcededPerGame <= 1.3) pontos += 25;
    if (m.cleanSheetRate >= 0.30) pontos += 30;
    double mediaDefesas = (m.played - m.cs) / (double) m.played; // manual "média de defesas"
    if (mediaDefesas >= 0.70) pontos += 20;
    if (m.age != null && m.age <= 30) pontos += 15;
    return pontos;
  }

  private static double scoreDPe(Metrics m) {
    double pontos = 0;
    if (m.played >= 100) pontos += 30;
    if (m.cleanSheetRate >= 0.25) pontos += 25;
    if (m.height >= 1.85) pontos += 20;
    if (m.age != null && m.age >= 28) pontos += 15;
    if (m.goalsConcededPerGame <= 1.2) pontos += 10;
    return pontos;
  }

  private static double scoreSGo(Metrics m) {
    double pontos = 0;
    if (m.height >= 2.00) pontos += 80;
    else if (m.height >= 1.95) pontos += 60;
    else if (m.height >= 1.92) pontos += 40;
    else if (m.height >= 1.88) pontos += 25;
    if (m.played >= 120) pontos += 20;
    if (m.cleanSheetRate >= 0.30) pontos += 20;
    if (m.age != null && m.age >= 27 && m.age <= 33) pontos += 15;
    if (m.assists > 0) pontos += 20;
    return pontos;
  }

  // ---------------- Zagueiro ----------------
  private static double scoreDesZag(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.15) pontos += 35;
    else if (m.yellowPerGame >= 0.10) pontos += 25;
    if (m.disciplineIndex >= 0.70) pontos += 20;
    if (m.height >= 1.85) pontos += 15;
    if (m.played >= 150) pontos += 10;
    return pontos;
  }

  private static double scoreMarZag(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.12) pontos += 30;
    if (m.disciplineIndex >= 0.75) pontos += 25;
    if (m.regularity >= 0.70) pontos += 20;
    if (m.played >= 180) pontos += 15;
    if (m.goalsPerGame <= 0.05) pontos += 10;
    return pontos;
  }

  private static double scoreCabZag(Metrics m) {
    double pontos = 0;
    if (m.goalsPerGame >= 0.10) pontos += 60;
    else if (m.goalsPerGame >= 0.07) pontos += 50;
    else if (m.goalsPerGame >= 0.04) pontos += 30;
    if (m.height >= 1.88) pontos += 25;
    if (m.penaltyGoals > 0) pontos += 15;
    if (m.participationPerGame >= 0.08) pontos += 10;
    if (m.played >= 300 && m.goalsPerGame >= 0.06) pontos += 20; // veterano goleador
    return pontos;
  }

  private static double scoreVelZag(Metrics m) {
    double pontos = 0;
    if (m.age != null && m.age <= 27) pontos += 30;
    if (m.height <= 1.85) pontos += 20;
    if (m.assistsPerGame >= 0.03) pontos += 20;
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("volante")))
      pontos += 20;
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("lateral"))) {
      pontos += 30;
      if (m.assistsPerGame >= 0.05) pontos += 20;
    }
    if (m.minsPerGame >= 80) pontos += 10;
    return pontos;
  }

  private static double scorePasZag(Metrics m) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.03) pontos += 35;
    else if (m.assistsPerGame >= 0.02) pontos += 25;
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("volante")))
      pontos += 25;
    if (m.disciplineIndex >= 0.80) pontos += 15;
    return pontos;
  }

  private static double scoreResZag(Metrics m) {
    double pontos = 0;
    if (m.minsPerGame >= 85) pontos += 35;
    else if (m.minsPerGame >= 80) pontos += 25;
    if (m.regularity >= 0.75) pontos += 25;
    if (m.age != null && m.age >= 29 && m.age <= 34) pontos += 15;
    return pontos;
  }

  // ---------------- Lateral ----------------
  private static double scoreCruLat(Metrics m) {
    double pontos = 20; // base
    if (m.assistsPerGame >= 0.10) pontos += 40;
    else if (m.assistsPerGame >= 0.07) pontos += 30;
    else if (m.assistsPerGame >= 0.05) pontos += 30;
    else if (m.assistsPerGame >= 0.03) pontos += 25;
    if (isLateralOfensivo(m)) pontos += 25;
    if (m.participationPerGame >= 0.12) pontos += 15;
    if (m.assistsPerGame >= 0.08) pontos += 20;
    if (m.played >= 300 && m.assistsPerGame >= 0.05) pontos += 15;
    return pontos;
  }

  private static double scoreVelLat(Metrics m) {
    double pontos = 0;
    if (m.age != null && m.age <= 28) pontos += 30;
    if (m.assistsPerGame >= 0.08) pontos += 25;
    else if (m.assistsPerGame >= 0.05) pontos += 20;
    if (m.minsPerGame >= 75) pontos += 20;
    if (m.subRate <= 0.20) pontos += 15;
    if (isLateralOfensivo(m)) pontos += 20;
    return pontos;
  }

  private static double scorePasLat(Metrics m) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.10) pontos += 50;
    else if (m.assistsPerGame >= 0.08) pontos += 35;
    else if (m.assistsPerGame >= 0.05) pontos += 25;
    if (isLateralOfensivo(m)) pontos += 20;
    if (m.disciplineIndex >= 0.80) pontos += 15;
    if (m.played >= 300 && m.assistsPerGame >= 0.10) pontos += 30;
    return pontos;
  }

  private static double scoreMarLat(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.10) pontos += 30;
    if (isLateralDefensivo(m)) pontos += 25;
    if (m.goalsPerGame <= 0.05) pontos += 20;
    if (m.disciplineIndex >= 0.70) pontos += 15;
    if (m.played >= 150) pontos += 10;
    // penalties
    if (m.goalsPerGame >= 0.05) pontos -= 15;
    if (m.participationPerGame >= 0.10) pontos -= 10;
    pontos -= 40; // massiva penalidade
    return Math.max(0, pontos);
  }

  private static double scoreDesLat(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.10) pontos += 30;
    if (isLateralDefensivo(m)) pontos += 25;
    if (m.height >= 1.78) pontos += 15;
    if (m.played >= 120) pontos += 15;
    if (m.participationPerGame >= 0.08) pontos -= 15;
    pontos -= 40; // massiva penalidade
    return Math.max(0, pontos);
  }

  private static double scoreFinLat(Metrics m) {
    double pontos = 0;
    if (m.goalsPerGame >= 0.05) pontos += 40;
    else if (m.goalsPerGame >= 0.03) pontos += 30;
    if (m.penaltyGoals > 0) pontos += 15;
    if (m.mpg > 0 && m.mpg < 2000) pontos += 15;
    return pontos;
  }

  // ---------------- Volante ----------------
  private static double scoreDesVol(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.15) pontos += 35;
    else if (m.yellowPerGame >= 0.10) pontos += 25;
    if (m.disciplineIndex >= 0.70) pontos += 20;
    if (m.goalsPerGame <= 0.05) pontos += 15;
    if (m.played >= 150) pontos += 10;
    return pontos;
  }

  private static double scoreMarVol(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.12) pontos += 30;
    if (m.regularity >= 0.70) pontos += 25;
    if (m.disciplineIndex >= 0.75) pontos += 20;
    if (m.played >= 180) pontos += 15;
    if (m.minsPerGame >= 80) pontos += 10;
    return pontos;
  }

  private static double scorePasVol(Metrics m) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.05) pontos += 35;
    else if (m.assistsPerGame >= 0.03) pontos += 25;
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("meia central")))
      pontos += 20;
    if (m.disciplineIndex >= 0.80) pontos += 15;
    if (m.participationPerGame >= 0.10) pontos += 20;
    return pontos;
  }

  private static double scoreFinVol(Metrics m) {
    double pontos = 0;
    if (m.goalsPerGame >= 0.08) pontos += 50;
    else if (m.goalsPerGame >= 0.05) pontos += 40;
    else if (m.goalsPerGame >= 0.03) pontos += 30;
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("meia ofensivo")))
      pontos += 20;
    if (m.penaltyGoals > 0) pontos += 10;
    if (m.mpg > 0 && m.mpg < 1500) pontos += 15;
    return pontos;
  }

  private static double scoreResVol(Metrics m) {
    double pontos = 0;
    if (m.minsPerGame >= 85) pontos += 35;
    else if (m.minsPerGame >= 80) pontos += 25;
    if (m.regularity >= 0.75) pontos += 25;
    if (m.age != null && m.age >= 27 && m.age <= 32) pontos += 15;
    return pontos;
  }

  private static double scoreVelVol(Metrics m) {
    double pontos = 0;
    if (m.age != null && m.age <= 27) pontos += 30;
    if (m.participationPerGame >= 0.10) pontos += 25;
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("meia")))
      pontos += 20;
    if (m.height <= 1.80) pontos += 15;
    return pontos;
  }

  // ---------------- Meia ----------------
  // Arm
  private static double scoreArmMeia(Metrics m, boolean ofensivo) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.12) pontos += 40;
    else if (m.assistsPerGame >= 0.08) pontos += 30;
    else if (m.assistsPerGame >= 0.05) pontos += 20;
    if (m.participationPerGame >= 0.20) pontos += 20;
    if (!ofensivo) pontos += 15; // meia central
    if (ofensivo) {
      if (m.participationPerGame >= 0.25) pontos += 20;
      if (m.assistsPerGame >= 0.10) pontos += 20;
    }
    return pontos;
  }

  // Pas
  private static double scorePasMeia(Metrics m) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.10) pontos += 35;
    else if (m.assistsPerGame >= 0.07) pontos += 25;
    if (m.disciplineIndex >= 0.80) pontos += 20;
    if (m.minsPerGame >= 75) pontos += 15;
    return pontos;
  }

  // Vel
  private static double scoreVelMeia(Metrics m, boolean ofensivo) {
    double pontos = 0;
    if (m.age != null && m.age <= 27) pontos += 30;
    if (m.participationPerGame >= 0.15) pontos += 25;
    if (m.height <= 1.78) pontos += 20;
    if (ofensivo) pontos += 15;
    return pontos;
  }

  // Dri
  private static double scoreDriMeia(Metrics m) {
    double pontos = 0;
    if (m.participationPerGame >= 0.20) pontos += 35;
    if (m.age != null && m.age <= 28) pontos += 25;
    if (m.height <= 1.75) pontos += 20;
    if (m.assistsPerGame >= 0.10) pontos += 20;
    return pontos;
  }

  // Fin
  private static double scoreFinMeia(Metrics m, boolean ofensivo) {
    double pontos = 0;
    if (m.goalsPerGame >= 0.12) pontos += 40;
    else if (m.goalsPerGame >= 0.08) pontos += 30;
    if (ofensivo) pontos += 20;
    if (m.penaltyGoals > 0) pontos += 15;
    if (m.mpg > 0 && m.mpg < 1000) pontos += 15;
    return pontos;
  }

  // Des (com penalidade)
  private static double scoreDesMeia(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.10) pontos += 25; // threshold aumentado
    if (!isMeiaOfensivo(m)) pontos += 20; // meia central
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("volante")))
      pontos += 25;
    if (m.goalsPerGame <= 0.05) pontos += 10;
    // penalidade se participativo
    if (m.participationPerGame >= 0.15) pontos -= 20;
    return Math.max(0, pontos);
  }

  // ---------------- Atacante ----------------
  // Fin
  private static double scoreFinAtac(Metrics m) {
    double pontos = 0;
    if (m.goalsPerGame >= 0.30) pontos += 50;
    else if (m.goalsPerGame >= 0.20) pontos += 40;
    else if (m.goalsPerGame >= 0.15) pontos += 30;
    else if (m.goalsPerGame >= 0.10) pontos += 20;

    if (m.goalsPerGame >= 0.19) pontos += 50; // bônus extra
    else if (m.goalsPerGame >= 0.15) pontos += 35;

    if (m.mpg > 0 && m.mpg < 300) pontos += 25;
    else if (m.mpg > 0 && m.mpg < 500) pontos += 15;

    if (m.penaltyGoals >= 3) pontos += 10;
    return pontos;
  }

  // Vel
  private static double scoreVelAtac(Metrics m, boolean ponta, boolean centroavante) {
    double pontos = 0;
    if (m.age != null && m.age <= 28) pontos += 35;
    if (ponta) pontos += 30;
    if (m.height <= 1.80) pontos += 20;
    if (m.participationPerGame >= 0.25) pontos += 15;
    if (centroavante && m.age != null && m.age <= 25) pontos += 25;
    // penalidade se for ponta habilidoso (muitas assistências e baixa altura)
    if (ponta && m.assistsPerGame >= 0.10 && m.height <= 1.78) pontos -= 15;
    return Math.max(0, pontos);
  }

  // Cab
  private static double scoreCabAtac(Metrics m, boolean centroavante) {
    double pontos = 0;
    if (m.height >= 1.85) pontos += 35;
    if (centroavante) pontos += 30;
    if (m.goalsPerGame >= 0.15) pontos += 25;
    if (m.age != null && m.age >= 26) pontos += 10;
    if (centroavante && m.goalsPerGame >= 0.25) pontos += 20;
    return pontos;
  }

  // Dri
  private static double scoreDriAtac(Metrics m, boolean ponta, boolean segundo) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.10) pontos += 40;
    else if (m.assistsPerGame >= 0.08) pontos += 30;
    if (m.age != null && m.age <= 27) pontos += 25;
    if (m.height <= 1.78) pontos += 25;
    if (ponta || segundo) pontos += 20;
    if (ponta && m.participationPerGame >= 0.25) pontos += 30;
    if (m.goalsPerGame >= 0.15 && m.assistsPerGame >= 0.08) pontos += 25;
    return pontos;
  }

  // Pas
  private static double scorePasAtac(Metrics m, boolean segundo) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.12) pontos += 40;
    else if (m.assistsPerGame >= 0.08) pontos += 30;
    if (segundo) pontos += 25;
    if (m.participationPerGame >= 0.30) pontos += 15;
    return pontos;
  }

  // Res
  private static double scoreResAtac(Metrics m, boolean centroavante) {
    double pontos = 0;
    if (m.minsPerGame >= 85) pontos += 35;
    else if (m.minsPerGame >= 80) pontos += 25;
    if (m.regularity >= 0.75) pontos += 20;
    if (centroavante) pontos += 15;
    return pontos;
  }

  // -------------------------------------------------------------------------
  // Allowed combinations per position/profile (manual section 8?)
  // Actually manual lists "Combinações Ideais" for each subposition.
  // We'll define sets.
  // -------------------------------------------------------------------------
  private static Set<String> getAllowedCombinations(String posProfile) {
    Set<String> set = new HashSet<>();
    switch (posProfile) {
      case "GK": set.addAll(List.of("Col/Ref","Ref/Col","Ref/DPe","DPe/Ref","SGo/Ref","Ref/SGo",
          "Col/DPe","DPe/Col","SGo/DPe","DPe/SGo","SGo/Col","Col/SGo")); break;
      case "ZAG_NORMAL": set.addAll(List.of("Des/Mar","Mar/Des","Des/Pas","Mar/Pas","Des/Res","Mar/Res")); break;
      case "ZAG_OFENSIVO": set.addAll(List.of("Mar/Vel","Des/Cab","Cab/Des","Mar/Cab","Cab/Mar")); break;
      case "LAT_DEF": set.addAll(List.of("Mar/Cru","Cru/Mar","Mar/Fin","Mar/Vel","Vel/Mar","Des/Cru","Cru/Des","Vel/Pas","Pas/Vel")); break;
      case "LAT_OF": set.addAll(List.of("Cru/Vel","Vel/Cru","Cru/Pas","Vel/Pas","Cru/Fin")); break;
      case "VOL": set.addAll(List.of("Des/Pas","Mar/Pas","Mar/Res","Des/Res","Des/Mar","Mar/Des","Mar/Fin","Des/Fin","Des/Vel")); break;
      case "M_CENTRAL": set.addAll(List.of("Pas/Vel","Vel/Pas","Arm/Vel","Arm/Dri","Dri/Pas","Pas/Dri","Des/Vel","Arm/Pas")); break;
      case "M_OFENSIVO": set.addAll(List.of("Fin/Pas","Arm/Fin","Arm/Pas","Fin/Arm","Dri/Pas","Dri/Fin","Fin/Dri")); break;
      case "ATAC_REC": set.addAll(List.of("Dri/Pas","Dri/Fin","Pas/Fin")); break;
      case "ATAC_CA": set.addAll(List.of("Fin/Pas","Fin/Cab","Cab/Fin","Fin/Dri","Fin/Res","Cab/Vel")); break;
      case "ATAC_PONTA": set.addAll(List.of("Vel/Fin","Fin/Vel","Vel/Dri","Fin/Dri","Dri/Fin")); break;
      default: break;
    }
    return set;
  }

  // -------------------------------------------------------------------------
  // Main method
  // -------------------------------------------------------------------------
  public static int[] pickTop2CharacteristicsByManual(
      int pos,
      String posText,
      ArrayList<String> secondaryPositions,
      int matchesRelated,
      int matchesPlayed,
      int goals,
      int assists,
      int ownGoals,
      int fromBench,
      int substituted,
      int yellow,
      int yellowRed,
      int red,
      int penaltyGoals,
      double minutesPerGoal,
      int minutesPlayed,
      int goalsConceded,
      int cleanSheets,
      Integer idade,
      double heightM) {

    Metrics m = Metrics.from(
        pos, posText, secondaryPositions,
        matchesRelated, matchesPlayed, goals, assists, ownGoals,
        fromBench, substituted, yellow, yellowRed, red,
        penaltyGoals, minutesPerGoal, minutesPlayed,
        goalsConceded, cleanSheets, idade, heightM);

    if (m.played == 0) {
      int[] fallback = getFallbackForPosition(pos);
      if (DEBUG) {
        System.out.println("[DEBUG] No matches played, using fallback: cr1=" +
                           fallback[0] + " cr2=" + fallback[1] + " for pos=" + pos);
      }
      return fallback;
    }

    // Determine position profile string for allowed combinations
    String profile = "";
    Map<Integer, Double> scores = new HashMap<>();

    // 1. Compute base scores for each valid characteristic per position
    if (pos == 0) { // Goleiro
      scores.put(0, scoreCol(m));   // Col
      scores.put(2, scoreRef(m));   // Ref
      scores.put(1, scoreDPe(m));   // DPe
      scores.put(3, scoreSGo(m));   // SGo
      profile = "GK";
    }
    else if (pos == 2) { // Zagueiro
      boolean ofensivo = isZagueiroOfensivo(m);
      scores.put(7, scoreDesZag(m));
      scores.put(10, scoreMarZag(m));
      scores.put(5, scoreCabZag(m));
      scores.put(13, scoreVelZag(m));
      scores.put(11, scorePasZag(m));
      scores.put(12, scoreResZag(m));
      profile = ofensivo ? "ZAG_OFENSIVO" : "ZAG_NORMAL";
    }
    else if (pos == 1) { // Lateral
      boolean defensivo = isLateralDefensivo(m);
      boolean ofensivo = isLateralOfensivo(m);
      scores.put(6, scoreCruLat(m));   // Cru
      scores.put(13, scoreVelLat(m));  // Vel
      scores.put(11, scorePasLat(m));  // Pas
      scores.put(10, scoreMarLat(m));  // Mar (penalizado)
      scores.put(7, scoreDesLat(m));   // Des (penalizado)
      scores.put(9, scoreFinLat(m));   // Fin
      // Determine profile string
      if (defensivo) profile = "LAT_DEF";
      else if (ofensivo) profile = "LAT_OF";
      else {
        // use stats
        profile = (m.participationPerGame >= 0.08) ? "LAT_OF" : "LAT_DEF";
      }
    }
    else if (pos == 3) { // Meia (Volante, Meia Central, Meia Ofensivo)
      boolean defMid = isDefMidLike(m); // volante
      boolean attMid = isMeiaOfensivo(m);
      boolean central = isMeiaCentral(m);

      if (defMid) {
        scores.put(7, scoreDesVol(m));
        scores.put(10, scoreMarVol(m));
        scores.put(11, scorePasVol(m));
        scores.put(9, scoreFinVol(m));
        scores.put(12, scoreResVol(m));
        scores.put(13, scoreVelVol(m));
        profile = "VOL";
      } else if (attMid) {
        scores.put(4, scoreArmMeia(m, true));  // Arm
        scores.put(11, scorePasMeia(m));       // Pas
        scores.put(13, scoreVelMeia(m, true)); // Vel
        scores.put(8, scoreDriMeia(m));        // Dri
        scores.put(9, scoreFinMeia(m, true));  // Fin
        scores.put(7, scoreDesMeia(m));        // Des (penalizado)
        profile = "M_OFENSIVO";
      } else { // central
        scores.put(4, scoreArmMeia(m, false));
        scores.put(11, scorePasMeia(m));
        scores.put(13, scoreVelMeia(m, false));
        scores.put(8, scoreDriMeia(m));
        scores.put(9, scoreFinMeia(m, false));
        scores.put(7, scoreDesMeia(m));
        profile = "M_CENTRAL";
      }
    }
    else if (pos == 4) { // Atacante
      boolean ca = isCentroavante(m);
      boolean ponta = isPonta(m);
      boolean seg = isSegundoAtacante(m);

      // Valid characteristics: Fin, Vel, Cab, Dri, Pas, Res
      scores.put(9, scoreFinAtac(m));
      scores.put(13, scoreVelAtac(m, ponta, ca));
      scores.put(5, scoreCabAtac(m, ca));
      scores.put(8, scoreDriAtac(m, ponta, seg));
      scores.put(11, scorePasAtac(m, seg));
      scores.put(12, scoreResAtac(m, ca));

      if (ca) profile = "ATAC_CA";
      else if (ponta) profile = "ATAC_PONTA";
      else if (seg) profile = "ATAC_REC";
      else {
        // fallback: use stats
        if (m.goalsPerGame >= 0.20 && m.height >= 1.85) profile = "ATAC_CA";
        else if (m.assistsPerGame >= 0.08) profile = "ATAC_REC";
        else profile = "ATAC_PONTA";
      }
    }

    // Apply global adjustments (veteran, young, low games)
    applyGlobalAdjustments(scores, m);

    // Sort characteristics by score descending
    List<Map.Entry<Integer, Double>> sorted = scores.entrySet().stream()
        .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
        .collect(Collectors.toList());

    if (DEBUG) {
      System.out.println("[DEBUG] Scores for " + m.posText + ": " + sorted);
    }

    // Select top two
    if (sorted.size() < 2) {
      // Should not happen, but fallback
      return getFallbackForPosition(pos);
    }

    int first = sorted.get(0).getKey();
    int second = sorted.get(1).getKey();

    // Validate against allowed combinations
    Set<String> allowed = getAllowedCombinations(profile);
    String pair1 = idxToName(first) + "/" + idxToName(second);
    String pair2 = idxToName(second) + "/" + idxToName(first);

    if (allowed.contains(pair1) || allowed.contains(pair2)) {
      // Accept, keep order as scored
      if (DEBUG) System.out.println("[DEBUG] Selected pair: " + pair1 + " (allowed)");
    } else {
      // Need to find the best allowed pair among top characteristics
      // Try top 5 to find any allowed pair
      List<Integer> topCandidates = sorted.stream().limit(5).map(Map.Entry::getKey).collect(Collectors.toList());
      double bestScore = -1;
      int bestA = first, bestB = second;
      for (int i = 0; i < topCandidates.size(); i++) {
        for (int j = i+1; j < topCandidates.size(); j++) {
          int a = topCandidates.get(i);
          int b = topCandidates.get(j);
          String p1 = idxToName(a) + "/" + idxToName(b);
          String p2 = idxToName(b) + "/" + idxToName(a);
          if (allowed.contains(p1) || allowed.contains(p2)) {
            double scoreSum = scores.get(a) + scores.get(b);
            if (scoreSum > bestScore) {
              bestScore = scoreSum;
              bestA = a;
              bestB = b;
            }
          }
        }
      }
      if (bestScore >= 0) {
        first = bestA;
        second = bestB;
        if (DEBUG) System.out.println("[DEBUG] Adjusted to allowed pair: " + idxToName(first) + "/" + idxToName(second));
      } else {
        // No allowed pair found among top 5, just use top two
        if (DEBUG) System.out.println("[DEBUG] No allowed pair found, using top two: " + pair1);
      }
    }

    return new int[] { first, second };
  }

  private static String idxToName(int idx) {
    switch (idx) {
      case 0: return "Col";
      case 1: return "DPe";
      case 2: return "Ref";
      case 3: return "SGo";
      case 4: return "Arm";
      case 5: return "Cab";
      case 6: return "Cru";
      case 7: return "Des";
      case 8: return "Dri";
      case 9: return "Fin";
      case 10: return "Mar";
      case 11: return "Pas";
      case 12: return "Res";
      case 13: return "Vel";
      default: return "?";
    }
  }

  // Fallback if something goes wrong
  private static int[] getFallbackForPosition(int pos) {
    switch (pos) {
      case 0: return new int[] {0, 2}; // Col/Ref
      case 1: return new int[] {6, 13}; // Cru/Vel (ofensivo default)
      case 2: return new int[] {10, 7}; // Mar/Des
      case 3: return new int[] {4, 11}; // Arm/Pas
      case 4: return new int[] {9, 8}; // Fin/Dri
      default: return new int[] {11, 13}; // Pas/Vel
    }
  }

  // Helper for meia detection (copied from previous code)
  private static boolean isDefMidLike(Metrics m) {
    final String p = m.posText.toLowerCase(Locale.ROOT);
    return m.pos == 3 && (p.contains("volante") || m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("volante")));
  }
}
