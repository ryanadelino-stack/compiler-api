// HeuristicsEngine.java
// Pacote: br.brasfoot.compiler
//
// Implementação oficial conforme MANUAL COMPLETO DO SISTEMA DE CARACTERÍSTICAS DO BRASFOOT v7.0
// Setembro 2026 – Versão 11.0 — Volante
//
// Mudanças v11.0 — varredura sistemática atrás da mesma classe de defeito nas
// funções ainda não auditadas. O volante era subperfil separado e passara
// despercebido; carregava TODOS os defeitos já corrigidos em outras posições:
//   - disciplineIndex >= 0.70 (Des, +20) disparava em 99,8% dos volantes
//   - disciplineIndex >= 0.75 (Mar, +20) disparava em 99,6%
//   - disciplineIndex >= 0.80 (Pas, +15) disparava em 97,1%
//   - minsPerGame >= 80 (Mar, +10) disparava em 1,3% — inalcançável
//   - assistsPerGame >= 0.08 (Pas, faixa de 55 pts) atingido por 2,9%
//   - goalsPerGame >= 0.12 (Fin, faixa de 55 pts) atingido por 2,9%
//   - scorePasVol somava assistências DUAS VEZES (faixas + "bônus direto")
//   Des e Mar também mediam a mesma coisa (ambos premiavam cartão e
//   disciplina): empate técnico em 59% dos volantes, caído para 16%
//   - scoreCruLat: as faixas 0.07 e 0.05 davam os mesmos 30 pontos — degrau
//     plano que não distinguia nada entre elas
//   - Priors regerados a partir deste scoring
//
// Mudanças v10.0 — o goleiro era a única posição cujo scoring nunca fora
// revisado. Quatro defeitos, todos medidos em 462 goleiros com estatísticas:
//   - scoreDPe era um CONTADOR DE IDADE: idade dava até +50 e jogos até +45,
//     95 pontos antes de qualquer evidência, enquanto a taxa real de defesa de
//     pênaltis valia no máximo +40. 92% dos goleiros com 33+ recebiam DPe,
//     contra 12% dos menores de 27 — e a taxa mediana de defesa de quem
//     recebia era 0,12, ABAIXO da média da liga (~0,25). Agora a taxa de defesa
//     é o critério primário (o dado existe em 69% dos goleiros) e idade/jogos
//     viram apoio; taxa ruim com amostra boa passa a descontar
//   - scoreSGo tinha um BLOQUEIO TOTAL por gols sofridos (gpg > 1.50 → zero).
//     Gol sofrido é atributo do time, não do goleiro: dos 46 goleiros com
//     1,95m ou mais, 9 ficavam sem SGo e todos os 9 por esse corte — inclusive
//     um de 2,00m com 276 jogos barrado por 1,51, um centésimo acima. Virou
//     desconto graduado
//   - a escala de altura do SGo saturava em 1,93m, então um goleiro de 2,04m
//     empatava com um de 1,93m e o desempate ia para critérios em que o
//     gigante costuma perder (reserva jovem). As faixas agora são os percentis
//     DA POSIÇÃO (p25=1,86 p50=1,89 p75=1,92 p90=1,95 p95=1,96), o que também
//     corrige o oposto: 1,88m é BAIXO para um goleiro e não deve render SGo
//   - scoreCol e scoreRef mediam a mesma coisa duas vezes (ambos movidos por
//     gols sofridos e clean sheets, que têm correlação de −0,76 entre si).
//     Col passa a ser posicionamento (clean sheets + experiência + maturidade)
//     e Ref, agilidade (juventude + estatura menor + qualidade)
//
// Mudanças v9.0 — fecha as duas posições que a v8.0 deixou em aberto:
//   ZAGUEIRO — Des e Mar mediam a MESMA coisa duas vezes (ambos premiavam
//   cartões E disciplina, o que é contraditório, já que disciplineIndex =
//   1 − cartões/jogo/2). Pior: o corte disciplineIndex >= 0.70 disparava para
//   100% dos zagueiros e >= 0.75 para 99% — pontos de graça que empilhavam as
//   duas notas no mesmo patamar e deixavam o desempate ao acaso (Des > Mar em
//   48% dos casos, diferença <= 10 pontos em 33%). Agora Des mede duelo
//   (cartões + porte) e Mar mede posicionamento (disciplina + regularidade +
//   minutos), sem sobreposição. O empate técnico caiu de 33% para 13%
//   - scoreCabZag: altura passa a ser o sinal primário do cabeceio (antes gols
//     valiam até 60 pontos e altura só 25 — o contrário do que define um
//     cabeceador de defesa)
//   - scorePasZag: removido outro bônus grátis de disciplina (disparava em 96%)
//   - scoreVelZag: idade escalonada e limiar de altura apertado de 1,85 (perto
//     da mediana, 1,87) para 1,82
//   LATERAL — novo perfil LAT_GEN (união dos pools LAT_DEF + LAT_OF) para
//   laterais sem amostra estatística. A v7.0 mandava todos para LAT_OF, cujo
//   pool tem só 5 pares e exclui Vel/Mar e Cru/Mar — juntos 20% do prior da
//   posição. Sem estatísticas não há base para escolher entre ofensivo e
//   defensivo, então usa-se a união e deixa-se os priors ponderarem
//   - Priors regerados a partir deste scoring
//
// Mudanças v8.0 — corrige o caminho de SCORING (não mais só o fallback),
// calibrado sobre 4.570 jogadores com estatísticas de 7 ligas:
//   - scoreDesMeia: removidos o bônus de identidade ("+20 por não ser meia
//     ofensivo") e o de ausência de evidência ("+10 por marcar poucos gols"),
//     que davam 55 pontos de graça a qualquer meia central. Cartões passam a ser
//     escalonados por percentil real em vez de um corte único em 0.10
//   - scoreVelMeia / scoreDriMeia: removido/apertado o bônus de altura, que era
//     quase gratuito (mediana real de um meia é 1,76m e o corte era 1,78m) —
//     mesmo defeito já corrigido em scoreVelVol. Idade passa a ser escalonada
//   - scoreArmMeia / scorePasMeia: limiares de assistência estavam TODOS acima
//     da mediana real, então o armador típico pontuava zero. Rebaixados aos
//     percentis medidos (p50=0.037, p75=0.063, p90=0.091)
//   - Efeito: Des/Vel cai de 67% para 28% dos meias centrais e Arm/Pas sobe de
//     18% para 37% — a distribuição deixa de ser dominada por um par único
//   - scoreRes*: limiares de minutos por jogo eram inalcançáveis (exigiam 80-85
//     quando o p95 real é 85 para zagueiro, 78 para volante e 69 para
//     centroavante — para centroavante era código morto). Agora usam os
//     percentis reais DA POSIÇÃO
//   - Priors do fallback regerados a partir deste scoring novo, como exige a
//     regra de que os priors descrevem o que o scoring faz
//
// Mudanças v7.1 (recalibração sobre 7 datasets: + BRA1, BRA2, BRA3):
//   - Priors reagregados sobre 4.617 jogadores com estatísticas (antes 2.416),
//     o que dobra a amostra e dilui o viés de uma liga só. A ordem dos pares
//     dominantes se confirmou em todas as ligas; o que variava era magnitude
//   - Medianas de altura recalculadas em 5.458 jogadores (antes 3.506);
//     goleiros subiram para 1,89m e centroavantes para 1,83m
//   - Nenhuma mudança de lógica: só recalibração das duas tabelas de dados
//
// Mudanças v7.0 (calibradas em 4.390 jogadores de ARG2, GR1, GRS2 e MEXA):
//   - Peso-base do fallback deixa de ser fixo (10.0) e passa a ser o prior
//     EMPÍRICO da posição — a frequência com que o scoring escolhe cada par.
//     Corrige o desbalanceamento medido em que elencos sem estatísticas
//     recebiam características fracas (Pas 15.8% vs 5.9%, Res 7.2% vs 0.3%)
//     e quase nunca as fortes (Vel 8.6% vs 18.9%, Cab 1.9% vs 7.5%)
//   - Altura ausente (38% dos jogadores gregos) deixa de zerar o sinal de
//     altura: é imputada pela mediana da posição, com afinidade amortecida
//   - Priors suavizados (80% empírico + 20% uniforme) para preservar variedade
//
// Mudanças v6.0:
//   - Fallback (amostra insuficiente) agora é PONDERADO por atributos estáticos
//     (idade, altura, secundárias) em vez de sorteio uniforme
//   - Sorteios determinísticos: seed estável por jogador (nome + atributos) —
//     recompilar o mesmo dataset gera sempre o mesmo .ban
//   - MIN_FALLBACK_SAMPLE=3: jogadores com 1-2 jogos de carreira (taxas de ruído
//     puro) são roteados para o fallback ponderado em vez do scoring
//   - GENERIC_DEF: subperfil (LAT/ZAG) escolhido ponderado por altura/secundárias
//   - Low-confidence draw também determinístico (seed estável)
//
// Mudanças v5.0:
//   - Pool de pares por subposição atualizado (tabela mestre do design doc)
//   - Novo perfil M_ESQUERDA_DIREITA (Meia Esquerda / Meia Direita)
//   - Detecção de "Ala" → LAT_OF por padrão
//   - Quando scoring produz par fora do allowed list, busca o melhor par permitido (top-5)
//   - Posição genérica sem subposição → pool unificado do grupo
//
// Índices das características (0..13):
// 0 Colocacao, 1 Defesa Penalty, 2 Reflexo, 3 Saida gol,
// 4 Armacao, 5 Cabeceio, 6 Cruzamento, 7 Desarme, 8 Drible,
// 9 Finalizacao, 10 Marcacao, 11 Passe, 12 Resistencia, 13 Velocidade

package br.brasfoot.compiler;

import java.util.ArrayList;
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

  public static final String HEURISTICS_ENGINE_MARKER = "V11.0-VOLANTE";

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

  // ── v10.0: Col e Ref deixam de medir a mesma coisa ──────────────────────
  //
  // DIAGNÓSTICO: as duas funções eram movidas pelos mesmos dois indicadores
  // (gols sofridos por jogo e taxa de clean sheets), que por sua vez têm
  // correlação de −0,76 entre si — ou seja, o MESMO sinal medido duas vezes, uma
  // invertida. Isso empilhava as duas notas (diferença <= 10 pontos em 23% dos
  // goleiros) e repetia, aqui, o defeito que Des/Mar tinha no zagueiro.
  //
  // Separação conceitual a partir da v10.0:
  //   Col (colocação) = posicionamento consistente → clean sheets + experiência
  //   Ref (reflexo)   = agilidade de reação → juventude, estatura menor, e a
  //                     qualidade medida por gols sofridos
  // Col perde o eixo de gols sofridos (que é do Ref e depende muito do time) e
  // ganha peso em clean sheets e idade. Limiares nos percentis reais de 462
  // goleiros: clean sheets p25=0.26, p50=0.31, p75=0.37, p90=0.41 — o corte
  // antigo de 0.42 era atingido por só 8% deles.
  private static double scoreCol(Metrics m) {
    double pontos = 0;
    // Clean sheets é o indicador primário e agora praticamente exclusivo
    if (m.cleanSheetRate >= 0.41) pontos += 62;      // p90
    else if (m.cleanSheetRate >= 0.37) pontos += 48; // p75
    else if (m.cleanSheetRate >= 0.31) pontos += 32; // p50
    else if (m.cleanSheetRate >= 0.26) pontos += 16; // p25
    // Experiência: posicionamento se aprende com jogos
    if (m.played >= 300) pontos += 30;
    else if (m.played >= 200) pontos += 22;
    else if (m.played >= 100) pontos += 13;
    // v10.0: colocação é atributo de goleiro maduro — o inverso do eixo do Ref
    if (m.age != null && m.age >= 32) pontos += 25;
    else if (m.age != null && m.age >= 29) pontos += 15;
    else if (m.age != null && m.age >= 26) pontos += 7;
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
    // v10.0: estatura menor favorece o reflexo lateral (o inverso do eixo do
    // SGo). Percentis reais: p25=1,86 p50=1,89 p75=1,92.
    if (m.height > 0 && m.height <= 1.86) pontos += 18;
    else if (m.height > 0 && m.height <= 1.89) pontos += 10;
    if (m.played >= 60) pontos += 10;
    return pontos;
  }

  // ── v10.0: DPe deixa de ser um contador de idade ─────────────────────────
  //
  // DIAGNÓSTICO: "Defesa de Pênalti" era decidida por idade e jogos, não por
  // pênaltis defendidos. Idade dava até +50 e jogos até +45 — 95 pontos antes de
  // qualquer evidência — enquanto a taxa real de defesa valia no máximo +40.
  // Resultado: 92% dos goleiros com 33 anos ou mais recebiam DPe, contra 12% dos
  // menores de 27, e a nota mediana ia de 122 para 9 só pela faixa etária.
  //
  // E o dado existe: 69% dos goleiros têm 5 ou mais pênaltis enfrentados
  // registrados no JSON. Estava sendo abafado por um proxy.
  //
  // Agora a taxa de defesa é o critério primário; idade e jogos viram apoio.
  private static double scoreDPe(Metrics m) {
    double pontos = 0;
    // Idade e jogos: contexto, não veredito
    if (m.age != null && m.age >= 33)      pontos += 22;
    else if (m.age != null && m.age >= 30) pontos += 14;
    else if (m.age != null && m.age >= 27) pontos += 7;
    if (m.played >= 300)      pontos += 20;
    else if (m.played >= 200) pontos += 14;
    else if (m.played >= 120) pontos += 8;
    else if (m.played >= 60)  pontos += 4;
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
      // v10.0: esta é a evidência direta e passa a pesar mais que idade + jogos
      if      (m.penaltySaveRate >= 0.40) pontos += 85;
      else if (m.penaltySaveRate >= 0.33) pontos += 65;
      else if (m.penaltySaveRate >= 0.25) pontos += 42;
      else if (m.penaltySaveRate >= 0.15) pontos += 18;
      else pontos -= 15;  // v10.0: taxa ruim com amostra boa é evidência CONTRA
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

  // ── v10.0: escala de altura do SGo estendida ────────────────────────────
  //
  // DIAGNÓSTICO: a escala saturava em 1,93m (+55), mas a distribuição real de
  // 442 goleiros vai muito além disso — p75=1,92, p90=1,95, p95=1,96, máximo
  // 2,04m. Resultado medido: a faixa >=1,95 recebia SGo em 80% dos casos,
  // MENOS que a faixa 1,93-1,94 (92%), porque acima de 1,93 a altura parava de
  // pontuar e o desempate passava para gols sofridos e jogos — critérios em que
  // o goleiro gigante costuma ser um reserva jovem. Um goleiro de 2,04m ficava
  // empatado em altura com um de 1,93m.
  //
  // Além disso o corte em 1,87 era um degrau seco: 1,86m zerava SGo. Agora há
  // uma faixa de transição em 1,85.
  private static double scoreSGo(Metrics m) {
    // Abaixo do p25 da posição (1,86m) não comanda a área
    if (m.height < 1.86) return 0;
    // v10.0: REMOVIDO o bloqueio total por gols sofridos (gpg > 1.50 → 0).
    //
    // Gols sofridos por jogo é sobretudo função da defesa do time, não da
    // capacidade do goleiro de dominar a área. O portão zerava justamente os
    // goleiros mais altos de equipes fracas: dos 46 goleiros com 1,95m ou mais,
    // 9 ficavam sem SGo e TODOS os 9 por causa desse corte — incluindo um de
    // 2,00m com 276 jogos barrado por 1,51 gols/jogo, um centésimo acima do
    // limite. Altura é atributo individual; gol sofrido é atributo coletivo.
    //
    // Agora o excesso de gols sofridos apenas desconta, de forma graduada.
    double pontos = 0;
    // ALTURA é o indicador primário. As faixas são os PERCENTIS DA PRÓPRIA
    // POSIÇÃO (442 goleiros: p25=1,86 p50=1,89 p75=1,92 p90=1,95 p95=1,96,
    // máximo 2,04) e não valores absolutos. Isso importa porque a mediana de um
    // goleiro já é 1,89m — um goleiro de 1,88m é BAIXO para a posição e não deve
    // receber SGo com facilidade, embora fosse altíssimo em qualquer outra.
    if (m.height >= 1.98) pontos += 72;           // acima do p95, raro
    else if (m.height >= 1.96) pontos += 62;      // p95
    else if (m.height >= 1.95) pontos += 54;      // p90
    else if (m.height >= 1.92) pontos += 40;      // p75
    else if (m.height >= 1.89) pontos += 20;      // p50
    else pontos += 6;                              // p25 (1,86-1,88): transição
    // Qualidade defensiva amplifica SGo: saída segura gera cleansheets
    if (m.goalsConcededPerGame <= 1.00)      pontos += 30;
    else if (m.goalsConcededPerGame <= 1.15) pontos += 18;
    else if (m.goalsConcededPerGame <= 1.25) pontos += 8;
    else if (m.goalsConcededPerGame <= 1.35) pontos += 2;
    else if (m.goalsConcededPerGame > 1.80) pontos -= 22; // v10.0: desconto graduado
    else if (m.goalsConcededPerGame > 1.55) pontos -= 12;
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
  // ── v9.0: Des e Mar de zagueiro passam a medir coisas DIFERENTES ─────────
  //
  // DIAGNÓSTICO: Des/Mar + Mar/Des respondiam por 93% dos zagueiros, e a ordem
  // entre os dois era praticamente cara-ou-coroa (Des > Mar em 48% dos casos;
  // diferença <= 10 pontos em 33%). A causa é que as duas funções mediam a MESMA
  // coisa duas vezes: ambas premiavam cartões E disciplina ao mesmo tempo — o que
  // é internamente contraditório, já que disciplineIndex = 1 − cartões/jogo/2,
  // ou seja, disciplina alta significa POUCOS cartões.
  //
  // Pior: disciplineIndex >= 0.70 dispara para 100% dos zagueiros e >= 0.75 para
  // 99% (a fórmula satura perto de 0,89 de mediana). Os dois bônus eram pontos
  // de graça para todo mundo — +20 no Des e +25 no Mar —, o que empilhava as
  // duas notas no mesmo patamar e deixava o desempate ao acaso.
  //
  // A partir da v9.0 os dois conceitos ficam separados:
  //   Des (desarme)   = ganhar a bola no duelo → faltas/cartões e porte físico
  //   Mar (marcação)  = posicionamento e constância → disciplina, regularidade
  //                     e minutos em campo, SEM prêmio por cartão
  // Limiares nos percentis reais de 773 zagueiros: amarelo/jogo p50=0.185,
  // p75=0.231, p90=0.278; disciplina p50=0.889, p75=0.915; regularidade
  // p75=0.82, p90=0.87.
  private static double scoreDesZag(Metrics m) {
    double pontos = 0;
    if (m.yellowPerGame >= 0.278) pontos += 58;      // p90 — desarmador agressivo
    else if (m.yellowPerGame >= 0.231) pontos += 46; // p75
    else if (m.yellowPerGame >= 0.185) pontos += 34; // p50
    else if (m.yellowPerGame >= 0.145) pontos += 18; // p25
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
    // v9.0: sem bônus por cartão (isso é Des) e sem bônus por "marcar poucos gols"
    // (ausência de evidência, mesmo defeito corrigido em scoreDesMeia na v8.0).
    double pontos = 0;
    if (m.disciplineIndex >= 0.915) pontos += 45;      // p75 — marca sem faltar
    else if (m.disciplineIndex >= 0.889) pontos += 33; // p50
    else if (m.disciplineIndex >= 0.860) pontos += 20; // p25
    if (m.regularity >= 0.87) pontos += 35;            // p90
    else if (m.regularity >= 0.82) pontos += 26;       // p75
    else if (m.regularity >= 0.74) pontos += 15;       // p50
    if (m.minsPerGame >= 84) pontos += 18;             // p90 — joga os 90
    else if (m.minsPerGame >= 79) pontos += 10;        // p50
    if (m.played >= 180) pontos += 12;
    else if (m.played >= 120) pontos += 6;
    return pontos;
  }

  private static double scoreCabZag(Metrics m) {
    // v9.0 — INVERSÃO DE PRIORIDADE. Antes os gols valiam até 60 pontos e a
    // altura só 25, o que é o contrário do que define um cabeceador de defesa:
    // um zagueiro de 1,95m domina a área aérea independentemente de quantos gols
    // marcou. Altura passa a ser o sinal primário (percentis reais de 773
    // zagueiros: p50=1,87 p75=1,90 p90=1,92) e os gols reforçam em segundo plano
    // (p50=0.037 p75=0.058 p90=0.079).
    double pontos = 0;
    if (m.height >= 1.92) pontos += 45;
    else if (m.height >= 1.90) pontos += 32;
    else if (m.height >= 1.87) pontos += 18;
    if (m.goalsPerGame >= 0.079) pontos += 35;
    else if (m.goalsPerGame >= 0.058) pontos += 25;
    else if (m.goalsPerGame >= 0.037) pontos += 12;
    if (m.penaltyGoals > 0) pontos += 15;
    if (m.participationPerGame >= 0.08) pontos += 10;
    if (m.played >= 300 && m.goalsPerGame >= 0.06) pontos += 20;
    return pontos;
  }

  private static double scoreVelZag(Metrics m) {
    double pontos = 0;
    // v9.0: idade escalonada (era um degrau único em 27) e limiar de altura
    // apertado de 1,85 para 1,82 — a mediana de um zagueiro é 1,87m, então 1,85
    // premiava perto de metade deles por "ser baixo".
    if (m.age != null && m.age > 0) {
      if (m.age <= 23) pontos += 30;
      else if (m.age <= 27) pontos += 20;
      else if (m.age >= 33) pontos -= 12;
      else if (m.age >= 30) pontos -= 6;
    }
    if (m.height > 0 && m.height <= 1.82) pontos += 20;
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
    // v9.0: limiares descidos aos percentis reais (p50=0.010, p75=0.019,
    // p90=0.030) — os cortes antigos (0.02/0.03) eram p75/p90, então o zagueiro
    // com saída de bola mediana pontuava zero.
    double pontos = 0;
    if (m.assistsPerGame >= 0.030) pontos += 42;      // p90
    else if (m.assistsPerGame >= 0.019) pontos += 28; // p75
    else if (m.assistsPerGame >= 0.010) pontos += 14; // p50
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("volante")))
      pontos += 25;
    // v9.0: o corte de disciplina em 0.80 disparava para 96% dos zagueiros —
    // era ponto de graça, não evidência de saída de bola. Subido ao p75 real.
    if (m.disciplineIndex >= 0.915) pontos += 15;
    return pontos;
  }

  // ── v8.0: Resistência recalibrada ────────────────────────────────────────
  //
  // DIAGNÓSTICO: Res aparecia em 0,2% dos jogadores — praticamente morta. A causa
  // não era o peso, era o LIMIAR: todas as funções exigiam minsPerGame >= 85 ou
  // >= 80, valores calibrados como se um titular jogasse ~90 min por partida.
  // Mas minutesPlayed/matchesPlayed inclui entradas no 2º tempo, então a
  // distribuição real (medida em 4.570 jogadores com estatísticas) é bem menor
  // e varia MUITO por posição:
  //
  //   posição        p50   p75   p90   p95
  //   Zagueiro        79    82    84    85
  //   Volante         65    71    76    78
  //   Meia central    62    68    73    75
  //   Centroavante    56    62    66    69
  //
  // Ou seja: para centroavante o corte de 80 era INALCANÇÁVEL (p95 = 69), e para
  // volante quase (p95 = 78). O bônus principal de Res era código morto nessas
  // posições. Os limiares abaixo passam a ser os percentis reais DA POSIÇÃO.
  private static double scoreResZag(Metrics m) {
    // Percentis de zagueiro: p75=82, p90=84, p95=85.
    double pontos = 0;
    if (m.minsPerGame >= 85) pontos += 45;
    else if (m.minsPerGame >= 84) pontos += 36;
    else if (m.minsPerGame >= 82) pontos += 24;
    if (m.regularity >= 0.87) pontos += 30;       // p90
    else if (m.regularity >= 0.82) pontos += 20;  // p75
    if (m.age != null && m.age >= 29 && m.age <= 34) pontos += 15;
    return pontos;
  }

  // Lateral
  private static double scoreCruLat(Metrics m) {
    double pontos = 20; // base
    // v11.0: as faixas 0.07 e 0.05 davam os MESMOS 30 pontos — um degrau plano
    // que não distinguia nada entre elas. Reescalonado nos percentis reais de
    // 725 laterais: assist/jogo p25=0.018 p50=0.040 p75=0.065 p90=0.088 p95=0.110.
    if (m.assistsPerGame >= 0.110) pontos += 45;      // p95
    else if (m.assistsPerGame >= 0.088) pontos += 38; // p90
    else if (m.assistsPerGame >= 0.065) pontos += 30; // p75
    else if (m.assistsPerGame >= 0.040) pontos += 22; // p50
    else if (m.assistsPerGame >= 0.018) pontos += 12; // p25
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
  // ── v11.0: volante recebe a mesma auditoria do zagueiro e do meia ────────
  //
  // O volante passou despercebido nas versões anteriores por ser um subperfil
  // separado, e carregava exatamente os mesmos defeitos. Medido em 475 volantes:
  //   disciplineIndex >= 0.70 (Des, +20) dispara em  99,8%  → ponto de graça
  //   disciplineIndex >= 0.75 (Mar, +20) dispara em  99,6%  → ponto de graça
  //   disciplineIndex >= 0.80 (Pas, +15) dispara em  97,1%  → ponto de graça
  //   minsPerGame >= 80       (Mar, +10) dispara em   1,3%  → inalcançável
  //   assistsPerGame >= 0.08  (Pas, +55) dispara em   2,9%  → faixa de topo morta
  //   goalsPerGame >= 0.12    (Fin, +55) dispara em   2,9%  → faixa de topo morta
  //
  // Percentis reais: assist/jogo p50=0.020 p75=0.035 p90=0.059 p95=0.074 |
  // gols/jogo p50=0.037 p75=0.061 p90=0.090 | amarelo/jogo p50=0.200 p75=0.250
  // p90=0.294 | disciplina p25=0.859 p50=0.890 p75=0.917 | min/jogo p75=71
  // p90=76 p95=78 | regularidade p75=0.86 p90=0.91.
  private static double scoreDesVol(Metrics m) {
    // Des = ganhar a bola no duelo → cartões. Sem bônus de disciplina (que é do
    // Mar) e sem prêmio por marcar poucos gols (ausência de evidência).
    double pontos = 0;
    // Desarme e marcação são as competências CENTRAIS do volante e pesam mais
    // que passe — o que não é bônus de identidade: cada faixa continua exigindo
    // evidência (cartões), só com escala à altura da posição.
    if (m.yellowPerGame >= 0.294) pontos += 88;      // p90
    else if (m.yellowPerGame >= 0.250) pontos += 72; // p75
    else if (m.yellowPerGame >= 0.200) pontos += 56; // p50
    else if (m.yellowPerGame >= 0.154) pontos += 32; // p25
    if (m.played >= 150) pontos += 14;
    // Bônus distribuidor: volante destruidor que também assiste bem tem perfil
    // Des/Pas — o "destroyer-playmaker". Sem este bônus, Mar sempre vencia Des
    // por causa dos bônus de regularidade/disciplina em scoreMarVol (v5.1).
    if (m.assistsPerGame >= 0.06) pontos += 30;
    else if (m.assistsPerGame >= 0.04) pontos += 15;
    return pontos;
  }

  private static double scoreMarVol(Metrics m) {
    // Mar = posicionamento e constância → disciplina, regularidade e minutos.
    // Sem bônus por cartão (que é do Des), espelhando a separação feita no
    // zagueiro na v9.0.
    double pontos = 0;
    if (m.disciplineIndex >= 0.917) pontos += 54;      // p75
    else if (m.disciplineIndex >= 0.890) pontos += 42; // p50
    else if (m.disciplineIndex >= 0.859) pontos += 24; // p25
    if (m.regularity >= 0.91) pontos += 44;            // p90
    else if (m.regularity >= 0.86) pontos += 34;       // p75
    else if (m.regularity >= 0.80) pontos += 20;       // p50
    if (m.minsPerGame >= 78) pontos += 16;             // p95 (era 80: 1,3%)
    else if (m.minsPerGame >= 71) pontos += 8;         // p75
    if (m.played >= 180) pontos += 12;
    return pontos;
  }

  private static double scorePasVol(Metrics m) {
    // Pas é o indicador principal do volante distribuidor.
    // Des/Pas e Mar/Pas devem aparecer para dois perfis distintos:
    //   1. Volante com número considerável de assistências (apg >= 0.06) — mesmo
    //      sem muitos gols, a criação de jogo justifica o par.
    //   2. Volante com alta participação geral (ppg elevado) — complemento do (1).
    double pontos = 0;
    // v11.0: limiares nos percentis reais — o antigo topo (0.08) era atingido
    // por só 2,9% dos volantes, deixando a faixa de 55 pontos praticamente morta.
    if (m.assistsPerGame >= 0.074) pontos += 55;      // p95
    else if (m.assistsPerGame >= 0.059) pontos += 45; // p90
    else if (m.assistsPerGame >= 0.035) pontos += 32; // p75
    else if (m.assistsPerGame >= 0.020) pontos += 18; // p50
    // v11.0: REMOVIDO o "bônus direto por assistências", que somava de novo o
    // mesmo sinal já contado nas faixas acima. Era assistência contando duas
    // vezes — a mesma duplicação que Des/Mar tinha no zagueiro — e inflava Pas
    // a ponto de Mar/Pas e Des/Pas engolirem o par Mar/Des do volante.
    // Participação reforça (mas não substitui assists como critério primário)
    if (m.participationPerGame >= 0.154) pontos += 30;      // p95
    else if (m.participationPerGame >= 0.130) pontos += 20; // p90
    else if (m.participationPerGame >= 0.091) pontos += 12; // p75
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("meia central")))
      pontos += 20;
    // v11.0: o corte de disciplina em 0.80 disparava para 97,1% dos volantes.
    if (m.disciplineIndex >= 0.917) pontos += 15;   // p75
    return pontos;
  }

  private static double scoreFinVol(Metrics m) {
    // Fin só deve aparecer (Des/Fin ou Mar/Fin) para volantes com números de gol
    // realmente relevantes. Thresholds elevados para evitar que qualquer volante
    // com poucos gols receba Fin como característica secundária.
    double pontos = 0;
    // v11.0: limiares nos percentis reais (p50=0.037 p75=0.061 p90=0.090
    // p95=0.104). O antigo topo (0.12) era atingido por só 2,9% dos volantes.
    if (m.goalsPerGame >= 0.104) pontos += 55;      // p95 — goleador atípico
    else if (m.goalsPerGame >= 0.090) pontos += 44; // p90
    else if (m.goalsPerGame >= 0.061) pontos += 28; // p75
    // Abaixo do p75 não pontua: Mar/Fin e Des/Fin não devem aparecer para
    // volantes com taxa de gol mediana.
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
    // Percentis de volante: p75=71, p90=76, p95=78. O corte antigo de 80/85
    // quase nunca era atingido — Res ficava em 5º lugar de 6 em 100% dos casos.
    double pontos = 0;
    if (m.minsPerGame >= 78) pontos += 45;
    else if (m.minsPerGame >= 76) pontos += 36;
    else if (m.minsPerGame >= 71) pontos += 24;
    if (m.regularity >= 0.91) pontos += 30;       // p90
    else if (m.regularity >= 0.86) pontos += 20;  // p75
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
    // v8.0 — limiares recalibrados sobre percentis reais de 390 meias centrais
    // (assists/jogo p50=0.037, p75=0.063, p90=0.091, p95=0.112). Os cortes
    // anteriores (0.05 / 0.08 / 0.12) ficavam todos ACIMA da mediana, então o
    // armador típico pontuava zero e Arm nunca competia com Des.
    if (m.assistsPerGame >= 0.112) pontos += 45;      // p95
    else if (m.assistsPerGame >= 0.091) pontos += 38; // p90
    else if (m.assistsPerGame >= 0.063) pontos += 28; // p75
    else if (m.assistsPerGame >= 0.037) pontos += 16; // p50
    if (m.participationPerGame >= 0.196) pontos += 20; // p90
    else if (m.participationPerGame >= 0.148) pontos += 12; // p75
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
    // v8.0 — limiares descidos para os percentis reais (p90=0.091, p75=0.063).
    if (m.assistsPerGame >= 0.091) pontos += 35;
    else if (m.assistsPerGame >= 0.063) pontos += 25;
    else if (m.assistsPerGame >= 0.037) pontos += 12;
    if (m.disciplineIndex >= 0.80) pontos += 20;
    // v8.0: 75 min/jogo era p95 para meias — praticamente inalcançável.
    if (m.minsPerGame >= 73) pontos += 15;   // p90 real
    else if (m.minsPerGame >= 68) pontos += 8; // p75 real
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
    // v8.0 — RECALIBRADO. Duas correções:
    //   1. O bônus "+20 se height <= 1.78" era quase gratuito: a mediana de
    //      altura de um meia é 1,76m, então quase todo meia o recebia. É o mesmo
    //      defeito já corrigido em scoreVelVol na v5.x. Removido.
    //   2. O corte único de idade em 27 dava +30 num degrau só. Agora escalonado,
    //      para que "jovem" module o score em vez de decidi-lo.
    if (m.age != null && m.age > 0) {
      if (m.age <= 23) pontos += 28;
      else if (m.age <= 26) pontos += 18;
      else if (m.age <= 28) pontos += 8;
      else if (m.age >= 33) pontos -= 15;
      else if (m.age >= 30) pontos -= 8;
    }
    if (m.participationPerGame >= 0.15) pontos += 25;
    if (ofensivo) pontos += 15;
    return Math.max(0, pontos);
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
    // v8.0: limiar de altura baixado de 1.75 para 1.72. Com 1.75 o bônus atingia
    // cerca de metade dos meias (mediana real 1,76m), inflando Dri por default.
    if (m.height > 0 && m.height <= 1.72) pontos += 20;
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
    // v8.0 — RECALIBRADO. O modelo anterior dava 55 pontos medianos a qualquer
    // meia central, fazendo Des vencer o slot principal em 64% deles e Des/Vel
    // responder por 67% dos M_CENTRAL. Três causas, todas corrigidas aqui:
    //   1. "+20 por não ser meia ofensivo" era um bônus de IDENTIDADE, não de
    //      evidência: todo meia central ganhava de graça. Removido.
    //   2. "+10 por gpg <= 0.05" premiava AUSÊNCIA de evidência ofensiva como se
    //      fosse evidência defensiva. Removido.
    //   3. O corte único de cartões em 0.10 disparava para ~80% dos meias
    //      (mediana real = 0.156). Agora escalonado sobre percentis reais
    //      medidos em 390 meias centrais: p50=0.156, p75=0.204, p90=0.250.
    double pontos = 0;
    if (m.yellowPerGame >= 0.25) pontos += 45;        // p90 — desarmador de verdade
    else if (m.yellowPerGame >= 0.20) pontos += 32;   // p75
    else if (m.yellowPerGame >= 0.156) pontos += 18;  // p50
    if (m.disciplineIndex >= 0.70) pontos += 15;
    // Secundária de volante continua sendo evidência posicional legítima.
    if (m.secondary.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("volante")))
      pontos += 25;
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
    // Percentis de centroavante: p75=62, p90=66, p95=69. O corte antigo de 80
    // era literalmente inalcançável nesta posição (código morto).
    double pontos = 0;
    if (m.minsPerGame >= 69) pontos += 45;
    else if (m.minsPerGame >= 66) pontos += 36;
    else if (m.minsPerGame >= 62) pontos += 24;
    if (m.regularity >= 0.94) pontos += 30;       // p90
    else if (m.regularity >= 0.90) pontos += 20;  // p75
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

      case "LAT_GEN":
        // v9.0 — Lateral SEM amostra estatística: união dos dois pools.
        //
        // A v7.0 fazia esses jogadores caírem em LAT_OF, porque o default antigo
        // (LAT_DEF) tornava Vel/Cru e Cru/Vel inalcançáveis. Mas LAT_OF tem só 5
        // pares, e isso deixava de fora Vel/Mar (10,5% do prior) e Cru/Mar (9,7%)
        // — por isso o lateral era a posição com pior aderência no fallback.
        //
        // Sem estatísticas não há como saber se o jogador é ofensivo ou
        // defensivo, então a resposta honesta é não escolher: usa-se a união dos
        // dois pools e deixa-se os priors empíricos da posição — que já embutem
        // a proporção real entre os dois perfis — fazerem a ponderação.
        set.addAll(getAllowedCombinations("LAT_DEF"));
        set.addAll(getAllowedCombinations("LAT_OF"));
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
      // v9.0 (revisa a v7.0): participationPerGame é estruturalmente 0 para quem
      // não tem amostra, então o default antigo mandava TODO lateral sem
      // estatísticas para LAT_DEF — cujo pool não contém Vel/Cru nem Cru/Vel, os
      // dois pares mais comuns em laterais reais. A v7.0 corrigiu com LAT_OF,
      // mas esse pool tem apenas 5 pares e exclui Vel/Mar e Cru/Mar, que juntos
      // valem 20% do prior da posição. Agora usa-se LAT_GEN (união dos dois
      // pools), deixando os priors decidirem. Só alcança o fallback, já que
      // played < MIN_FALLBACK_SAMPLE.
      if (m.played < MIN_FALLBACK_SAMPLE) return "LAT_GEN";
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

    if (m.played < MIN_FALLBACK_SAMPLE) {
      // Amostra insuficiente (0-2 jogos de carreira): taxas por jogo seriam ruído
      // puro (1 gol em 2 jogos → gpg=0.50). Sorteio ponderado por atributos
      // estáticos (idade, altura, secundárias), determinístico por jogador.
      int[] fallback = getFallbackWeighted(profile, pos, m);
      if (DEBUG) {
        System.out.println("[DEBUG] Insufficient sample (played=" + m.played
            + " < " + MIN_FALLBACK_SAMPLE + ") — weighted fallback: "
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
      return getFallbackWeighted(profile, pos, m);
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
        double roll = new Random(stableSeed(m)).nextDouble() * totalWeight;
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
        // Nenhum par nos top-5 está no allowed list — usa fallback ponderado do perfil
        if (DEBUG) System.out.println("[DEBUG] No allowed pair in top-5; using weighted fallback for " + profile);
        return getFallbackWeighted(profile, pos, m);
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
      case "LAT_DEF": case "LAT_OF": case "LAT_GEN":  return 1;
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
  // Fallback ponderado por atributos (v6.0)
  // -------------------------------------------------------------------------

  /**
   * Amostra mínima de jogos de carreira para o caminho de scoring por estatísticas.
   * Abaixo disso, as taxas por jogo são ruído (1 gol em 2 jogos → gpg=0.50, que
   * pareceria um artilheiro de elite) e o jogador é roteado para o fallback
   * ponderado por atributos.
   */
  private static final int MIN_FALLBACK_SAMPLE = 3;

  /**
   * Chave de seed por jogador (normalmente o NOME), setada pelo BanCompiler
   * antes de chamar pickTop2CharacteristicsByManual. Garante que dois jogadores
   * com atributos idênticos (mesma idade/altura/posição) recebam sorteios
   * INDEPENDENTES, eliminando qualquer risco de "fallback único" repetido.
   *
   * ThreadLocal para segurança caso a compilação venha a ser paralelizada.
   * Se nunca for setada, a seed usa apenas os atributos (comportamento seguro).
   */
  private static final ThreadLocal<String> SEED_KEY = new ThreadLocal<>();

  /** Chamado pelo BanCompiler com o nome do jogador antes de calcular características. */
  public static void setSeedKey(String key) {
    SEED_KEY.set(key);
  }

  /**
   * Seed determinística derivada do seedKey (nome do jogador) + atributos estáveis.
   * Garante que recompilações sucessivas do mesmo dataset produzam o mesmo .ban,
   * mas jogadores diferentes tenham sorteios independentes.
   */
  private static long stableSeed(Metrics m) {
    long h = 1125899906842597L;
    String sk = SEED_KEY.get();
    String key = (sk == null ? "" : sk) + "|" + m.posText + "|" + m.age + "|"
        + m.height + "|" + m.related + "|" + String.join(",", m.secondary);
    for (int i = 0; i < key.length(); i++) {
      h = 31 * h + key.charAt(i);
    }
    return h;
  }

  // ── v7.0: Priors empíricos por posição ────────────────────────────────────
  //
  // MOTIVAÇÃO (medida em 8.816 jogadores de BRA1/BRA2/BRA3/ARG2/GR1/GRS2/MEXA):
  // o fallback v6.0 usava peso-base FIXO (10.0) para todo par do pool, o que faz
  // a distribuição de saída ser aproximadamente uniforme sobre o pool. Já o
  // caminho de scoring produz uma distribuição bem diferente. Resultado medido:
  //
  //   característica   fallback   scoring    (jogadores com >=3 jogos)
  //   Pas                15.8%      5.9%     ← fallback inflava características
  //   Res                 7.2%      0.3%       "fracas" no Brasfoot
  //   Vel                 8.6%     18.9%     ← e suprimia as "fortes"
  //   Fin                 8.8%     14.8%
  //   Cab                 1.9%      7.5%
  //   Cru                 0.8%      5.4%
  //
  // Ou seja: elencos com poucas estatísticas (México 49% e Grécia 47% dos
  // jogadores sem jogos) recebiam sistematicamente características piores que
  // elencos bem documentados — exatamente o desbalanceamento a corrigir.
  //
  // A correção é usar como peso-base a frequência EMPÍRICA com que o caminho de
  // scoring escolhe cada par naquela posição, em vez de 10.0 fixo. As afinidades
  // por atributo (charAffinity) continuam aplicadas por cima, então altura/idade
  // seguem individualizando o jogador — o prior só corrige o ponto de partida.
  //
  // SUAVIZAÇÃO: usa-se 80% do prior empírico + 20% de peso uniforme
  // (PRIOR_BLEND). Isso preserva o formato da distribuição real sem copiar
  // exageros do scoring (ex.: Des/Vel responde por 29% dos meias, o que parece
  // excesso do scoreDes e não uma verdade do futebol — ver relatório).
  //
  // Valores = % de ocorrência no caminho de scoring, agregados sobre as 7 ligas.
  // Pares legais no pool mas nunca escolhidos pelo scoring recebem PRIOR_FLOOR
  // (não zero: mantêm variedade).
  //
  // v7.1 — ESTABILIDADE ENTRE LIGAS: o ranking dos pares dominantes se repete em
  // todas as ligas, mas a magnitude varia bastante (ex.: Fin/Vel para atacantes
  // oscila de 15% a 38% entre ligas; SGo/Ref para goleiros, de 9% a 21%, porque
  // goleiros brasileiros têm mediana 1,90m contra 1,87m das demais). Essa
  // dispersão real é a razão de manter PRIOR_BLEND abaixo de 1.0: o prior indica
  // a forma da distribuição, não um alvo exato a ser replicado.

  /** Peso de piso para pares que o scoring nunca escolheu, em % (mantém variedade). */
  private static final double PRIOR_FLOOR = 1.5;

  /** Mistura prior empírico × uniforme: 0.80 = 80% empírico, 20% uniforme. */
  private static final double PRIOR_BLEND = 0.80;

  /** Peso uniforme de referência (média aproximada de um pool de ~12 pares). */
  private static final double PRIOR_UNIFORM = 8.0;

  private static final Map<Integer, Map<String, Double>> EMPIRICAL_PRIORS = buildEmpiricalPriors();

  private static Map<Integer, Map<String, Double>> buildEmpiricalPriors() {
    Map<Integer, Map<String, Double>> byPos = new HashMap<>();

    // ── Goleiro (n=462) ──
    Map<String, Double> gk = new HashMap<>();
    gk.put("SGo/Ref", 22.3); gk.put("SGo/Col", 15.4); gk.put("Ref/Col", 11.9);
    gk.put("Ref/SGo", 11.7); gk.put("Col/Ref", 8.9); gk.put("Col/SGo", 7.4);
    gk.put("DPe/Col", 5.4); gk.put("Ref/DPe", 5.2); gk.put("SGo/DPe", 3.5);
    gk.put("Col/DPe", 3.0); gk.put("DPe/Ref", 2.8); gk.put("DPe/SGo", 2.6);
    byPos.put(0, gk);

    // ── Lateral (n=725) ──
    Map<String, Double> lat = new HashMap<>();
    lat.put("Cru/Vel", 17.1); lat.put("Vel/Cru", 16.4); lat.put("Cru/Pas", 13.0);
    lat.put("Cru/Fin", 11.2); lat.put("Cru/Mar", 10.3); lat.put("Vel/Mar", 10.2);
    lat.put("Vel/Pas", 9.1); lat.put("Mar/Fin", 4.1); lat.put("Mar/Vel", 3.0);
    lat.put("Mar/Cru", 2.9); lat.put("Cru/Des", 1.5); lat.put("Pas/Vel", 1.5);
    byPos.put(1, lat);

    // ── Zagueiro (n=780) ──
    Map<String, Double> zag = new HashMap<>();
    zag.put("Des/Mar", 29.9); zag.put("Mar/Pas", 13.1); zag.put("Mar/Des", 11.9);
    zag.put("Des/Cab", 9.1); zag.put("Des/Pas", 7.6); zag.put("Mar/Res", 6.5);
    zag.put("Mar/Cab", 6.0); zag.put("Mar/Vel", 5.4); zag.put("Des/Res", 3.7);
    zag.put("Cab/Des", 3.6); zag.put("Cab/Mar", 3.2);
    byPos.put(2, zag);

    // ── Meio-campo (n=1323) ──
    Map<String, Double> mei = new HashMap<>();
    mei.put("Des/Vel", 13.8); mei.put("Arm/Pas", 13.1); mei.put("Fin/Pas", 11.4);
    mei.put("Mar/Pas", 7.9); mei.put("Fin/Dri", 7.8); mei.put("Des/Mar", 6.7);
    mei.put("Des/Pas", 5.4); mei.put("Dri/Pas", 4.8); mei.put("Arm/Vel", 4.5);
    mei.put("Fin/Arm", 4.2); mei.put("Mar/Des", 4.1); mei.put("Arm/Fin", 3.8);
    mei.put("Pas/Dri", 2.7); mei.put("Arm/Dri", 2.3); mei.put("Mar/Fin", 2.2);
    mei.put("Mar/Res", 2.0); mei.put("Des/Fin", 1.5); mei.put("Des/Res", 1.5);
    mei.put("Vel/Pas", 1.5);
    byPos.put(3, mei);

    // ── Atacante (n=1280) ──
    Map<String, Double> ata = new HashMap<>();
    ata.put("Fin/Vel", 32.4); ata.put("Fin/Cab", 25.5); ata.put("Vel/Dri", 19.7);
    ata.put("Cab/Vel", 7.5); ata.put("Fin/Dri", 5.5); ata.put("Cab/Fin", 4.5);
    ata.put("Vel/Fin", 3.0); ata.put("Fin/Res", 1.5); ata.put("Dri/Pas", 1.5);
    ata.put("Dri/Fin", 1.5);
    byPos.put(4, ata);

    return byPos;
  }

  /**
   * Peso-base de um par, já misturado entre o prior empírico da posição e o
   * peso uniforme. Substitui o antigo valor fixo 10.0 do v6.0.
   *
   * @param pos posição Brasfoot resolvida (0=GK, 1=LAT, 2=ZAG, 3=MEI, 4=ATA)
   */
  private static double basePairWeight(String pair, int pos) {
    Map<String, Double> priors = EMPIRICAL_PRIORS.get(pos);
    double empirical = (priors == null) ? PRIOR_UNIFORM
        : priors.getOrDefault(pair, PRIOR_FLOOR);
    return PRIOR_BLEND * empirical + (1.0 - PRIOR_BLEND) * PRIOR_UNIFORM;
  }

  // ── v7.0: Imputação de altura ─────────────────────────────────────────────
  //
  // MOTIVAÇÃO: a altura é o sinal mais forte do fallback, mas falta em 38% dos
  // jogadores da Grécia (e 17% da Argentina). Quando height=0, o v6.0
  // neutralizava TODOS os bônus de altura, deixando esses jogadores praticamente
  // sem sinal — 30% do elenco grego caía em "sem altura E sem estatísticas".
  //
  // Em vez de neutralizar, imputa-se a mediana da posição, medida em 3.506
  // jogadores com altura conhecida de 5 ligas (ARG2, GR1, GRS2, MEXA, BRA1).
  // Como é estimativa e não dado real, a afinidade derivada dela entra com peso
  // reduzido (IMPUTED_HEIGHT_DAMPING) — melhor que nada, pior que o dado real.
  //
  // IMPORTANTE: usada SOMENTE no fallback. O caminho de scoring por estatísticas
  // continua lendo m.height cru, sem imputação.

  /** Fator aplicado à afinidade de altura quando a altura foi imputada. */
  private static final double IMPUTED_HEIGHT_DAMPING = 0.5;

  /**
   * Retorna a altura a usar no fallback: a real quando existe, senão a mediana
   * da posição. Medianas de 5.458 jogadores com altura conhecida (7 datasets:
   * BRA1, BRA2, BRA3, ARG2, GR1, GRS2, MEXA):
   *   GK 1,89 | ZAG 1,86 | CA 1,83 | VOL 1,79 | Defensor genérico 1,78
   *   LAT 1,77 | Atacante genérico 1,77 | Ponta 1,76 | MEI 1,76
   */
  private static double effectiveHeight(Metrics m) {
    if (m.height > 0) return m.height;
    String p = m.posText.toLowerCase(Locale.ROOT);
    if (p.contains("goleiro")) return 1.89;
    if (p.contains("zagueiro")) return 1.86;
    if (p.contains("centroavante")) return 1.83;
    if (p.contains("volante")) return 1.79;
    if (p.contains("defensor") || p.contains("defesa")) return 1.78;
    if (p.contains("lateral") || p.contains("ala")) return 1.77;
    if (p.contains("atacante")) return 1.77;
    if (p.contains("ponta")) return 1.76;
    if (p.contains("meia") || p.contains("meio")) return 1.76;
    return 1.78; // mediana geral de jogadores de linha
  }

  /**
   * Afinidade de UMA característica (por nome, ex. "Vel") com os atributos
   * estáticos do jogador. Retorna um delta somado ao peso-base do par.
   *
   * Sinais usados (todos disponíveis mesmo para jogadores sem stats):
   *   - Altura (real do JSON ou imputada pela mediana da posição — v7.0)
   *   - Idade  (m.age, Integer; null quando ausente)
   *   - Posições secundárias (m.secondary)
   *
   * Calibração validada contra elencos reais de BRA1, ARG2, GR1, GRS2 e MEXA:
   *   Goleiros 1,88m | Zagueiros 1,86m | Laterais 1,77m | Meias/Pontas 1,75m.
   */
  private static double charAffinity(String c, Metrics m, boolean isGk) {
    // wh acumula termos derivados da ALTURA; w acumula idade/secundárias.
    // Quando a altura é imputada (v7.0), só wh entra amortecido — idade e
    // posições secundárias são dados reais e mantêm peso integral.
    double w = 0.0;
    double wh = 0.0;
    double h = effectiveHeight(m);
    boolean imputed = (m.height <= 0);
    Integer age = m.age;
    String sec = String.join(" ", m.secondary).toLowerCase(Locale.ROOT);

    if (isGk) {
      switch (c) {
        case "SGo":
          if (h >= 1.92) wh += 10;        // goleiro gigante domina a área
          else if (h >= 1.88) wh += 5;
          break;
        case "Ref":
          if (h < 1.88) wh += 5;          // goleiro baixo compensa com reflexo
          if (age != null && age <= 24) w += 3;
          break;
        case "Col":
          if (age != null && age >= 29) w += 6;  // colocação vem com experiência
          else if (age != null && age >= 26) w += 3;
          break;
        case "DPe":
          if (h >= 1.90) wh += 2;         // envergadura ajuda em pênaltis
          // v10.0: alinhado ao scoreDPe, que deixou de ser um contador de idade
          // mas segue sendo um atributo de goleiro rodado. Sem estatística
          // nenhuma, um goleiro de 17 anos não tem como evidenciar defesa de
          // pênalti — antes disso a base recebia DPe como característica
          // principal com frequência.
          if (age != null && age <= 20) w -= 9;
          else if (age != null && age <= 23) w -= 5;
          else if (age != null && age >= 30) w += 5;
          break;
        default: break;
      }
      return w + wh * (imputed ? IMPUTED_HEIGHT_DAMPING : 1.0);
    }

    // ── Jogadores de linha ──────────────────────────────────────────────────
    switch (c) {
      case "Vel":
        if (age != null) {
          if (age <= 22) w += 8;
          else if (age <= 26) w += 4;
          if (age >= 34) w -= 10;        // veterano raramente é "velocista"
          else if (age >= 31) w -= 7;
        }
        if (h <= 1.74) wh += 3;          // baixinhos tendem a ser rápidos
        if (h >= 1.90) wh -= 3;
        break;

      case "Res":
        if (age != null && age >= 25 && age <= 32) w += 5; // auge físico
        break;

      case "Cab":
        if (h >= 1.90) wh += 10;
        else if (h >= 1.85) wh += 6;
        else if (h >= 1.80) wh += 2;
        else if (h <= 1.75) wh -= 8;     // 1,70m cabeceador não faz sentido
        break;

      case "Dri":
        if (h <= 1.72) wh += 7;          // perfil clássico do driblador baixo
        else if (h <= 1.76) wh += 4;
        if (age != null && age <= 24) w += 2;
        // Ponta que atua nos dois lados (invertido) → perfil de drible
        if (sec.contains("ponta")) w += 2;
        break;

      case "Arm":
        if (age != null && age >= 30) w += 6; // armador veterano cerebral
        else if (age != null && age >= 27) w += 3;
        break;

      case "Pas":
        if (age != null && age >= 30) w += 4;
        else if (age != null && age >= 27) w += 2;
        break;

      case "Mar":
        if (h >= 1.85) wh += 3;
        break;

      case "Fin":
        if (sec.contains("centroav")) w += 3;
        break;

      case "Cru":
        if (sec.contains("lateral") || sec.contains("ala")
            || sec.contains("meia esquerda") || sec.contains("meia direita")) w += 2;
        break;

      default: break;
    }
    return w + wh * (imputed ? IMPUTED_HEIGHT_DAMPING : 1.0);
  }

  /**
   * Sorteio ponderado de um par dentro de um pool.
   * Peso do par = max(1, priorEmpirico(par, pos) + afinidade(c1) + afinidade(c2)).
   * O rng deve vir seedado com stableSeed(m) para reprodutibilidade.
   *
   * @param resolvedPos posição Brasfoot já resolvida — define qual tabela de
   *                    priors empíricos (v7.0) se aplica.
   */
  private static String weightedDrawFromPool(Set<String> pool, Metrics m,
                                             boolean isGk, Random rng, int resolvedPos) {
    List<String> pairs = new ArrayList<>(pool);
    double[] weights = new double[pairs.size()];
    double total = 0;
    for (int i = 0; i < pairs.size(); i++) {
      String pair = pairs.get(i);
      String[] parts = pair.split("/");
      // v7.0: peso-base vem do prior empírico da posição (antes era fixo 10.0)
      double w = basePairWeight(pair, resolvedPos);
      if (parts.length == 2) {
        w += charAffinity(parts[0], m, isGk) + charAffinity(parts[1], m, isGk);
      }
      weights[i] = Math.max(1.0, w);
      total += weights[i];
    }
    double roll = rng.nextDouble() * total;
    double acc = 0;
    for (int i = 0; i < pairs.size(); i++) {
      acc += weights[i];
      if (roll <= acc) return pairs.get(i);
    }
    return pairs.get(pairs.size() - 1);
  }

  /**
   * Fallback PONDERADO por atributos para jogadores com amostra insuficiente
   * de estatísticas (< MIN_FALLBACK_SAMPLE jogos de carreira) e para os casos
   * excepcionais do scored path (scores insuficientes, nenhum par permitido).
   *
   * Substitui o antigo getFallbackRandom (sorteio uniforme, não determinístico).
   *
   * Retorna int[3]: {cr1, cr2, resolvedPos} — mesmo contrato do método antigo.
   *
   * GENERIC_DEF: o subperfil (LAT_DEF/LAT_OF/ZAG_NORMAL/ZAG_OFENSIVO) é
   * escolhido de forma ponderada por altura e secundárias, garantindo que um
   * "Defensor" de 1,90m tenda a virar Zagueiro, e um de 1,74m tenda a Lateral.
   *
   * GENERIC_MID / GENERIC_ATK — o pos nunca varia dentro do grupo (sempre 3 ou 4),
   *   então sorteia do pool unificado diretamente (ainda ponderado por atributos).
   */
  private static int[] getFallbackWeighted(String profile, int pos, Metrics m) {
    Random rng = new Random(stableSeed(m));
    Set<String> pool;
    int resolvedPos = pos;
    boolean isGk = false;

    if ("GENERIC_DEF".equals(profile)) {
      // Escolha ponderada do subperfil defensivo por altura e secundárias.
      // v7.0: LAT_OF entra com peso maior que LAT_DEF pelo mesmo motivo do
      // default de detectProfile — laterais reais recebem pares ofensivos
      // (Cru/Vel) com muito mais frequência que pares puramente defensivos.
      // v7.0: usa effectiveHeight, para que a mediana imputada também oriente
      // a escolha quando o JSON não traz altura (38% dos jogadores gregos).
      // v9.0: LAT_GEN (união dos pools de lateral) substitui o par LAT_DEF/LAT_OF,
      // pela mesma razão do default de detectProfile — sem amostra não há base
      // para escolher entre lateral ofensivo e defensivo.
      String[] subs = {"LAT_GEN", "LAT_GEN", "ZAG_NORMAL", "ZAG_OFENSIVO"};
      double[] w = {6, 14, 10, 5};
      double h = effectiveHeight(m);
      String sec = String.join(" ", m.secondary).toLowerCase(Locale.ROOT);
      if (h >= 1.86) { w[2] += 20; w[3] += 8; w[0] -= 4; w[1] -= 8; }
      else if (h <= 1.79) { w[0] += 8; w[1] += 16; w[2] -= 6; w[3] -= 4; }
      if (sec.contains("zague")) { w[2] += 10; w[3] += 4; }
      if (sec.contains("meia") || sec.contains("ponta")) { w[1] += 10; }
      double total = 0;
      for (int i = 0; i < w.length; i++) { w[i] = Math.max(1, w[i]); total += w[i]; }
      double roll = rng.nextDouble() * total, acc = 0;
      String chosen = subs[0];
      for (int i = 0; i < subs.length; i++) {
        acc += w[i];
        if (roll <= acc) { chosen = subs[i]; break; }
      }
      pool = getAllowedCombinations(chosen);
      resolvedPos = profileToPos(chosen);
      if (DEBUG) System.out.println("[DEBUG] getFallbackWeighted: GENERIC_DEF → subperfil="
          + chosen + " (h=" + h + ") resolvedPos=" + resolvedPos + " pool=" + pool.size() + " pares");

    } else if ("GENERIC_MID".equals(profile)) {
      pool = getGenericGroupPool(3);
      resolvedPos = 3;
      if (DEBUG) System.out.println("[DEBUG] getFallbackWeighted: GENERIC_MID → pool=" + pool.size() + " pares");

    } else if ("GENERIC_ATK".equals(profile)) {
      pool = getGenericGroupPool(4);
      resolvedPos = 4;
      if (DEBUG) System.out.println("[DEBUG] getFallbackWeighted: GENERIC_ATK → pool=" + pool.size() + " pares");

    } else {
      // Perfil específico conhecido
      isGk = "GK".equals(profile);
      pool = getAllowedCombinations(profile);
      resolvedPos = profileToPos(profile);
      // Perfil desconhecido ou vazio → usa pool genérico do grupo por pos
      if (pool.isEmpty()) {
        pool = getGenericGroupPool(pos);
        resolvedPos = pos;
        if (DEBUG) System.out.println("[DEBUG] getFallbackWeighted: profile='" + profile
            + "' vazio → usando getGenericGroupPool(pos=" + pos + ")");
      }
    }

    // Último recurso absoluto (não deveria acontecer)
    if (pool.isEmpty()) {
      if (DEBUG) System.out.println("[DEBUG] getFallbackWeighted: pool vazio, retornando Pas/Vel pos=" + pos);
      return new int[]{11, 13, pos};
    }

    // GK também pode chegar aqui via pool genérico — garante flag correta
    if (resolvedPos == 0) isGk = true;

    String chosenPair = weightedDrawFromPool(pool, m, isGk, rng, resolvedPos);
    int[] pair = parseCharPair(chosenPair);

    if (DEBUG) System.out.println("[DEBUG] getFallbackWeighted [" + profile + "]: sorteou "
        + chosenPair + " (age=" + m.age + " h=" + m.height + " sec=" + m.secondary
        + ") resolvedPos=" + resolvedPos);
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
