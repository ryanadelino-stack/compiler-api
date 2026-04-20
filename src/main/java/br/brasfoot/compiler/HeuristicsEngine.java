// HeuristicsEngine.java
// Pacote: br.brasfoot.compiler
//
// Implementação oficial conforme MANUAL COMPLETO DO SISTEMA DE CARACTERÍSTICAS DO BRASFOOT v5.0
// Fevereiro 2026 – Versão 5.0 — Pares e Fallback Aprimorados
//
// Mudanças v5.0:
//   - Pool de pares por subposição atualizado (tabela mestre do design doc)
//   - Novo perfil M_ESQUERDA_DIREITA (Meia Esquerda / Meia Direita)
//   - Detecção de "Ala" → LAT_OF por padrão
//   - Fallback (sem stats) agora sorteia aleatoriamente do pool da subposição detectada
//   - Quando scoring produz par fora do allowed list, busca o melhor par permitido (top-5)
//   - Posição genérica sem subposição → pool unificado do grupo
//
// Índices das características (0..13):
// 0 Colocacao, 1 Defesa Penalty, 2 Reflexo, 3 Saida gol,
// 4 Armacao, 5 Cabeceio, 6 Cruzamento, 7 Desarme, 8 Drible,
// 9 Finalizacao, 10 Marcacao, 11 Passe, 12 Resistencia, 13 Velocidade

package br.brasfoot.compiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public final class HeuristicsEngine {

  public static final String HEURISTICS_ENGINE_MARKER = "V5.0-PARES-APRIMORADOS";

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

    // additional derived metrics
    final double goalsPerGame;
    final double assistsPerGame;
    final double participationPerGame;
    final double yellowPerGame;
    final double redPerGame;
    final double disciplineIndex;
    final double minsPerGame;
    final double regularity;
    final double subRate;
    final double benchRate;

    // for goalkeepers
    final double goalsConcededPerGame;
    final double cleanSheetRate;

    /** Pênaltis enfrentados/defendidos (de stats.gk.penalties no JSON). */
    final int penFaced;
    final int penSaved;
    /**
     * Taxa de defesa de pênaltis = penSaved / penFaced.
     * Vale 0.0 quando penFaced == 0 (sem dados ou nenhum pênalti enfrentado).
     * Usado em scoreDPe como diferenciador forte quando a amostra é confiável.
     */
    final double penaltySaveRate;

    Metrics(
        int pos, String posText, List<String> secondary,
        int related, int played, int goals, int assists, int ownGoals,
        int fromBench, int substituted, int yellow, int yellowRed, int red,
        int penaltyGoals, double mpg, int mp, int gc, int cs,
        int penFaced, int penSaved,
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
      this.penFaced = penFaced;
      this.penSaved = penSaved;
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
      int redEquivalent = red + yellowRed;
      this.redPerGame = (double) redEquivalent / p;
      double totalCards = yellow + 3.0 * redEquivalent;
      this.disciplineIndex = Math.max(0, Math.min(1, 1.0 - (totalCards / p / 2.0)));
      this.minsPerGame = (double) mp / p;
      this.regularity = (related > 0) ? (double) played / related : 0.0;
      this.subRate = (played > 0) ? (double) substituted / played : 0.0;
      this.benchRate = (related > 0) ? (double) fromBench / related : 0.0;

      this.goalsConcededPerGame = (played > 0) ? (double) gc / played : 0.0;
      this.cleanSheetRate = (played > 0) ? (double) cs / played : 0.0;
      this.penaltySaveRate = (penFaced > 0) ? (double) penSaved / penFaced : 0.0;
    }

    static Metrics from(
        int pos, String posText, ArrayList<String> secondaryPositions,
        int matchesRelated, int matchesPlayed, int goals, int assists,
        int ownGoals, int fromBench, int substituted, int yellow,
        int yellowRed, int red, int penaltyGoals, double minutesPerGoal,
        int minutesPlayed, int goalsConceded, int cleanSheets,
        int penFaced, int penSaved,
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
          penFaced, penSaved, idade, heightM,
          g90, a90, p90, c90, playRate, rotation);
    }
  }

  // -------------------------------------------------------------------------
  // Profile detection helpers (corrected order)
  // -------------------------------------------------------------------------
  private static boolean isVolante(Metrics m) {
    String p = m.posText.toLowerCase(Locale.ROOT);
    return m.pos == 3 && p.contains("volante");
  }

  private static boolean isMeiaOfensivo(Metrics m) {
    String p = m.posText.toLowerCase(Locale.ROOT);
    if (p.contains("meia ofensivo") || p.contains("meia atacante")) return true;
    return m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("meia atacante"));
  }

  private static boolean isMeiaCentral(Metrics m) {
    String p = m.posText.toLowerCase(Locale.ROOT);
    if (p.contains("meia central")) return true;
    return m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("volante"));
  }

  /**
   * Meia Esquerda ou Meia Direita — subposição lateral de meio-campo.
   * Detectada pelo posText principal vindo do Transfermarkt.
   * Não deve colidir com meia central, meia ofensivo nem volante (verificados antes).
   */
  private static boolean isMeiaEsquerdaDireita(Metrics m) {
    String p = m.posText.toLowerCase(Locale.ROOT);
    return p.contains("meia esquerda")
        || p.contains("meia direita")
        || p.contains("left mid")
        || p.contains("right mid")
        || p.contains("left midfielder")
        || p.contains("right midfielder")
        || p.equals("meia esq")
        || p.equals("meia dir");
  }

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

  /**
   * Detecta se o posText é uma categoria posicional GENÉRICA do Transfermarkt
   * (sem especificação de subposição).
   *
   * Retorna:
   *   "GENERIC_DEF" — "Defensor", "Defensores", "Defender", etc.
   *   "GENERIC_MID" — "Meio-Campo", "Meio Campo", "Midfield", "Mittelfeld"
   *   "GENERIC_ATK" — "Atacante", "Forward", "Stürmer" (sem sub como Ponta, CA, etc.)
   *   null          — posText tem subposição específica (não é genérico)
   *
   * A verificação de tokens negativos garante que "Lateral Defensivo" não
   * seja confundido com "Defensor".
   */
  private static String detectGenericCategory(String posText) {
    if (posText == null || posText.isBlank()) return null;
    String p = posText.toLowerCase(Locale.ROOT).trim();

    // ── Defensor genérico ──────────────────────────────────────────────────
    // Aceita: "Defensor", "Defensores", "Defender", "Abwehr", "Verteidiger"
    // Rejeita: qualquer texto que já contenha subposição específica
    boolean isDefToken = p.equals("defensor") || p.equals("defensores")
        || p.equals("defender") || p.equals("defenders")
        || p.equals("abwehr") || p.equals("verteidiger");
    boolean hasSpecificDef = p.contains("lateral") || p.contains("zagueiro")
        || p.contains("ala") || p.contains("back") || p.contains("cb")
        || p.contains("innenverteidiger") || p.contains("außenverteidiger");
    if (isDefToken && !hasSpecificDef) return "GENERIC_DEF";

    // ── Meio-campo genérico ────────────────────────────────────────────────
    // Aceita: "Meio-Campo", "Meio Campo", "Midfield", "Mittelfeld"
    // Rejeita: textos com subposição (volante, meia central, meia ofensivo, etc.)
    boolean isMidToken = p.equals("meio-campo") || p.equals("meio campo")
        || p.equals("midfield") || p.equals("mittelfeld");
    boolean hasSpecificMid = p.contains("volante") || p.contains("central")
        || p.contains("ofensivo") || p.contains("esquerda") || p.contains("direita")
        || p.contains("defensivo") || p.contains("attacking") || p.contains("defensive");
    if (isMidToken && !hasSpecificMid) return "GENERIC_MID";

    // ── Atacante genérico ──────────────────────────────────────────────────
    // Aceita: "Atacante", "Forward", "Stürmer", "Sturmer"
    // Rejeita: textos com subposição (ponta, centroavante, segundo atacante, etc.)
    boolean isAtkToken = p.equals("atacante") || p.equals("forward")
        || p.equals("stürmer") || p.equals("sturmer") || p.equals("forwards");
    boolean hasSpecificAtk = p.contains("ponta") || p.contains("centroavante")
        || p.contains("segundo") || p.contains("extremo") || p.contains("winger")
        || p.contains("centre-forward") || p.contains("mittelstürmer");
    if (isAtkToken && !hasSpecificAtk) return "GENERIC_ATK";

    return null; // posText tem subposição específica
  }

  /**
   * Quando um jogador tem estatísticas mas veio com posição genérica,
   * resolve para a subposição de scoring mais adequada com base no pos numérico
   * e nas métricas disponíveis.
   * Usado apenas no caminho scored (played > 0).
   */
  private static String resolveGenericToScoringProfile(String genericProfile, int pos, Metrics m) {
    switch (genericProfile) {
      case "GENERIC_DEF":
        // pos==1 → Lateral; pos==2 → Zagueiro; outro → tenta inferir
        if (pos == 1) return (m.participationPerGame >= 0.08) ? "LAT_OF" : "LAT_DEF";
        if (pos == 2) return isZagueiroOfensivo(m) ? "ZAG_OFENSIVO" : "ZAG_NORMAL";
        // pos ambíguo: sem stats ofensivas → Zagueiro Normal; com → Lateral Ofensivo
        return (m.participationPerGame >= 0.08) ? "LAT_OF" : "ZAG_NORMAL";

      case "GENERIC_MID":
        // Sem token de posição → assume Meia Central como default de scoring
        return "M_CENTRAL";

      case "GENERIC_ATK":
        // Heurística por métricas
        if (m.goalsPerGame >= 0.20 && m.height >= 1.85) return "ATAC_CA";
        if (m.assistsPerGame >= 0.08)                    return "ATAC_REC";
        return "ATAC_PONTA";

      default:
        return genericProfile; // não deveria chegar aqui
    }
  }

  // -------------------------------------------------------------------------
  // Global adjustments
  // -------------------------------------------------------------------------
  private static void applyGlobalAdjustments(Map<Integer, Double> scores, Metrics m) {
    if (m.age != null && m.age > 33) {
      modifyScore(scores, 11, 15.0); // Pas
      modifyScore(scores, 4, 15.0);  // Arm
      modifyScore(scores, 0, 10.0);  // Col
      modifyScore(scores, 1, 10.0);  // DPe
      modifyScore(scores, 3, 10.0);  // SGo
      modifyScore(scores, 13, -20.0); // Vel
      modifyScore(scores, 12, -10.0); // Res
    }
    if (m.age != null && m.age < 21) {
      modifyScore(scores, 13, 20.0); // Vel
      modifyScore(scores, 8, 15.0);  // Dri
      modifyScore(scores, 1, -15.0); // DPe
      modifyScore(scores, 12, -10.0); // Res
    }
    if (m.played < 10 && m.height >= 1.90) {
      modifyScore(scores, 3, 10.0); // SGo
      modifyScore(scores, 5, 10.0); // Cab
    }
  }

  private static void modifyScore(Map<Integer, Double> scores, int idx, double delta) {
    scores.put(idx, scores.getOrDefault(idx, 0.0) + delta);
  }

  // -------------------------------------------------------------------------
  // Scoring functions (as per manual)
  // -------------------------------------------------------------------------

  // Goleiro
  // ─── Redesign v5.3 ──────────────────────────────────────────────────────────
  // Problema v5.2: SGo dominava 54% dos goleiros porque qualquer GK >= 1.90m com
  // 60+ jogos recebia ~75 pts automaticamente, sem nenhum limitador de qualidade.
  // Com 94% dos goleiros profissionais acima de 1.85m, SGo virava característica
  // padrão mesmo para goleiros de baixíssima qualidade (gpg > 1.50).
  //
  // Redesign v5.3:
  //   Col  → CSR alto + GPG baixo + experiência (posicionamento = clean sheets)
  //   Ref  → GPG baixo/médio + JUVENTUDE (reflexos; range de GPG expandido até 1.45)
  //   DPe  → IDADE >= 27 + JOGOS acumulados (veterano; usa penaltySaveRate quando
  //           disponível — diferenciador direto e mais preciso que idade/jogos)
  //   SGo  → ALTURA >= 1.87 + QUALIDADE MÍNIMA (gpg <= 1.50 obrigatório;
  //           bloqueio total para gpg > 1.50 — goleiro ruim não comanda a área)
  //
  // Resultado: distribuição equilibrada — SGo ~35%, Ref ~30%, DPe ~23%, Col ~12%
  // ────────────────────────────────────────────────────────────────────────────

  private static double scoreCol(Metrics m) {
    double pontos = 0;
    // CSR é o indicador primário: clean sheets refletem posicionamento consistente
    if (m.cleanSheetRate >= 0.42) pontos += 55;
    else if (m.cleanSheetRate >= 0.35) pontos += 40;
    else if (m.cleanSheetRate >= 0.28) pontos += 22;
    else if (m.cleanSheetRate >= 0.22) pontos += 10;
    // GPG complementa: posicionamento reduz gols sofridos
    if (m.goalsConcededPerGame <= 0.90) pontos += 40;
    else if (m.goalsConcededPerGame <= 1.05) pontos += 28;
    else if (m.goalsConcededPerGame <= 1.15) pontos += 18;
    else if (m.goalsConcededPerGame <= 1.25) pontos += 8;
    // Experiência: posicionamento se aprende com jogos
    if (m.played >= 200) pontos += 20;
    else if (m.played >= 100) pontos += 12;
    // Sinergia: goleiro genuinamente bom defensivamente tem Col e Ref elevados
    if (m.cleanSheetRate >= 0.30 && m.goalsConcededPerGame <= 1.10) pontos += 20;
    else if (m.cleanSheetRate >= 0.25 && m.goalsConcededPerGame <= 1.20) pontos += 10;
    return pontos;
  }

  private static double scoreRef(Metrics m) {
    double pontos = 0;
    // GPG com range expandido até 1.45: goleiro de time fraco pode ter ótimos reflexos
    if (m.goalsConcededPerGame <= 0.90)      pontos += 50;
    else if (m.goalsConcededPerGame <= 1.05) pontos += 38;
    else if (m.goalsConcededPerGame <= 1.15) pontos += 28;
    else if (m.goalsConcededPerGame <= 1.25) pontos += 18;
    else if (m.goalsConcededPerGame <= 1.35) pontos += 10;
    else if (m.goalsConcededPerGame <= 1.45) pontos += 5;
    // Dois perfis de longevidade:
    // Goleiros muito altos (>= 1.92m) perdem reflexo lateral mais cedo
    // Goleiros de altura normal: longevidade maior, escala estendida até 37
    if (m.height >= 1.92) {
      if (m.age != null && m.age > 0 && m.age <= 25)      pontos += 30;
      else if (m.age != null && m.age > 0 && m.age <= 27) pontos += 18;
      else if (m.age != null && m.age > 0 && m.age <= 29) pontos += 8;
    } else {
      if (m.age != null && m.age > 0 && m.age <= 25)      pontos += 30;
      else if (m.age != null && m.age > 0 && m.age <= 28) pontos += 22;
      else if (m.age != null && m.age > 0 && m.age <= 31) pontos += 14;
      else if (m.age != null && m.age > 0 && m.age <= 34) pontos += 8;
      else if (m.age != null && m.age > 0 && m.age <= 37) pontos += 4;
    }
    if (m.cleanSheetRate >= 0.35) pontos += 12;
    else if (m.cleanSheetRate >= 0.28) pontos += 7;
    if (m.played >= 60) pontos += 10;
    return pontos;
  }

  private static double scoreDPe(Metrics m) {
    double pontos = 0;
    // IDADE é o indicador primário — DPe é mérito de veterano
    if (m.age != null && m.age >= 33)      pontos += 50;
    else if (m.age != null && m.age >= 30) pontos += 32;
    else if (m.age != null && m.age >= 27) pontos += 15;
    // Jogos acumulados: principal indicador de experiência total
    if (m.played >= 300)      pontos += 45;
    else if (m.played >= 200) pontos += 32;
    else if (m.played >= 120) pontos += 18;
    else if (m.played >= 60)  pontos += 8;
    // Pequeno bônus de qualidade e disciplina
    if (m.goalsConcededPerGame <= 1.15) pontos += 10;
    if (m.cleanSheetRate >= 0.25) pontos += 8;

    // ── Taxa de defesa de pênaltis (quando o dado está disponível) ────────────
    // Este é o diferenciador DIRETO de DPe: o goleiro realmente defende pênaltis.
    // Amostra mínima de 5 para evitar que 1/1 ou 2/2 (sortudo) infle o score.
    // Referência: média histórica da liga ≈ 25-28% de defesa;
    //   >= 40%: elite (Emiliano Martínez, Alisson nível Copa)
    //   >= 33%: muito bom (1 em 3)
    //   >= 25%: acima da média
    //   >= 15%: abaixo da média mas com experiência
    if (m.penFaced >= 5) {
      if      (m.penaltySaveRate >= 0.40) pontos += 40;
      else if (m.penaltySaveRate >= 0.33) pontos += 28;
      else if (m.penaltySaveRate >= 0.25) pontos += 18;
      else if (m.penaltySaveRate >= 0.15) pontos += 8;
      // < 0.15: abaixo da média — sem bônus mas sem penalidade
      // Bônus de volume: já enfrentou muitos pênaltis = mais experiência
      if (m.penFaced >= 25) pontos += 12;
      else if (m.penFaced >= 15) pontos += 6;
    } else if (m.penFaced > 0) {
      // Amostra pequena (1-4): conta apenas o fato de ter algum dado
      if (m.penaltySaveRate >= 0.33) pontos += 10;
      else if (m.penaltySaveRate > 0) pontos += 4;
    }
    // penFaced == 0: sem dados de pênaltis — score depende só de idade/jogos
    return pontos;
  }

  private static double scoreSGo(Metrics m) {
    // Altura mínima elevada: 1.87m — somente goleiros realmente altos comandam a área
    if (m.height < 1.87) return 0;
    // QUALIDADE MÍNIMA obrigatória: goleiro ruim não comanda a área com segurança.
    // GPG > 1.50 → bloqueio total (não faz sentido SGo para quem sofre muito)
    if (m.goalsConcededPerGame > 1.50) return 0;
    double pontos = 0;
    // ALTURA é o indicador primário
    if (m.height >= 1.93) pontos += 55;
    else if (m.height >= 1.90) pontos += 40;
    else if (m.height >= 1.87) pontos += 20;
    // Qualidade defensiva amplifica SGo: saída segura gera cleansheets
    if (m.goalsConcededPerGame <= 1.00)      pontos += 30;
    else if (m.goalsConcededPerGame <= 1.15) pontos += 18;
    else if (m.goalsConcededPerGame <= 1.25) pontos += 8;
    else if (m.goalsConcededPerGame <= 1.35) pontos += 2;
    // gpg 1.35~1.50: bônus zero (apenas altura conta — perfil limítrofe)
    if (m.cleanSheetRate >= 0.35) pontos += 20;
    else if (m.cleanSheetRate >= 0.28) pontos += 12;
    else if (m.cleanSheetRate >= 0.22) pontos += 6;
    // Experiência para sair com segurança
    if (m.played >= 150) pontos += 18;
    else if (m.played >= 80)  pontos += 10;
    else if (m.played >= 40)  pontos += 4;
    // Goleiro maduro (não muito jovem, não muito velho)
    if (m.age != null && m.age >= 25 && m.age <= 37) pontos += 12;
    // Assistências = saída de bola ativa / chutão longo que vira jogada
    if (m.assists > 0) pontos += 15;
    return pontos;
  }

  // Zagueiro
  private static double scoreDesZag(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.15) pontos += 35;
    else if (m.yellowPerGame >= 0.10) pontos += 25;
    if (m.disciplineIndex >= 0.70) pontos += 20;
    // Ajustado: muito alto (>=1.88) recebe mais; moderadamente alto (>=1.85) recebe menos.
    if (m.height >= 1.88) pontos += 22;
    else if (m.height >= 1.85) pontos += 12;
    if (m.played >= 150) pontos += 10;
    // Bônus combo: zagueiro alto E agressivo — arquétipo Des/Cab (vai ao duelo aéreo
    // e comete faltas). Des precisa superar Mar para que Des/Cab apareça naturalmente
    // em ZAG_OFENSIVO em vez de sempre Mar/Cab.
    if (m.height >= 1.86 && m.yellowPerGame >= 0.15) pontos += 20;
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
    if (m.played >= 300 && m.goalsPerGame >= 0.06) pontos += 20;
    return pontos;
  }

  private static double scoreVelZag(Metrics m) {
    double pontos = 0;
    if (m.age != null && m.age > 0 && m.age <= 27) pontos += 30;
    if (m.height > 0 && m.height <= 1.85) pontos += 20;
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

  // Lateral
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
    if (m.goalsPerGame >= 0.05) pontos -= 15;
    if (m.participationPerGame >= 0.10) pontos -= 10;
    pontos -= 40; // penalidade massiva
    return Math.max(0, pontos);
  }

  private static double scoreDesLat(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.10) pontos += 30;
    if (isLateralDefensivo(m)) pontos += 25;
    if (m.height >= 1.78) pontos += 15;
    if (m.played >= 120) pontos += 15;
    if (m.participationPerGame >= 0.08) pontos -= 15;
    pontos -= 40;
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

  // Volante
  private static double scoreDesVol(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.15) pontos += 35;
    else if (m.yellowPerGame >= 0.10) pontos += 25;
    if (m.disciplineIndex >= 0.70) pontos += 20;
    if (m.goalsPerGame <= 0.05) pontos += 15;
    if (m.played >= 150) pontos += 10;
    // Bônus distribuidor: volante destruidor que também assiste bem tem perfil
    // Des/Pas — o "destroyer-playmaker". Sem este bônus, Mar sempre vencia Des
    // por causa dos bônus de regularidade/disciplina em scoreMarVol (v5.1).
    if (m.assistsPerGame >= 0.06) pontos += 30;
    else if (m.assistsPerGame >= 0.04) pontos += 15;
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
    // Pas é o indicador principal do volante distribuidor.
    // Des/Pas e Mar/Pas devem aparecer para dois perfis distintos:
    //   1. Volante com número considerável de assistências (apg >= 0.06) — mesmo
    //      sem muitos gols, a criação de jogo justifica o par.
    //   2. Volante com alta participação geral (ppg elevado) — complemento do (1).
    double pontos = 0;
    if (m.assistsPerGame >= 0.08) pontos += 55;
    else if (m.assistsPerGame >= 0.05) pontos += 42;
    else if (m.assistsPerGame >= 0.03) pontos += 30;
    // Bônus direto por assistências consideráveis — independente de gols.
    // Garante que Des/Pas e Mar/Pas apareçam para volantes criadores mesmo
    // quando goalsPerGame é zero ou baixo.
    if (m.assistsPerGame >= 0.06) pontos += 20;
    else if (m.assistsPerGame >= 0.04) pontos += 12;
    // Participação reforça (mas não substitui assists como critério primário)
    if (m.participationPerGame >= 0.15) pontos += 30;
    else if (m.participationPerGame >= 0.10) pontos += 20;
    else if (m.participationPerGame >= 0.07) pontos += 12;
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("meia central")))
      pontos += 20;
    if (m.disciplineIndex >= 0.80) pontos += 15;
    return pontos;
  }

  private static double scoreFinVol(Metrics m) {
    // Fin só deve aparecer (Des/Fin ou Mar/Fin) para volantes com números de gol
    // realmente relevantes. Thresholds elevados para evitar que qualquer volante
    // com poucos gols receba Fin como característica secundária.
    double pontos = 0;
    if (m.goalsPerGame >= 0.12) pontos += 55;      // goleador atípico para a posição
    else if (m.goalsPerGame >= 0.08) pontos += 38;  // muito bom
    else if (m.goalsPerGame >= 0.05) pontos += 20;  // razoável — ainda competitivo
    // Abaixo de 0.05 não pontua: Mar/Fin e Des/Fin não devem aparecer para
    // volantes com taxa de gol baixa ou mediana.
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("meia ofensivo")))
      pontos += 20;
    // Pênaltis cobrados indicam vocação ofensiva, mas não sobrepõem a taxa de gol
    if (m.penaltyGoals >= 3) pontos += 12;
    else if (m.penaltyGoals > 0) pontos += 5;
    if (m.mpg > 0 && m.mpg < 1000) pontos += 15;  // threshold mais rigoroso (era 1500)
    else if (m.mpg > 0 && m.mpg < 1500) pontos += 7;
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
    // Threshold mais rigoroso: apenas volantes muito jovens (<=25) recebem bônus máximo.
    // Entre 26-27 o bônus cai pela metade, evitando que Des/Vel apareça por padrão
    // em qualquer jovem com participação razoável.
    if (m.age != null && m.age > 0 && m.age <= 25) pontos += 30;
    else if (m.age != null && m.age > 0 && m.age <= 27) pontos += 15;
    if (m.participationPerGame >= 0.10) pontos += 25;
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("meia")))
      pontos += 20;
    // REMOVIDO: bônus de altura (height <= 1.80 não é indicador de Vel num volante —
    // qualquer mediano tem <=1.80, tornava Vel dominante de forma automática).
    return pontos;
  }

  // Meia
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
    // Meia goleador (gpg >= 0.15) é menos Arm e mais Fin — leve penalidade para
    // liberar Fin como característica principal nesses casos (v5.1).
    if (m.goalsPerGame >= 0.15) pontos -= 15;
    return Math.max(0, pontos);
  }

  private static double scorePasMeia(Metrics m) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.10) pontos += 35;
    else if (m.assistsPerGame >= 0.07) pontos += 25;
    if (m.disciplineIndex >= 0.80) pontos += 20;
    if (m.minsPerGame >= 75) pontos += 15;
    // Bônus arquétipo Fin/Pas: meia ofensivo clássico "10 goleador".
    // Threshold elevado de 0.12 → 0.17: com 0.12, quase todo meia produtivo
    // ativava o bônus, fazendo Pas sempre bater Fin/Dri no segundo slot e
    // impedindo Arm/Fin de aparecer para os meias com perfil correto.
    if (m.goalsPerGame >= 0.17 && m.assistsPerGame >= 0.07) pontos += 35;
    return pontos;
  }

  private static double scoreVelMeia(Metrics m, boolean ofensivo) {
    double pontos = 0;
    // Guard age > 0: age=0 no JSON significa "não cadastrado" — não deve
    // disparar o bônus de "jovem" que contamina a distribuição de características
    // em ligas com dados escassos (ex: Nova Zelândia).
    if (m.age != null && m.age > 0 && m.age <= 27) pontos += 30;
    if (m.participationPerGame >= 0.15) pontos += 25;
    // Guard height > 0: height=null no JSON vira 0.0 em double Java,
    // e 0.0 <= 1.78 seria TRUE — dando bônus de "baixo" para todos sem dado.
    if (m.height > 0 && m.height <= 1.78) pontos += 20;
    if (ofensivo) pontos += 15;
    return pontos;
  }

  private static double scoreDriMeia(Metrics m, boolean ofensivo) {
    double pontos = 0;
    // Threshold de participação elevado de 0.20 → 0.25 para o tier máximo:
    // com 0.20, qualquer meia que some gols e assists regularmente ganhava
    // +35 de Dri e dominava Fin/Dri. Com 0.25, Dri fica reservado para
    // jogadores realmente dominantes em participação.
    if (m.participationPerGame >= 0.25) pontos += 35;
    else if (m.participationPerGame >= 0.20) pontos += 20;
    // Guards age > 0 e height > 0: mesma razão que scoreVelMeia.
    if (m.age != null && m.age > 0 && m.age <= 28) pontos += 25;
    if (m.height > 0 && m.height <= 1.75) pontos += 20;
    if (m.assistsPerGame >= 0.10) pontos += 20;
    // Penalidade para meia ofensivo goleador antecipada de 0.15 → 0.12:
    // meias com perfil goleador moderado (gpg >= 0.12) já têm Fin/Arm como
    // destino natural — Dri não deve vencer o segundo slot nesse range.
    if (ofensivo && m.goalsPerGame >= 0.12) pontos -= 20;
    return Math.max(0, pontos);
  }

  private static double scoreFinMeia(Metrics m, boolean ofensivo) {
    double pontos = 0;
    // Tiers escalonados: Fin precisa vencer Arm para meias muito goleadores (v5.1).
    // Com apenas dois tiers (≥0.12:+40), o teto de 75 pts era sempre superado
    // por scoreArmMeia, impedindo Fin/Pas de aparecer para scorers dominantes.
    if (m.goalsPerGame >= 0.25)      pontos += 70;
    else if (m.goalsPerGame >= 0.20) pontos += 55;
    else if (m.goalsPerGame >= 0.15) pontos += 45;
    else if (m.goalsPerGame >= 0.12) pontos += 35;
    else if (m.goalsPerGame >= 0.08) pontos += 25;
    if (ofensivo) pontos += 20;
    if (m.penaltyGoals > 0) pontos += 15;
    if (m.mpg > 0 && m.mpg < 1000) pontos += 15;
    return pontos;
  }

  private static double scoreDesMeia(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.10) pontos += 25;
    if (!isMeiaOfensivo(m)) pontos += 20; // meia central
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("volante")))
      pontos += 25;
    if (m.goalsPerGame <= 0.05) pontos += 10;
    if (m.participationPerGame >= 0.15) pontos -= 20;
    return Math.max(0, pontos);
  }

  // Atacante
  private static double scoreFinAtac(Metrics m, boolean ponta) {
    double pontos = 0;
    if (m.goalsPerGame >= 0.30) pontos += 50;
    else if (m.goalsPerGame >= 0.20) pontos += 40;
    else if (m.goalsPerGame >= 0.15) pontos += 30;
    else if (m.goalsPerGame >= 0.10) pontos += 20;
    if (m.goalsPerGame >= 0.19) pontos += 50;
    else if (m.goalsPerGame >= 0.15) pontos += 35;
    if (m.mpg > 0 && m.mpg < 300) pontos += 25;
    else if (m.mpg > 0 && m.mpg < 500) pontos += 15;
    if (m.penaltyGoals >= 3) pontos += 10;
    // Bônus de contexto: pontas com taxa de gol real merecem Fin/Vel em vez de Vel/Dri.
    // Aumentado de +30 para +50 (v5.2): com +30, Dri ainda vencia o slot secundário
    // porque ppg >= 0.25 dava +30 ao Dri de forma automática (ex.: Doku, Savinho).
    if (ponta && m.goalsPerGame >= 0.10) pontos += 50;
    return pontos;
  }

  private static double scoreVelAtac(Metrics m, boolean ponta, boolean centroavante) {
    double pontos = 0;
    if (m.age != null && m.age > 0 && m.age <= 28) pontos += 35;
    if (ponta) pontos += 30;
    if (m.height > 0 && m.height <= 1.80) pontos += 20;
    if (m.participationPerGame >= 0.25) pontos += 15;
    if (centroavante && m.age != null && m.age > 0 && m.age <= 25) pontos += 25;
    if (ponta && m.assistsPerGame >= 0.10 && m.height > 0 && m.height <= 1.78) pontos -= 15;
    // Ponta scorer-dominante (gpg >= 0.15 e mais gols que assists): Vel é o melhor
    // par secundário com Fin. Sem este bônus, Dri vencia o slot apesar da penalidade,
    // gerando Fin/Dri em vez de Fin/Vel ou Vel/Fin para pontas goleadoras (v5.1).
    if (ponta && m.goalsPerGame >= 0.15 && m.goalsPerGame > m.assistsPerGame)
      pontos += 25;
    return Math.max(0, pontos);
  }

  private static double scoreCabAtac(Metrics m, boolean centroavante) {
    double pontos = 0;
    if (m.height >= 1.85) pontos += 35;
    if (centroavante) pontos += 30;
    if (m.goalsPerGame >= 0.15) pontos += 25;
    if (m.age != null && m.age >= 26) pontos += 10;
    if (centroavante && m.goalsPerGame >= 0.25) pontos += 20;
    return pontos;
  }

  private static double scoreDriAtac(Metrics m, boolean ponta, boolean segundo) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.10) pontos += 40;
    else if (m.assistsPerGame >= 0.08) pontos += 30;
    if (m.age != null && m.age <= 27) pontos += 25;
    if (m.height <= 1.78) pontos += 25;
    // Segundo atacante mantém bônus flat — Dri é característica central do perfil.
    // Ponta REMOVIDO do bônus flat: era responsável pela dominância de Vel/Dri em
    // pontas com qualquer perfil, impedindo Fin/Vel de aparecer.
    if (segundo) pontos += 20;
    // Ponta dribbladora com muita assistência ainda recebe bônus, mas menor e condicional.
    if (ponta && m.assistsPerGame >= 0.12) pontos += 15;
    // Bônus ppg para PONTA só se gpg < 0.10: ponta que marca razoavelmente (gpg >= 0.10)
    // não deve ter Dri inflado por participação alta — afinal, a participação vem dos
    // gols, não dos dribles. Sem essa condição, Doku (gpg=0.142) e Savinho (gpg=0.120)
    // recebiam +30 no Dri via ppg>=0.25 e ficavam Vel/Dri em vez de Vel/Fin (v5.2).
    if (ponta && m.participationPerGame >= 0.25 && m.goalsPerGame < 0.10) pontos += 30;
    if (m.goalsPerGame >= 0.15 && m.assistsPerGame >= 0.08) pontos += 25;
    // Penalidade baseada no ratio gpg/apg (v5.2):
    // A penalidade antes era flat (-40 para qualquer gpg >= 0.15), o que
    // prejudicava pontas como Jhon Arias (gpg=0.162, apg=0.164, ratio=0.99)
    // que criam tanto quanto marcam — para esses, Fin/Dri é mais representativo.
    //
    // Lógica do ratio:
    //   ratio > 1.5  → scorer dominante (ex.: Sorriso 3.73, Paulinho 3.00)
    //                  → penalidade forte, Vel vence Dri → Fin/Vel
    //   ratio > 1.0  → leve scorer (ex.: Ramón Sosa 1.29, Semenyo 1.71)
    //                  → penalidade moderada, Vel ainda costuma vencer → Fin/Vel
    //   ratio <= 1.0 → criador-scorer (apg >= gpg, ex.: Jhon Arias 0.99, Doku 0.72)
    //                  → sem penalidade extra, Dri competitivo → Fin/Dri possível
    if (ponta && m.goalsPerGame >= 0.15) {
      double ratio = m.goalsPerGame / Math.max(0.001, m.assistsPerGame);
      if (ratio > 1.5)      pontos -= 40;  // scorer dominante
      else if (ratio > 1.0) pontos -= 20;  // leve scorer
      // ratio <= 1.0: sem penalidade adicional
    } else if (ponta && m.goalsPerGame >= 0.10) {
      pontos -= 25;
    }
    return Math.max(0, pontos);
  }

  private static double scorePasAtac(Metrics m, boolean segundo) {
    double pontos = 0;
    if (m.assistsPerGame >= 0.12) pontos += 40;
    else if (m.assistsPerGame >= 0.08) pontos += 30;
    if (segundo) pontos += 25;
    if (m.participationPerGame >= 0.30) pontos += 15;
    return pontos;
  }

  private static double scoreResAtac(Metrics m, boolean centroavante) {
    double pontos = 0;
    if (m.minsPerGame >= 85) pontos += 35;
    else if (m.minsPerGame >= 80) pontos += 25;
    if (m.regularity >= 0.75) pontos += 20;
    if (centroavante) pontos += 15;
    return pontos;
  }

  // -------------------------------------------------------------------------
  // Combinações permitidas por perfil de subposição
  //
  // Esta é a fonte de verdade para os pares válidos.
  // Manutenção: ao adicionar/remover pares, atualizar também as tabelas
  // de design doc correspondentes.
  // -------------------------------------------------------------------------
  private static Set<String> getAllowedCombinations(String posProfile) {
    Set<String> set = new HashSet<>();
    switch (posProfile) {

      case "GK":
        set.addAll(List.of(
            "Col/Ref","Ref/Col","Ref/DPe","DPe/Ref",
            "SGo/Ref","Ref/SGo","Col/DPe","DPe/Col",
            "SGo/DPe","DPe/SGo","SGo/Col","Col/SGo"));
        break;

      case "LAT_DEF":
        // Lateral Defensivo: foco em Mar/Cru + Des. Vel/Pas aceito para laterais
        // que ligam o jogo mesmo com perfil mais defensivo.
        set.addAll(List.of(
            "Mar/Cru","Cru/Mar","Mar/Fin","Mar/Vel","Vel/Mar",
            "Des/Cru","Cru/Des","Vel/Pas","Pas/Vel"));
        break;

      case "LAT_OF":
        // Lateral Ofensivo: foco em Cru + Vel. Vel/Pas compartilhado com LAT_DEF.
        set.addAll(List.of(
            "Cru/Vel","Vel/Cru","Cru/Fin","Cru/Pas","Vel/Pas"));
        break;

      case "ZAG_NORMAL":
        // "Mas/Pas" era typo — corrigido para Mar/Pas.
        set.addAll(List.of(
            "Des/Mar","Mar/Des","Des/Pas","Mar/Pas","Des/Res","Mar/Res"));
        break;

      case "ZAG_OFENSIVO":
        set.addAll(List.of(
            "Mar/Vel","Des/Cab","Cab/Des","Mar/Cab","Cab/Mar"));
        break;

      case "VOL":
        // Todos os 9 pares de Volante confirmados.
        set.addAll(List.of(
            "Des/Mar","Mar/Des","Des/Pas","Mar/Pas",
            "Mar/Res","Mar/Fin","Des/Fin","Des/Vel","Des/Res"));
        break;

      case "M_CENTRAL":
        // Pas/Vel e Vel/Pas MIGRADOS para M_ESQUERDA_DIREITA.
        set.addAll(List.of(
            "Arm/Vel","Arm/Dri","Dri/Pas","Pas/Dri","Des/Vel","Arm/Pas"));
        break;

      case "M_ESQUERDA_DIREITA":
        // NOVO perfil: Meia Esquerda / Meia Direita.
        // Inclui Pas/Vel e Vel/Pas (migrados de M_CENTRAL).
        set.addAll(List.of(
            "Pas/Vel","Vel/Pas","Arm/Vel","Dri/Pas","Pas/Dri","Des/Vel"));
        break;

      case "M_OFENSIVO":
        // Dri/Fin REMOVIDO por decisão de design (v5.0).
        set.addAll(List.of(
            "Arm/Fin","Arm/Pas","Fin/Pas","Fin/Arm","Dri/Pas","Fin/Dri"));
        break;

      case "ATAC_REC":
        // Pas/Fin ADICIONADO. Fin/Pas migrado de ATAC_CA para cá.
        set.addAll(List.of(
            "Pas/Fin","Fin/Pas","Dri/Fin","Dri/Pas"));
        break;

      case "ATAC_CA":
        // Fin/Pas REMOVIDO — migrado para ATAC_REC.
        set.addAll(List.of(
            "Fin/Cab","Cab/Fin","Fin/Dri","Cab/Vel","Fin/Res"));
        break;

      case "ATAC_PONTA":
        set.addAll(List.of(
            "Vel/Fin","Fin/Vel","Vel/Dri","Fin/Dri","Dri/Fin"));
        break;

      default:
        break;
    }
    return set;
  }

  /**
   * Retorna um pool unificado e deduplicado de pares para um grupo posicional genérico.
   * Usado quando a subposição não pode ser determinada (posição genérica do Transfermarkt
   * como "Defensor", "Meio-Campo", etc.) e o jogador não tem estatísticas.
   */
  private static Set<String> getGenericGroupPool(int pos) {
    Set<String> pool = new LinkedHashSet<>();
    switch (pos) {
      case 1: // Lateral genérico
        pool.addAll(getAllowedCombinations("LAT_DEF"));
        pool.addAll(getAllowedCombinations("LAT_OF"));
        break;
      case 2: // Zagueiro genérico
        pool.addAll(getAllowedCombinations("ZAG_NORMAL"));
        pool.addAll(getAllowedCombinations("ZAG_OFENSIVO"));
        break;
      case 3: // Meio-campo genérico
        pool.addAll(getAllowedCombinations("VOL"));
        pool.addAll(getAllowedCombinations("M_CENTRAL"));
        pool.addAll(getAllowedCombinations("M_ESQUERDA_DIREITA"));
        pool.addAll(getAllowedCombinations("M_OFENSIVO"));
        break;
      case 4: // Atacante genérico
        pool.addAll(getAllowedCombinations("ATAC_REC"));
        pool.addAll(getAllowedCombinations("ATAC_CA"));
        pool.addAll(getAllowedCombinations("ATAC_PONTA"));
        break;
      default: // GK (pos==0) não precisa de pool genérico, mas cobre qualquer caso
        pool.addAll(getAllowedCombinations("GK"));
        break;
    }
    return pool;
  }

  // -------------------------------------------------------------------------
  // Detecção de perfil de subposição (separada para reutilização no fallback)
  // -------------------------------------------------------------------------

  /**
   * Detecta o perfil de subposição baseado em pos + posText + secundárias + métricas.
   * Retorna uma string de perfil compatível com getAllowedCombinations().
   * Chamado tanto para jogadores com stats (scored path) quanto sem stats (fallback path).
   */
  /**
   * Detecta o perfil de subposição baseado em pos + posText + secundárias + métricas.
   *
   * Ordem de prioridade:
   *   1. Categorias genéricas do Transfermarkt ("Defensor", "Meio-Campo", "Atacante")
   *      → retorna GENERIC_DEF / GENERIC_MID / GENERIC_ATK
   *   2. Subposições específicas detectadas por tokens no posText
   *   3. Heurísticas por métricas quando tokens não são conclusivos
   */
  private static String detectProfile(Metrics m) {

    // 1. Categoria genérica tem prioridade — deve ser verificada ANTES do routing por pos,
    //    pois o pos numérico (vindo do PositionUtil) pode ser impreciso para textos genéricos.
    String generic = detectGenericCategory(m.posText);
    if (generic != null) return generic;

    int pos = m.pos;

    if (pos == 0) return "GK";

    if (pos == 1) { // Lateral
      boolean defensivo = isLateralDefensivo(m);
      boolean ofensivo  = isLateralOfensivo(m);
      if (defensivo) return "LAT_DEF";
      if (ofensivo)  return "LAT_OF";
      // Sem secundária clara: "ala" → ofensivo por padrão; caso contrário, heurística
      String pLow = m.posText.toLowerCase(Locale.ROOT);
      if (pLow.contains("ala")) return "LAT_OF";
      return (m.participationPerGame >= 0.08) ? "LAT_OF" : "LAT_DEF";
    }

    if (pos == 2) { // Zagueiro
      return isZagueiroOfensivo(m) ? "ZAG_OFENSIVO" : "ZAG_NORMAL";
    }

    if (pos == 3) { // Meio-campo
      // Ordem de prioridade: Volante → Meia Ofensivo → Meia E/D → Meia Central (default)
      if (isVolante(m))             return "VOL";
      if (isMeiaOfensivo(m))        return "M_OFENSIVO";
      if (isMeiaEsquerdaDireita(m)) return "M_ESQUERDA_DIREITA";
      if (isMeiaCentral(m))         return "M_CENTRAL";
      return "M_CENTRAL"; // default para meias não classificados
    }

    if (pos == 4) { // Atacante
      boolean ca    = isCentroavante(m);
      boolean ponta = isPonta(m);
      boolean seg   = isSegundoAtacante(m);
      if (ca)    return "ATAC_CA";
      if (ponta) return "ATAC_PONTA";
      if (seg)   return "ATAC_REC";
      // Heurística quando nenhum token detectado no posText
      if (m.goalsPerGame >= 0.20 && m.height >= 1.85) return "ATAC_CA";
      if (m.assistsPerGame >= 0.08)                    return "ATAC_REC";
      return "ATAC_PONTA";
    }

    return "ATAC_CA"; // último recurso (não deveria ocorrer)
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
      int penFaced,
      int penSaved,
      Integer idade,
      double heightM) {

    Metrics m = Metrics.from(
        pos, posText, secondaryPositions,
        matchesRelated, matchesPlayed, goals, assists, ownGoals,
        fromBench, substituted, yellow, yellowRed, red,
        penaltyGoals, minutesPerGoal, minutesPlayed,
        goalsConceded, cleanSheets, penFaced, penSaved, idade, heightM);

    // Detecta perfil antecipadamente (usado tanto no fallback quanto no scored path)
    String profile = detectProfile(m);

    if (m.played == 0) {
      // Sem estatísticas: sorteia aleatoriamente do pool da subposição/grupo detectado
      int[] fallback = getFallbackRandom(profile, pos);
      if (DEBUG) {
        System.out.println("[DEBUG] No matches played — random fallback: "
            + idxToName(fallback[0]) + "/" + idxToName(fallback[1])
            + " for profile=" + profile);
      }
      return fallback;
    }

    // Posição genérica com stats → resolve para subposição de scoring específica.
    // O pool de pares permitidos (allowed combinations) também é atualizado para
    // refletir a subposição resolvida. resolvedPos é calculado a partir do subperfil
    // resolvido para garantir consistência com a característica que será gravada.
    int resolvedPos = pos; // será sobrescrito para posições genéricas
    if (profile.startsWith("GENERIC_")) {
      if (DEBUG) System.out.println("[DEBUG] Generic profile '" + profile
          + "' resolved for scoring (pos=" + pos + ")");
      profile = resolveGenericToScoringProfile(profile, pos, m);
      resolvedPos = profileToPos(profile);
      if (DEBUG) System.out.println("[DEBUG] resolvedPos=" + resolvedPos
          + " para perfil resolvido=" + profile);
    }

    Map<Integer, Double> scores = new HashMap<>();

    if (pos == 0) { // Goleiro
      scores.put(0, scoreCol(m));
      scores.put(2, scoreRef(m));
      scores.put(1, scoreDPe(m));
      scores.put(3, scoreSGo(m));
    }
    else if (pos == 2) { // Zagueiro
      scores.put(7, scoreDesZag(m));
      scores.put(10, scoreMarZag(m));
      scores.put(5, scoreCabZag(m));
      scores.put(13, scoreVelZag(m));
      scores.put(11, scorePasZag(m));
      scores.put(12, scoreResZag(m));
    }
    else if (pos == 1) { // Lateral
      scores.put(6, scoreCruLat(m));
      scores.put(13, scoreVelLat(m));
      scores.put(11, scorePasLat(m));
      scores.put(10, scoreMarLat(m));
      scores.put(7, scoreDesLat(m));
      scores.put(9, scoreFinLat(m));
    }
    else if (pos == 3) { // Meia / Volante
      if (profile.equals("VOL")) {
        scores.put(7, scoreDesVol(m));
        scores.put(10, scoreMarVol(m));
        scores.put(11, scorePasVol(m));
        scores.put(9, scoreFinVol(m));
        scores.put(12, scoreResVol(m));
        scores.put(13, scoreVelVol(m));
      } else if (profile.equals("M_OFENSIVO")) {
        scores.put(4, scoreArmMeia(m, true));
        scores.put(11, scorePasMeia(m));
        scores.put(13, scoreVelMeia(m, true));
        scores.put(8, scoreDriMeia(m, true));
        scores.put(9, scoreFinMeia(m, true));
        scores.put(7, scoreDesMeia(m));
      } else {
        // M_CENTRAL, M_ESQUERDA_DIREITA — mesmo pool de scoring; o profile restringe pares válidos
        scores.put(4, scoreArmMeia(m, false));
        scores.put(11, scorePasMeia(m));
        scores.put(13, scoreVelMeia(m, false));
        scores.put(8, scoreDriMeia(m, false));
        scores.put(9, scoreFinMeia(m, false));
        scores.put(7, scoreDesMeia(m));
      }
    }
    else if (pos == 4) { // Atacante
      boolean ca    = profile.equals("ATAC_CA");
      boolean ponta = profile.equals("ATAC_PONTA");
      boolean seg   = profile.equals("ATAC_REC");

      scores.put(9,  scoreFinAtac(m, ponta));
      scores.put(13, scoreVelAtac(m, ponta, ca));
      scores.put(5,  scoreCabAtac(m, ca));
      scores.put(8,  scoreDriAtac(m, ponta, seg));
      scores.put(11, scorePasAtac(m, seg));
      scores.put(12, scoreResAtac(m, ca));
    }

    // Ajustes globais (veterano, jovem, biotipo)
    applyGlobalAdjustments(scores, m);

    // Ordena scores de forma decrescente
    List<Map.Entry<Integer, Double>> sorted = scores.entrySet().stream()
        .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
        .collect(Collectors.toList());

    if (DEBUG) {
      System.out.println("[DEBUG] Scores for " + m.posText + " [" + profile + "]: " + sorted);
    }

    if (sorted.size() < 2) {
      return getFallbackRandom(profile, pos);
    }

    int first  = sorted.get(0).getKey();
    int second = sorted.get(1).getKey();

    // ── Limiar de baixa confiança ─────────────────────────────────────────────
    // Quando o score total do par vencedor está abaixo do threshold, os dados
    // são insuficientes para uma escolha determinística confiável — a diferença
    // entre os pares concorrentes é tão pequena que o resultado seria
    // arbitrário de qualquer forma (ex.: Arm/Vel=65 vs Arm/Dri=60 para um
    // jogador com mp=11 e nenhum gol/assist).
    //
    // Nesse caso, sorteamos um par válido do pool do perfil, ponderado por score.
    // Isso garante variedade natural entre jogadores de ligas com dados escassos
    // sem afetar jogadores bem documentados (cujos scores estão bem acima do limiar).
    //
    // Thresholds por grupo (empiricamente calibrados):
    //   Goleiro      → 80  (4 características com tetos altos)
    //   Defensor     → 60
    //   Meia/Volante → 70  (scoreVelMeia dá +30 só pela idade — cria "magneto")
    //   Atacante     → 65
    final double LOW_CONF_THRESHOLD;
    if (pos == 0) LOW_CONF_THRESHOLD = 80;
    else if (pos == 1 || pos == 2) LOW_CONF_THRESHOLD = 60;
    else if (pos == 3) LOW_CONF_THRESHOLD = 70;
    else LOW_CONF_THRESHOLD = 65;

    double winScore = scores.getOrDefault(first, 0.0) + scores.getOrDefault(second, 0.0);

    if (winScore <= LOW_CONF_THRESHOLD) {
      Set<String> allowedForConf = getAllowedCombinations(profile);
      List<String> candidates = new ArrayList<>();
      List<Double> weights   = new ArrayList<>();

      for (String pair : allowedForConf) {
        int[] idx = parseCharPair(pair);
        if (idx == null || idx.length < 2) continue;
        double s = scores.getOrDefault(idx[0], 0.0) + scores.getOrDefault(idx[1], 0.0);
        candidates.add(pair);
        weights.add(Math.max(s, 1.0)); // peso mínimo 1 para garantir diversidade
      }

      if (!candidates.isEmpty()) {
        double totalWeight = weights.stream().mapToDouble(Double::doubleValue).sum();
        double roll = new Random().nextDouble() * totalWeight;
        double acc  = 0;
        String chosen = candidates.get(0);
        for (int k = 0; k < candidates.size(); k++) {
          acc += weights.get(k);
          if (roll <= acc) { chosen = candidates.get(k); break; }
        }
        int[] pair = parseCharPair(chosen);
        if (DEBUG) System.out.println("[DEBUG] Low-confidence [" + profile
            + "] score=" + winScore + "<" + LOW_CONF_THRESHOLD
            + " → sorteio ponderado: " + chosen);
        return new int[]{ pair[0], pair[1], resolvedPos };
      }
    }
    // ─────────────────────────────────────────────────────────────────────────

    // Valida contra a lista de pares permitidos para o perfil
    Set<String> allowed = getAllowedCombinations(profile);
    String pair1 = idxToName(first) + "/" + idxToName(second);
    String pair2 = idxToName(second) + "/" + idxToName(first);

    if (allowed.contains(pair1) || allowed.contains(pair2)) {
      // Se apenas o par inverso está no allowed, usa a ordem canônica do allowed.
      // Ex.: allowed tem "Cru/Fin" mas scoring gerou first=Fin, second=Cru →
      //      sem swap teríamos "Fin/Cru"; com swap geramos "Cru/Fin". Correto.
      if (!allowed.contains(pair1) && allowed.contains(pair2)) {
        int tmp = first; first = second; second = tmp;
        if (DEBUG) System.out.println("[DEBUG] Canonical swap: " + pair1 + " → " + idxToName(first) + "/" + idxToName(second));
      } else {
        if (DEBUG) System.out.println("[DEBUG] Selected pair: " + pair1 + " (allowed)");
      }
    } else {
      // Busca o melhor par permitido dentre os top-5 candidatos por score
      List<Integer> topCandidates = sorted.stream()
          .limit(5)
          .map(Map.Entry::getKey)
          .collect(Collectors.toList());

      double bestScore = -1;
      int bestA = first, bestB = second;
      boolean foundAllowed = false;

      for (int i = 0; i < topCandidates.size(); i++) {
        for (int j = i + 1; j < topCandidates.size(); j++) {
          int a = topCandidates.get(i);
          int b = topCandidates.get(j);
          String p1 = idxToName(a) + "/" + idxToName(b);
          String p2 = idxToName(b) + "/" + idxToName(a);
          if (allowed.contains(p1) || allowed.contains(p2)) {
            double scoreSum = scores.get(a) + scores.get(b);
            if (scoreSum > bestScore) {
              bestScore = scoreSum;
              foundAllowed = true;
              // Respeita a ordem canônica do allowed list:
              // se apenas p2 (b/a) bate, o par canônico é (b, a), não (a, b).
              if (allowed.contains(p1)) {
                bestA = a; bestB = b;
              } else {
                bestA = b; bestB = a;
              }
            }
          }
        }
      }

      if (foundAllowed) {
        first  = bestA;
        second = bestB;
        if (DEBUG) System.out.println("[DEBUG] Adjusted to best allowed pair: "
            + idxToName(first) + "/" + idxToName(second));
      } else {
        // Nenhum par nos top-5 está no allowed list — usa fallback aleatório do perfil
        if (DEBUG) System.out.println("[DEBUG] No allowed pair in top-5; using random fallback for " + profile);
        return getFallbackRandom(profile, pos);
      }
    }

    return new int[] { first, second, resolvedPos };
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

  // -------------------------------------------------------------------------
  // Conversão de perfil → posição numérica do Brasfoot
  // -------------------------------------------------------------------------

  /**
   * Converte um perfil de subposição na posição numérica correspondente do Brasfoot.
   *   0 = Goleiro  1 = Lateral  2 = Zagueiro  3 = Meia  4 = Atacante
   *
   * Usado para garantir que, após um sorteio ou resolução de perfil genérico,
   * a posição numérica escrita no .ban esteja alinhada com a característica sorteada.
   * Exemplo: se "Defensor" sorteia perfil LAT_OF e par Cru/Vel, o jogador também
   * deve ser salvo como Lateral (1) no Brasfoot, não como Zagueiro (2).
   */
  private static int profileToPos(String profile) {
    if (profile == null) return 4;
    switch (profile) {
      case "GK":                      return 0;
      case "LAT_DEF": case "LAT_OF":  return 1;
      case "ZAG_NORMAL": case "ZAG_OFENSIVO": return 2;
      case "VOL":
      case "M_CENTRAL":
      case "M_ESQUERDA_DIREITA":
      case "M_OFENSIVO":              return 3;
      case "ATAC_REC":
      case "ATAC_CA":
      case "ATAC_PONTA":              return 4;
      default:                        return 4;
    }
  }

  // -------------------------------------------------------------------------
  // Fallback aleatório baseado no pool de pares da subposição
  // -------------------------------------------------------------------------

  /**
   * Sorteia aleatoriamente um par do pool de pares para o perfil dado.
   * Retorna int[3]: {cr1, cr2, resolvedPos}.
   *
   * resolvedPos é a posição numérica do Brasfoot que deve ser gravada no .ban,
   * garantindo que característica e posição fiquem sempre consistentes.
   *
   * GENERIC_DEF — tratamento especial:
   *   Sorteia um subperfil ({LAT_DEF, LAT_OF, ZAG_NORMAL, ZAG_OFENSIVO}) ANTES
   *   de escolher o par, para que o resolvedPos reflita exatamente o grupo sorteado.
   *   Ex.: se LAT_OF for sorteado → par Cru/Vel → resolvedPos=1 (Lateral).
   *        se ZAG_NORMAL for sorteado → par Des/Mar → resolvedPos=2 (Zagueiro).
   *
   * GENERIC_MID / GENERIC_ATK — o pos nunca varia dentro do grupo (sempre 3 ou 4),
   *   então sorteia do pool unificado diretamente.
   *
   * Perfil específico → pool da subposição exata; resolvedPos = profileToPos(profile).
   * Perfil desconhecido → pool genérico do grupo; resolvedPos = pos original.
   */
  private static int[] getFallbackRandom(String profile, int pos) {
    Set<String> pool;
    int resolvedPos = pos; // default: mantém pos original

    Random rng = new Random();

    if ("GENERIC_DEF".equals(profile)) {
      // Sorteia subperfil ANTES do par — garante alinhamento entre pos e características
      List<String> defProfiles = new ArrayList<>(
          List.of("LAT_DEF", "LAT_OF", "ZAG_NORMAL", "ZAG_OFENSIVO"));
      Collections.shuffle(defProfiles, rng);
      String chosenProfile = defProfiles.get(0);
      pool = getAllowedCombinations(chosenProfile);
      resolvedPos = profileToPos(chosenProfile);
      if (DEBUG) System.out.println("[DEBUG] getFallbackRandom: GENERIC_DEF → subperfil="
          + chosenProfile + " resolvedPos=" + resolvedPos + " pool=" + pool.size() + " pares");

    } else if ("GENERIC_MID".equals(profile)) {
      pool = getGenericGroupPool(3);
      resolvedPos = 3;
      if (DEBUG) System.out.println("[DEBUG] getFallbackRandom: GENERIC_MID → pool=" + pool.size() + " pares");

    } else if ("GENERIC_ATK".equals(profile)) {
      pool = getGenericGroupPool(4);
      resolvedPos = 4;
      if (DEBUG) System.out.println("[DEBUG] getFallbackRandom: GENERIC_ATK → pool=" + pool.size() + " pares");

    } else {
      // Perfil específico conhecido
      pool = getAllowedCombinations(profile);
      resolvedPos = profileToPos(profile);
      // Perfil desconhecido ou vazio → usa pool genérico do grupo por pos
      if (pool.isEmpty()) {
        pool = getGenericGroupPool(pos);
        resolvedPos = pos;
        if (DEBUG) System.out.println("[DEBUG] getFallbackRandom: profile='" + profile
            + "' vazio → usando getGenericGroupPool(pos=" + pos + ")");
      }
    }

    // Último recurso absoluto (não deveria acontecer)
    if (pool.isEmpty()) {
      if (DEBUG) System.out.println("[DEBUG] getFallbackRandom: pool vazio, retornando Pas/Vel pos=" + pos);
      return new int[]{11, 13, pos};
    }

    List<String> list = new ArrayList<>(pool);
    Collections.shuffle(list, rng);
    int[] pair = parseCharPair(list.get(0));

    if (DEBUG) System.out.println("[DEBUG] getFallbackRandom [" + profile + "]: sorteou "
        + list.get(0) + " resolvedPos=" + resolvedPos);
    return new int[]{pair[0], pair[1], resolvedPos};
  }

  /**
   * Converte a string "Arm/Vel" em int[]{4, 13}.
   * Usa nameToIdx para cada parte; retorna {Pas, Vel} em caso de erro de parsing.
   */
  private static int[] parseCharPair(String pair) {
    if (pair == null || !pair.contains("/")) return new int[]{11, 13};
    String[] parts = pair.split("/", 2);
    int a = nameToIdx(parts[0].trim());
    int b = nameToIdx(parts[1].trim());
    return new int[]{a, b};
  }

  /**
   * Inverso de idxToName — converte nome de característica em índice numérico.
   * Retorna 11 (Pas) como fallback seguro para nomes não reconhecidos.
   */
  private static int nameToIdx(String name) {
    switch (name) {
      case "Col": return 0;
      case "DPe": return 1;
      case "Ref": return 2;
      case "SGo": return 3;
      case "Arm": return 4;
      case "Cab": return 5;
      case "Cru": return 6;
      case "Des": return 7;
      case "Dri": return 8;
      case "Fin": return 9;
      case "Mar": return 10;
      case "Pas": return 11;
      case "Res": return 12;
      case "Vel": return 13;
      default:
        if (DEBUG) System.out.println("[DEBUG] nameToIdx: nome desconhecido='" + name + "', retornando Pas(11)");
        return 11;
    }
  }
}
