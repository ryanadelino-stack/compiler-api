// HeuristicsEngine.java
// Pacote: br.brasfoot.compiler
//
// Heuristicas Oficiais Brasfoot - v3.5.5
// Implementa escolha deterministica do par (cr1/cr2) conforme documento oficial.
//
// Regras principais:
// - Prioridade: posicao primaria > estatisticas > posicao secundaria (somente desempate tecnico)
// - Selecao por PARES (nao por "top2 atributos soltos"): cada par permitido recebe score proprio.
// - Formula base (par A/B):
//   score = 0.62*sA + 0.38*sB + 0.06*(sA*sB) - 0.05*|sA-sB| + boosts/penalidades
// - Resistencia e rara (penalidade global fora do caso "motor")
// - Anti-clone: aproximado e stateless (somente desempate), via ruido deterministico pequeno
//
// Indices de caracteristicas (0..13):
// 0 Colocacao, 1 Defesa Penalty, 2 Reflexo, 3 Saida gol,
// 4 Armacao, 5 Cabeceio, 6 Cruzamento, 7 Desarme, 8 Drible,
// 9 Finalizacao, 10 Marcacao, 11 Passe, 12 Resistencia, 13 Velocidade

package br.brasfoot.compiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class HeuristicsEngine {

  // Marker para logs / rastreio
  public static final String HEURISTICS_ENGINE_MARKER = "V3.5.5.3-OFICIAL-PAIRS";

  // Ruido deterministico (somente desempate, "anti-clone stateless")
  private static final double TIE_EPS = 0.015;

  // Empate tecnico real (para decisoes de desempate internas)
  private static final double TECH_TIE = 0.010;

  private HeuristicsEngine() {}

  // -------------------------
  // API (chamada pelo BanCompiler)
  // -------------------------
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

    final Metrics m =
        Metrics.from(
            pos,
            posText,
            secondaryPositions,
            matchesRelated,
            matchesPlayed,
            goals,
            assists,
            ownGoals,
            fromBench,
            substituted,
            yellow,
            yellowRed,
            red,
            penaltyGoals,
            minutesPerGoal,
            minutesPlayed,
            goalsConceded,
            cleanSheets,
            idade,
            heightM);

    // 1) Base scores por atributo (0..1)
    final double[] a = new double[14];
    computeBaseAttributeScores(a, m);

    // 2) Escolhe dominio e lista de pares candidatos
    final Domain dom = Domain.resolve(m);
    final List<Pair> candidates = dom.candidates();

    // 3) Avalia pares (score base + criterios/boosts)
    final long seed = m.playerKeySeed() ^ (dom.ordinal() * 0x9E3779B97F4A7C15L);

    Pair best = null;
    Pair second = null;
    double bestScore = -1e9;
    double secondScore = -1e9;

    for (Pair p : candidates) {
      double sc = scorePair(p, a, m, dom, seed);
      if (sc > bestScore) {
        second = best;
        secondScore = bestScore;
        best = p;
        bestScore = sc;
      } else if (sc > secondScore) {
        second = p;
        secondScore = sc;
      }
    }

    // Fallback duro (nao deveria ocorrer)
    if (best == null) best = dom.fallbackPair();
    if (second == null || second.equals(best)) second = dom.fallbackSecondPair(best);

    // 4) Desempate tecnico por posicao secundaria (somente se muito proximo)
    // Observacao: nao muda "vencedor claro", so atua no empate.
    if (second != null && Math.abs(bestScore - secondScore) <= TECH_TIE) {
      Pair tieChosen = breakTieBySecondary(best, second, m, dom, seed);
      if (tieChosen != null) best = tieChosen;
    }

    return new int[] {best.a, best.b};
  }

  // -------------------------
  // Dominios e pares candidatos
  // -------------------------
  private enum Domain {
    GK,
    DEF_CB,
    FB,
    MID_DM,
    MID_CM_TECH,
    MID_CM_MIX,
    MID_AM,
    FWD_WING,
    FWD_ST,
    FWD_DROP;

    List<Pair> candidates() {
      switch (this) {
        case GK:
          return Pairs.GK;
        case DEF_CB:
          return Pairs.CB;
        case FB:
          return Pairs.FB;
        case MID_DM:
          return Pairs.DM;
        case MID_CM_TECH:
          return Pairs.CM_TECH;
        case MID_CM_MIX:
          return Pairs.CM_MIX;
        case MID_AM:
          return Pairs.AM;
        case FWD_WING:
          return Pairs.WING;
        case FWD_ST:
          return Pairs.ST;
        case FWD_DROP:
          return Pairs.DROP;
        default:
          return Pairs.CM_TECH;
      }
    }

    Pair fallbackPair() {
      switch (this) {
        case GK:
          return new Pair(0, 2); // Col/Ref
        case DEF_CB:
          return new Pair(10, 7); // Mar/Des
        case FB:
          return new Pair(6, 13); // Cru/Vel
        case MID_DM:
          return new Pair(7, 10); // Des/Mar
        case MID_CM_TECH:
          return new Pair(4, 11); // Arm/Pas
        case MID_CM_MIX:
          return new Pair(7, 13); // Des/Vel
        case MID_AM:
          return new Pair(4, 9); // Arm/Fin
        case FWD_WING:
          return new Pair(13, 8); // Vel/Dri
        case FWD_ST:
          return new Pair(9, 8); // Fin/Dri
        case FWD_DROP:
          return new Pair(8, 11); // Dri/Pas
        default:
          return new Pair(11, 13);
      }
    }

    Pair fallbackSecondPair(Pair primary) {
      // tenta uma alternativa coerente sem repetir
      for (Pair p : candidates()) {
        if (!p.equals(primary)) return p;
      }
      return fallbackPair();
    }

    static Domain resolve(Metrics m) {
      if (m.pos == 0) return GK;

      // Zagueiro/Lateral/Meio/Ataque baseado no pos e texto.
      if (m.isCenterBackLike()) return DEF_CB;
      if (m.isFullBackLike()) return FB;

      // Meio: volante vs CM vs AM
      if (m.pos == 3) {
        if (m.isDefMidLike()) return MID_DM;

        // MID_AM por texto (ofensivo / atacante recuado / meia atacante)
        if (m.isAttMidLike()) return MID_AM;

        // CM (TECH vs MIX)
        boolean forceMix = (m.c90 >= 0.30 && m.a90 < 0.12 && m.g90 < 0.12);
        // regra: se g90 >= 0.12, nao forcar MIX
        if (m.g90 >= 0.12) forceMix = false;
        return forceMix ? MID_CM_MIX : MID_CM_TECH;
      }

      // Ataque: ponta vs ST vs recuado
      if (m.pos == 4) {
        if (m.isWingerLike()) return FWD_WING;
        if (m.isDropStrikerLike()) return FWD_DROP;
        return FWD_ST;
      }

      // default conservador
      return MID_CM_TECH;
    }
  }

  private static final class Pair {
    final int a;
    final int b;

    Pair(int a, int b) {
      this.a = a;
      this.b = b;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Pair)) return false;
      Pair p = (Pair) o;
      return this.a == p.a && this.b == p.b;
    }

    @Override
    public int hashCode() {
      return (a * 31) ^ b;
    }

    @Override
    public String toString() {
      return a + "/" + b;
    }
  }

  private static final class Pairs {
    // GK
    static final List<Pair> GK =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(0, 2), // Col/Ref
                new Pair(2, 0), // Ref/Col
                new Pair(2, 1), // Ref/DPe
                new Pair(1, 2), // DPe/Ref
                new Pair(3, 2), // SGo/Ref
                new Pair(2, 3), // Ref/SGo
                new Pair(0, 1), // Col/DPe
                new Pair(1, 0), // DPe/Col
                new Pair(3, 1), // SGo/DPe
                new Pair(1, 3), // DPe/SGo
                new Pair(3, 0), // SGo/Col
                new Pair(0, 3) // Col/SGo
                ));

    // CB
    static final List<Pair> CB =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(7, 10), // Des/Mar
                new Pair(10, 7), // Mar/Des
                new Pair(7, 11), // Des/Pas
                new Pair(10, 11), // Mar/Pas
                new Pair(7, 12), // Des/Res
                new Pair(10, 12), // Mar/Res
                new Pair(10, 13), // Mar/Vel
                new Pair(7, 5), // Des/Cab
                new Pair(5, 7), // Cab/Des
                new Pair(10, 5), // Mar/Cab
                new Pair(5, 10) // Cab/Mar
                ));

    // FB
    static final List<Pair> FB =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(10, 6), // Mar/Cru
                new Pair(6, 10), // Cru/Mar
                new Pair(10, 9), // Mar/Fin
                new Pair(10, 13), // Mar/Vel
                new Pair(13, 10), // Vel/Mar
                new Pair(7, 6), // Des/Cru
                new Pair(6, 7), // Cru/Des
                new Pair(13, 11), // Vel/Pas
                new Pair(11, 13), // Pas/Vel
                new Pair(6, 13), // Cru/Vel
                new Pair(13, 6), // Vel/Cru
                new Pair(6, 11), // Cru/Pas
                new Pair(6, 9) // Cru/Fin
                ));

    // DM
    static final List<Pair> DM =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(7, 11), // Des/Pas
                new Pair(10, 11), // Mar/Pas
                new Pair(10, 12), // Mar/Res
                new Pair(7, 12), // Des/Res
                new Pair(7, 10), // Des/Mar
                new Pair(10, 7), // Mar/Des
                new Pair(10, 9), // Mar/Fin
                new Pair(7, 9), // Des/Fin
                new Pair(7, 13) // Des/Vel
                ));

    // CM TECH
    static final List<Pair> CM_TECH =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(11, 13), // Pas/Vel
                new Pair(13, 11), // Vel/Pas
                new Pair(11, 12), // Pas/Res (penalizada globalmente)
                new Pair(4, 13), // Arm/Vel
                new Pair(4, 8), // Arm/Dri
                new Pair(8, 11), // Dri/Pas
                new Pair(11, 8), // Pas/Dri
                new Pair(4, 11) // Arm/Pas
                ));

    // CM MIX
    static final List<Pair> CM_MIX =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(7, 13), // Des/Vel
                new Pair(7, 11), // Des/Pas
                new Pair(10, 11), // Mar/Pas
                new Pair(7, 12), // Des/Res
                new Pair(10, 12), // Mar/Res
                new Pair(7, 10), // Des/Mar
                new Pair(10, 9), // Mar/Fin
                new Pair(7, 9) // Des/Fin
                ));

    // AM (v3.5.5 - dominantes)
    static final List<Pair> AM =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(4, 9), // Arm/Fin
                new Pair(4, 11), // Arm/Pas
                new Pair(4, 8), // Arm/Dri
                new Pair(8, 11), // Dri/Pas
                new Pair(9, 11), // Fin/Pas
                new Pair(11, 9), // Pas/Fin
                new Pair(11, 4), // Pas/Arm
                new Pair(9, 4) // Fin/Arm
                ));

    // WING (inclui Dri/Vel conforme v3.5.4/5)
    static final List<Pair> WING =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(13, 9), // Vel/Fin
                new Pair(9, 13), // Fin/Vel
                new Pair(13, 8), // Vel/Dri
                new Pair(8, 13), // Dri/Vel
                new Pair(9, 8), // Fin/Dri
                new Pair(8, 9) // Dri/Fin
                ));

    // ST
    static final List<Pair> ST =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(9, 11), // Fin/Pas
                new Pair(9, 5), // Fin/Cab
                new Pair(5, 9), // Cab/Fin
                new Pair(9, 8), // Fin/Dri
                new Pair(9, 12), // Fin/Res
                new Pair(9, 13), // Fin/Vel
                new Pair(13, 9), // Vel/Fin
                new Pair(5, 13) // Cab/Vel
                ));

    // DROP
    static final List<Pair> DROP =
        Collections.unmodifiableList(
            Arrays.asList(
                new Pair(8, 11), // Dri/Pas
                new Pair(8, 9), // Dri/Fin
                new Pair(11, 9) // Pas/Fin
                ));
  }

  // -------------------------
  // Base attribute scores (0..1)
  // -------------------------
  private static void computeBaseAttributeScores(double[] a, Metrics m) {
    // Zera todos
    for (int i = 0; i < a.length; i++) a[i] = 0.0;

    if (m.pos == 0) {
      computeGoalkeeperScores(a, m);
      return;
    }

    // FIN
    double sFin = 0.70 * sat(m.g90, 0.05, 0.55) + 0.30 * (1.0 - sat(m.mpg, 180.0, 520.0));
    if (m.goals > 0 && (m.penaltyGoals / (double) m.goals) >= 0.35) sFin += bonus(0.04);
    if (m.isMostlyBench() && m.mp < 1200) sFin -= bonus(0.03);
    a[9] = clamp01(sFin);

    // PAS
    double sPas = sat(m.a90, 0.03, 0.35);
    if (m.a90 >= m.g90) sPas += bonus(0.03);
    if (m.c90 > 0.45) sPas -= bonus(0.02);
    a[11] = clamp01(sPas);

    // ARM
    double sArm = 0.60 * sat(m.a90, 0.04, 0.30) + 0.40 * sat(m.p90, 0.08, 0.55);
    a[4] = clamp01(sArm);

    // DRI (proxy: participacao + rotacao)
    double sDri = 0.60 * sat(m.p90, 0.10, 0.60) + 0.40 * sat(m.rotation, 0.10, 0.90);
    a[8] = clamp01(sDri);

    // VEL (proxy: rotacao + participacao)
    double sVel = 0.65 * sat(m.rotation, 0.10, 0.90) + 0.35 * sat(m.p90, 0.08, 0.55);
    // penalidade: ponta muito alta (sem metrica de sprint real) reduz prob. de Vel dominar
    if (m.isWingerLike() && m.height >= 1.90) sVel -= bonus(0.06);
    // penalidade: CB "travado" (sem producao) reduz Vel
    if (m.isCenterBackLike() && (m.g90 + m.a90) < 0.06) sVel -= bonus(0.04);
    a[13] = clamp01(sVel);

    // RES (base; penalidade global sera aplicada por par)
    double sRes = 0.60 * sat(m.mp, 800, 3200) + 0.40 * sat(m.playRate, 0.35, 0.90);
    a[12] = clamp01(sRes);

    // CAB (base: altura + sinal ofensivo; inclui um pouco de "gols acumulados" para não punir zagueiros/atacantes de carreira longa)
    double sCab = 0.40 * sat(m.height, 1.78, 1.95)
        + 0.45 * sat(m.g90, 0.04, 0.22)
        + 0.15 * sat(m.goals, 3, 30);
    // bonus por funcao tipica (CB/ST), penalidade para lateral
    if (m.isCenterBackLike() || m.isStrikerLike()) sCab += bonus(0.04);
    if (m.isFullBackLike()) sCab -= bonus(0.06);
    a[5] = clamp01(sCab);

    // DES
    double sDes = 0.65 * sat(m.c90, 0.10, 0.55) + 0.35 * sat(m.played, 10, 45);
    a[7] = clamp01(sDes);

    // MAR
    double sMar = 0.55 * sat(m.c90, 0.08, 0.45) + 0.45 * sat(m.mp, 800, 3200);
    if (m.isCenterBackLike() || m.isDefMidLike()) sMar += bonus(0.06);
    if (m.isAttackerLike()) sMar -= bonus(0.06);
    a[10] = clamp01(sMar);

    // CRU
    double sCru = sat(m.a90, 0.04, 0.30);
    if (m.isFullBackLike()) sCru += bonus(0.06);
    if (m.isWingerLike()) sCru += bonus(0.02);
    if (m.isCenterBackLike()) sCru -= bonus(0.06);
    a[6] = clamp01(sCru);

    // GK attrs 0..3 permanecem 0 para linha
    a[0] = 0;
    a[1] = 0;
    a[2] = 0;
    a[3] = 0;
  }

  private static void computeGoalkeeperScores(double[] a, Metrics m) {
    final double played = Math.max(1.0, m.played);
    final double gpg = m.gc / played;
    final double csr = m.cs / played;

    // REF
    double sRef = 0.55 * sat(1.0 - gpg, 0.05, 0.55) + 0.45 * sat(csr, 0.15, 0.55);
    a[2] = clamp01(sRef);

    // COL
    double sCol = 0.65 * sat(csr, 0.15, 0.60) + 0.35 * sat(m.mp, 900, 4000);
    a[0] = clamp01(sCol);

    // SGO
    double sSGo = 0.78 * sat(m.height, 1.80, 2.05) + 0.22 * sat(m.mp, 900, 3500);
    a[3] = clamp01(sSGo);

    // DPE
    double sDPe = 0.50 * sat(m.penaltyGoals, 0, 6) + 0.50 * sat(m.mp, 900, 4000);
    a[1] = clamp01(sDPe);

    // Linha: zera
    a[4] = 0;
    a[5] = 0;
    a[6] = 0;
    a[7] = 0;
    a[8] = 0;
    a[9] = 0;
    a[10] = 0;
    a[11] = 0;
    a[12] = 0;
    a[13] = 0;
  }

  // -------------------------
  // Pair scoring (base + boosts/penalidades)
  // -------------------------
  private static double scorePair(Pair p, double[] a, Metrics m, Domain dom, long seed) {
    double sA = a[p.a];
    double sB = a[p.b];

    double base = 0.62 * sA + 0.38 * sB + 0.06 * (sA * sB) - 0.05 * Math.abs(sA - sB);

    double boost = 0.0;

    // Anti-clone stateless (pequeno) somente para desempatar
    boost += tieNoise(seed, p.a, p.b);

    // Penalidade global de Resistencia fora do caso "motor"
    if (p.a == 12 || p.b == 12) {
      if (!m.isEnduranceMotor()) {
        boost -= 0.20; // forte: Res deve ser rara fora do caso "motor"
      } else {
        boost += 0.08; // motor real pode sustentar Res
      }
    }

    switch (dom) {
      case GK:
        boost += boostGK(p, m);
        break;
      case DEF_CB:
        boost += boostCB(p, m);
        break;
      case FB:
        boost += boostFB(p, m);
        break;
      case MID_DM:
        boost += boostDM(p, m);
        break;
      case MID_CM_TECH:
      case MID_CM_MIX:
        boost += boostCM(p, m, dom);
        break;
      case MID_AM:
        boost += boostAM(p, m);
        break;
      case FWD_WING:
        boost += boostWing(p, m);
        break;
      case FWD_ST:
        boost += boostST(p, m);
        break;
      case FWD_DROP:
        // sem boosts especificos alem do global (por enquanto)
        break;
      default:
        break;
    }

    return base + boost;
  }

  private static double boostGK(Pair p, Metrics m) {
    final double played = Math.max(1.0, m.played);
    final double csr = m.cs / played;
    final double gpg = m.gc / played;


    // "Goleiro de posição" experiente (muitos jogos + boa taxa de CS + poucos gols sofridos por jogo)
    // tende a ser mais Col/DPe (ou DPe/Col) do que Col/SGo.
    double b = 0.0;

    if (m.mp >= 30000 && csr >= 0.30 && gpg <= 1.15 && (m.age == null || m.age >= 32)) {
      if ((p.a == 0 && p.b == 2) || (p.a == 2 && p.b == 0)) b += 0.10; // Col/DPe
      if ((p.a == 0 && p.b == 3) || (p.a == 3 && p.b == 0)) b -= 0.05; // Col/SGo
    }

    // Jovem consistente -> Col/Ref ou Ref/Col dominantes (nao forca: da boost e penaliza levemente outros)
    if (m.age != null && m.age <= 27 && m.mp >= 4000 && gpg <= 1.10) {
      if ((p.a == 0 && p.b == 2) || (p.a == 2 && p.b == 0)) b += 0.06;
      else b -= 0.08;
    }

    // Altura extrema + muitos minutos -> SGo/Ref vence SGo/Col
    if (m.height >= 1.95 && m.mp >= 8000 && csr >= 0.30) {
      if (p.a == 3 && p.b == 2) b += 0.08; // SGo/Ref
      if (p.a == 3 && p.b == 0) b -= 0.02; // SGo/Col perde um pouco
    }

    // Altura extrema adicional (>=2.00) -> SGo ganha competitividade
    if (m.height >= 2.00 && m.mp >= 2500) {
      if (p.a == 3) b += 0.05; // qualquer SGo/*
    }

    // Instavel: gpg alto + csr baixo -> Col/DPe
    if (gpg >= 1.55 && csr <= 0.22) {
      if ((p.a == 0 && p.b == 1) || (p.a == 1 && p.b == 0)) b += 0.06;
    }


    return b;
  }

  private static double boostCB(Pair p, Metrics m) {
    double b = 0.0;

    // Cab elegivel?
    // Regra oficial: Cab entra por altura OU por producao real (g90) em CB, para nao depender de parsing de altura.
    boolean cabOk = (m.height >= 1.85)
        || (m.mp >= 1800 && m.g90 >= 0.10)
        || (m.mp >= 12000 && m.goals >= 15);
    // CB aereo defensivo (v3.5.x): permite Cab mesmo com g90 baixo, desde que alto e com minutagem
    if (!cabOk && m.height >= 1.86 && m.mp >= 1800) cabOk = true;

    if ((p.a == 5 || p.b == 5) && !cabOk) b -= 0.22;

    // CB aereo defensivo: boost Mar/Cab e Cab/Mar
    if (m.height >= 1.86 && m.mp >= 1800 && m.c90 <= 0.22) {
      if ((p.a == 10 && p.b == 5) || (p.a == 5 && p.b == 10)) b += 0.04;
    }

    // CB com producao real (g90 alto) -> Cab ganha competitividade mesmo se altura estiver ausente/ruim
    if (m.mp >= 1800 && m.g90 >= 0.10) {
      if ((p.a == 10 && p.b == 5) || (p.a == 5 && p.b == 10)) b += 0.06;
      if ((p.a == 7 && p.b == 5) || (p.a == 5 && p.b == 7)) b += 0.03;
    }

    // CB cabeceador (altura ok + g90 razoavel) -> favorece Mar/Cab
    if (m.height >= 1.85 && m.mp >= 1800 && m.g90 >= 0.08) {
      if (p.a == 10 && p.b == 5) b += 0.04;
      if (p.a == 7 && p.b == 5) b += 0.02;
    }

    // Tiebreak Cab (Mar/Cab vs Des/Cab)
    if (p.a == 10 && p.b == 5) { // Mar/Cab
      if (m.c90 >= 0.22) b += 0.02;
      else b -= 0.02;
    }
    if (p.a == 7 && p.b == 5) { // Des/Cab
      if (m.c90 < 0.22) b += 0.02;
      else b -= 0.02;
    }

    // CB movel: rotation alta + pouca ameaca ofensiva -> Mar/Vel
    if (m.rotation >= 0.35 && m.g90 < 0.06) {
      if (p.a == 10 && p.b == 13) b += 0.04; // Mar/Vel
    }

    // CB "ofensivo" por producao real e disciplina alta -> tende a Mar/Cab
    // (caso Gustavo Gomez-like: g90 relevante + c90 alto)
    if (cabOk && m.mp >= 1800 && m.g90 >= 0.09 && m.c90 >= 0.22) {
      if (p.a == 10 && p.b == 5) b += 0.06; // Mar/Cab dominante
      if (p.a == 10 && p.b == 12) b -= 0.10; // afasta Mar/Res
      if (p.a == 7 && p.b == 12) b -= 0.08; // afasta Des/Res
    }

    // Evita Res em CB (mesmo com muita minutagem, Res nao deve dominar zagueiros)
    if (p.a == 12 || p.b == 12) {
      if (!m.isEnduranceMotor()) b -= 0.35; // muito forte
      else b -= 0.12;
    }

    return b;
  }

  private static double boostFB(Pair p, Metrics m) {
    double b = 0.0;

    // FB/ALAS: queremos separar "lateral construtor/ala" (Cru/Vel, Cru/Pas, Vel/Pas...)
    // de "lateral marcador" (Mar/...) usando produção ofensiva por 90.
    final boolean veryAttacking = (m.a90 >= 0.08) || (m.p90 >= 0.12) || (m.g90 >= 0.06);
    final boolean attacking = (m.a90 >= 0.06) || (m.p90 >= 0.10);
    final boolean defensive = (m.a90 <= 0.05 && m.p90 <= 0.09) || (m.c90 >= 0.18); // c90 = cartões por 90 (proxy de agressividade)

    // 1) Lateral ofensivo: empurra forte para Cru/Vel e variações
    if (veryAttacking) {
      // Cru/Vel & Vel/Cru
      if ((p.a == 6 && p.b == 13) || (p.a == 13 && p.b == 6)) b += 0.55;

      // Cru/Pas, Pas/Cru
      if ((p.a == 6 && p.b == 11) || (p.a == 11 && p.b == 6)) b += 0.40;

      // Vel/Pas, Pas/Vel
      if ((p.a == 13 && p.b == 11) || (p.a == 11 && p.b == 13)) b += 0.22;

      // Dri/Cru (ala driblador), Cru/Dri
      if ((p.a == 9 && p.b == 6) || (p.a == 6 && p.b == 9)) b += 0.12;

      // Penaliza perfis de marcador quando a produção ofensiva é alta
      if (p.a == 10 || p.b == 10) b -= 0.28; // qualquer Mar/*
      if ((p.a == 10 && p.b == 6) || (p.a == 6 && p.b == 10)) b -= 0.18; // Mar/Cru específico
      if ((p.a == 10 && p.b == 13) || (p.a == 13 && p.b == 10)) b -= 0.25; // Mar/Vel (tende a aparecer demais)
    } else if (attacking) {
      // 2) Lateral moderadamente ofensivo: ainda favorece Cru
      if (p.a == 6 || p.b == 6) b += 0.12;
      if ((p.a == 6 && p.b == 13) || (p.a == 13 && p.b == 6)) b += 0.18;
      if ((p.a == 6 && p.b == 11) || (p.a == 11 && p.b == 6)) b += 0.10;

      // evita "Mar/Vel" virar padrão
      if ((p.a == 10 && p.b == 13) || (p.a == 13 && p.b == 10)) b -= 0.08;
    }

    // 3) Lateral mais defensivo/agressivo: Mar/Cru faz sentido (ex.: Varela/Emerson em alguns contextos)
    if (defensive) {
      if (p.a == 10 || p.b == 10) b += 0.10; // Mar/*
      if ((p.a == 10 && p.b == 6) || (p.a == 6 && p.b == 10)) b += 0.22; // Mar/Cru prioriza cruzamento + disciplina defensiva
      if ((p.a == 10 && p.b == 13) || (p.a == 13 && p.b == 10)) b -= 0.10; // desprioriza Mar/Vel
    }

    return b;
  }

  private static double boostDM(Pair p, Metrics m) {
    // % de gols de pênalti (proxy simples) para ajustar perfil: artilheiro de bola parada
    final double penShare = (m.goals <= 0) ? 0.0 : ((double) m.penaltyGoals / (double) m.goals);
    double b = 0.0;

    // Camisa 5 (camisa 5 dominante)
    if (m.c90 >= 0.24 && m.p90 < 0.08) {
      if (p.a == 7 && p.b == 10) b += 0.12; // Des/Mar
      // afasta pares "de apoio" e principalmente Res
      if (p.a == 10 && p.b == 12) b -= 0.10; // Mar/Res
      if (p.a == 7 && p.b == 12) b -= 0.10; // Des/Res
    }

    // Camisa 8 (volante de passe / construtor): participa e tem a90 decente
// - Mar/Pas e Des/Pas devem ser dominantes quando a90 e p90 sao bons e c90 nao indica "carrinhozento".
if (m.a90 >= 0.09 && m.p90 >= 0.16 && m.c90 < 0.22) {
  if (p.a == 10 && p.b == 11) b += 0.08; // Mar/Pas (Marlon Freitas)
  if (p.a == 7 && p.b == 11) b += 0.06; // Des/Pas
  // nesses perfis, Mar/Des nao deve vencer
  if ((p.a == 10 && p.b == 7) || (p.a == 7 && p.b == 10)) b -= 0.05;
} else if (m.a90 >= 0.10 && m.p90 >= 0.14) {
  if (p.a == 10 && p.b == 11) b += 0.05; // Mar/Pas
  if (p.a == 7 && p.b == 11) b += 0.04; // Des/Pas
}

    // Box-to-box: chega na area
    if (m.g90 >= 0.12) {
      if ((p.a == 10 && p.b == 9) || (p.a == 7 && p.b == 9)) b += 0.03;
    }

    // Resistencia em volante so deve vencer com motor REAL (ja ha regra global, aqui reforca)
    if (p.a == 12 || p.b == 12) {
      if (!m.isEnduranceMotor()) b -= 0.10;
    }

    return b;
  }

  private static double boostCM(Pair p, Metrics m, Domain dom) {
    double b = 0.0;

    // Evita Res em CM tambem (ja penaliza globalmente)
    if (p.a == 12 || p.b == 12) b -= 0.02;

    // Regra: Vel so compete quando rotation e alta
    if (m.rotation < 0.65) {
      if (p.b == 13 || p.a == 13) {
        // penaliza pares com Vel no CM quando rotacao nao justifica
        b -= 0.02;
      }
    }

    // Preferencia Pas/Dri em empates (p90 >= 0.15)
    if (m.p90 >= 0.15) {
      if (p.a == 11 && p.b == 8) b += 0.02; // Pas/Dri
      if (p.a == 8 && p.b == 11) b += 0.01; // Dri/Pas
    }

    // CM MIX tende a aceitar Des/Vel com c90 alto
    if (dom == Domain.MID_CM_MIX && m.c90 >= 0.30) {
      if (p.a == 7 && p.b == 13) b += 0.02;
    }

    // Volante construtor (caso Jorginho / 8 organizador): assists + passes razoáveis e poucas faltas/cartões
    if ((m.a90 >= 0.06 && m.p90 >= 0.14) && m.c90 <= 0.30) {
      if (p.a == 10 && p.b == 11) b += 0.14; // Mar/Pas
      if (p.a == 10 && p.b == 7)  b -= 0.08; // evita Mar/Des genérico
    }

    return b;
  }

  private static double boostAM(Pair p, Metrics m) {
    double b = 0.0;

    // 1) Arm/Pas (dominante para armadores) e Pas/Arm (cadenciador)
    // - Arm/Pas: playmaker com p90 alto e g90 moderado
    if (m.a90 >= 0.17 && m.p90 >= 0.28 && m.g90 < 0.22) {
      if (p.a == 4 && p.b == 11) b += 0.07; // Arm/Pas (ex: Andreas Pereira)
      if (p.a == 11 && p.b == 4) b += 0.03; // Pas/Arm
      // nesses perfis, Dri/Pas nao deve dominar
      if (p.a == 8 && p.b == 11) b -= 0.03;
    }
    // Pas/Arm mais "puro": muito a90 e pouco g90
    if (m.a90 >= 0.22 && m.g90 < 0.18) {
      if (p.a == 11 && p.b == 4) b += 0.04; // Pas/Arm
    }

    // 2) Fin/Pas e Pas/Fin (meia decisivo)
    if (m.g90 >= 0.28 || m.mpg <= 240.0) {
      if (p.a == 9 && p.b == 11) b += 0.04; // Fin/Pas
      if (p.a == 4 && p.b == 9) b += 0.02; // Arm/Fin segue competitivo
    }
    // Perfil completo (g90 + a90): Pas/Fin e Fin/Pas devem dominar (ex: Mauricio-like)
if (m.a90 >= 0.18 && m.g90 >= 0.18) {
  if (p.a == 11 && p.b == 9) b += 0.14; // Pas/Fin
  if (p.a == 9 && p.b == 11) b += 0.06; // Fin/Pas
  // nesses perfis, Dri/Pas nao pode roubar o lugar
  if (p.a == 8 && p.b == 11) b -= 0.06;
} else if (m.a90 >= 0.15 && m.g90 >= 0.20) {
  if (p.a == 11 && p.b == 9) b += 0.06; // Pas/Fin
  if (p.a == 9 && p.b == 11) b += 0.02; // Fin/Pas tambem
}

    // 3) Arm/Fin e Fin/Arm (ponta de classe / meia-atacante decisivo)
    if (m.a90 >= 0.16 && m.g90 >= 0.18) {
      if (p.a == 4 && p.b == 9) b += 0.04; // Arm/Fin
    }
    if (m.g90 >= 0.32 && m.a90 >= 0.12) {
      if (p.a == 9 && p.b == 4) b += 0.04; // Fin/Arm
    } else {
      if (p.a == 9 && p.b == 4) b -= 0.02;
    }

    // 4) Dri/Pas condutor (somente quando faz sentido: rotacao alta e nao e "armador puro")
    if (m.rotation >= 0.45 && m.p90 >= 0.30 && (m.a90 < 0.18 || m.g90 >= 0.20)) {
      if (p.a == 8 && p.b == 11) b += 0.04; // Dri/Pas
    }
    if (m.rotation >= 0.45 && m.p90 >= 0.30 && m.a90 >= 0.18 && m.g90 < 0.18) {
      // playmaker de assistencia: reduz Dri/Pas
      if (p.a == 8 && p.b == 11) b -= 0.03;
    }

    
    // Meia decisivo (ex: g90 ~0.20+ e bom volume de criação) -> puxa para Arm/Fin
    if (m.g90 >= 0.20 && m.a90 >= 0.08 && m.p90 >= 0.25) {
      if (p.a == 4 && p.b == 9)  b += 0.16; // Arm/Fin
      if (p.a == 8 && p.b == 11) b -= 0.10; // evita Dri/Pas quando também decide
    }
return b;
  }

  
  private static double boostWing(Pair p, Metrics m) {
    double b = 0.0;

    // Winger decisivo (g+a alto por 90, jogo "de último terço")
    // Prioriza Fin/Dri e derruba combinações muito "correria pura" (Dri/Vel, Vel/Dri).
    if (m.p90 >= 0.38 && (m.g90 >= 0.16 || m.a90 >= 0.16)) {
      if (p.a == 9 && p.b == 8) b += 0.20; // Fin/Dri
      if (p.a == 8 && p.b == 9) b += 0.12; // Dri/Fin
      if (p.a == 13 && p.b == 8) b -= 0.10; // Vel/Dri
      if (p.a == 8 && p.b == 13) b -= 0.12; // Dri/Vel
      if (p.a == 13 && p.b == 9) b -= 0.06; // Vel/Fin
      if (p.a == 9 && p.b == 13) b -= 0.04; // Fin/Vel (evita excesso aqui)
    
      // Se o ponta é MUITO produtivo (g90 + a90 altos), permita que Fin/Vel e Vel/Fin compitam.
      // (casos tipo Luiz Araújo / Bruno Henrique)
      if (m.p90 >= 0.36 && m.g90 >= 0.22 && m.a90 >= 0.14) {
        if (p.a == 9 && p.b == 13) b += 0.16; // Fin/Vel
        if (p.a == 13 && p.b == 9) b += 0.12; // Vel/Fin
        // reduz um pouco o viés automático para Fin/Dri nesses casos
        if (p.a == 9 && p.b == 8) b -= 0.08;
        if (p.a == 8 && p.b == 9) b -= 0.04;
      }
    } else if (m.p90 >= 0.30 && m.g90 >= 0.18) {
    // Winger com muita participacao em gol (tende a Fin/Dri mais do que Dri/Fin)
    if (m.p90 >= 0.38 && m.a90 >= 0.20 && m.g90 >= 0.14) {
      if (p.a == 9 && p.b == 8) b += 0.10; // Fin/Dri
      if (p.a == 8 && p.b == 9) b -= 0.06; // Dri/Fin
    }

      if (p.a == 9 && p.b == 8) b += 0.06;
      if (p.a == 8 && p.b == 9) b += 0.04;
    }

    
    // Winger veterano muito goleador (caso típico: Bruno Henrique):
    // se marca muito (g90 alto), Vel/Fin ou Fin/Vel tendem a representar melhor do que Fin/Dri.
    if (m.g90 >= 0.30) {
      if ((p.a == 13 && p.b == 9) || (p.a == 9 && p.b == 13)) b += 0.14; // Vel/Fin, Fin/Vel
      if (p.a == 9 && p.b == 8) b -= 0.06; // Fin/Dri
      if (p.a == 8 && p.b == 9) b -= 0.05; // Dri/Fin
    }
// Ponta jovem e finalizador (mais pace/fin do que drible)
    if (m.age <= 21 && m.g90 >= 0.30 && m.a90 < 0.12) {
      if (p.a == 9 && p.b == 13) b += 0.25; // Fin/Vel
      if (p.a == 13 && p.b == 9) b += 0.18; // Vel/Fin
      if (p.a == 9 && p.b == 8) b -= 0.20; // Fin/Dri
      if (p.a == 8 && p.b == 9) b -= 0.15; // Dri/Fin
    }

    return b;
  }

  private static double boostST(Pair p, Metrics m) {
    double b = 0.0;

    // 9 classico
    if (m.height >= 1.88 && m.g90 >= 0.22) {
      if (p.a == 9 && p.b == 5) b += 0.06; // Fin/Cab
      if (p.a == 5 && p.b == 9) b += 0.03; // Cab/Fin
    }

    // Matador absurdo
    if (m.height >= 1.80 && m.g90 >= 0.42 && m.mpg <= 210.0) {
      if (p.a == 9 && p.b == 5) b += 0.08;
    }

    // Cab/Vel (alto e movel)
    if (m.height >= 1.90 && m.rotation >= 0.45) {
      if (p.a == 5 && p.b == 13) b += 0.04;
    }


    // Cab/Vel (jovem finalizador movel) — prioriza Cab/Vel, mas NAO deve matar Fin/Dri quando o CA também cria/joga (a90 alto)
    if (m.age <= 20 && m.rotation >= 0.33 && m.g90 >= 0.28) {
      if (p.a == 5 && p.b == 13) b += 0.14; // Cab/Vel
      if (p.a == 9 && p.b == 13) b += 0.06; // Fin/Vel

      // Se o atacante jovem também dá assistências, Fin/Dri fica mais plausível (caso Vitor Roque)
      if (m.a90 >= 0.12 && m.g90 >= 0.35) {
        if (p.a == 9 && p.b == 8) b += 0.12; // Fin/Dri
        if (p.a == 8 && p.b == 9) b += 0.08; // Dri/Fin
      } else {
        if (p.a == 9 && p.b == 8) b -= 0.08; // Fin/Dri
        if (p.a == 8 && p.b == 9) b -= 0.05; // Dri/Fin
      }
    }
    return b;
  }

  // -------------------------
  // Desempate por posicao secundaria (somente em empate tecnico)
  // -------------------------
  private static Pair breakTieBySecondary(Pair best, Pair second, Metrics m, Domain dom, long seed) {
    if (m.secondary == null || m.secondary.isEmpty()) return null;

    // Heuristica: favorece um par que "converge" com a secundaria (sem criar dominio novo).
    // Apenas em empate tecnico; se nao casar, retorna null.
    boolean secWinger = m.hasSecondaryLike("Ponta");
    boolean secStriker = m.hasSecondaryLike("Centroav") || m.hasSecondaryLike("Atac") || m.hasSecondaryLike("Seg");
    boolean secCB = m.hasSecondaryLike("Zagueiro");
    boolean secMid = m.hasSecondaryLike("Meia") || m.hasSecondaryLike("Volante");

    int bias = 0;
    if (secWinger) bias = 13; // tende a Vel/Dri ou Cru/Vel etc
    else if (secStriker) bias = 9; // tende a Fin/*
    else if (secCB) bias = 10; // tende a Mar/*
    else if (secMid) bias = 11; // tende a Pas/*

    if (bias == 0) return null;

    boolean bestHas = (best.a == bias || best.b == bias);
    boolean secHas = (second.a == bias || second.b == bias);

    if (bestHas == secHas) return null;

    // Se o segundo casar e o best nao casar, e estamos em empate tecnico, troca.
    if (secHas) return second;
    return null;
  }

  // -------------------------
  // Metrics
  // -------------------------
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

    // derivadas
    final double g90;
    final double a90;
    final double p90;
    final double c90;
    final double playRate;
    final double rotation;

    private Metrics(
        int pos,
        String posText,
        List<String> secondary,
        int related,
        int played,
        int goals,
        int assists,
        int ownGoals,
        int fromBench,
        int substituted,
        int yellow,
        int yellowRed,
        int red,
        int penaltyGoals,
        double mpg,
        int mp,
        int gc,
        int cs,
        Integer age,
        double height,
        double g90,
        double a90,
        double p90,
        double c90,
        double playRate,
        double rotation) {

      this.pos = pos;
      this.posText = posText == null ? "" : posText;
      this.secondary = secondary == null ? List.of() : secondary;

      this.related = Math.max(0, related);
      this.played = Math.max(0, played);
      this.goals = Math.max(0, goals);
      this.assists = Math.max(0, assists);
      this.ownGoals = Math.max(0, ownGoals);

      this.fromBench = Math.max(0, fromBench);
      this.substituted = Math.max(0, substituted);

      this.yellow = Math.max(0, yellow);
      this.yellowRed = Math.max(0, yellowRed);
      this.red = Math.max(0, red);

      this.penaltyGoals = Math.max(0, penaltyGoals);

      this.mpg = mpg;
      this.mp = Math.max(0, mp);

      this.gc = Math.max(0, gc);
      this.cs = Math.max(0, cs);

      this.age = age;
      this.height = height > 0 ? height : 1.80;

      this.g90 = g90;
      this.a90 = a90;
      this.p90 = p90;
      this.c90 = c90;
      this.playRate = playRate;
      this.rotation = rotation;
    }

    static Metrics from(
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
          pos,
          posText,
          secondaryPositions == null ? List.of() : secondaryPositions,
          related,
          played,
          goals,
          assists,
          ownGoals,
          fromBench,
          substituted,
          yellow,
          yellowRed,
          red,
          penaltyGoals,
          mpg,
          mp,
          goalsConceded,
          cleanSheets,
          idade,
          heightM,
          g90,
          a90,
          p90,
          c90,
          playRate,
          rotation);
    }

    boolean hasSecondaryLike(String token) {
      if (token == null || token.isBlank()) return false;
      final String t = token.toLowerCase(Locale.ROOT);
      for (String s : secondary) {
        if (s == null) continue;
        if (s.toLowerCase(Locale.ROOT).contains(t)) return true;
      }
      return false;
    }

    boolean isMostlyBench() {
      return played > 0 && ((fromBench / (double) played) >= 0.28);
    }

    boolean isCenterBackLike() {
      return pos == 2 || posText.toLowerCase(Locale.ROOT).contains("zague");
    }

    boolean isFullBackLike() {
      return pos == 1 || posText.toLowerCase(Locale.ROOT).contains("lateral");
    }

    boolean isDefMidLike() {
      final String p = posText.toLowerCase(Locale.ROOT);
      return pos == 3 && (p.contains("volante"));
    }

    boolean isAttMidLike() {
      final String p = posText.toLowerCase(Locale.ROOT);
      // meia ofensivo / meia atacante / segundo atacante (quando pos==3)
      return pos == 3 && (p.contains("ofens") || p.contains("atacante") || p.contains("meia-atac") || p.contains("meia atac"));
    }

    boolean isMidLike() {
      final String p = posText.toLowerCase(Locale.ROOT);
      return pos == 3 && (p.contains("meia") || p.contains("armador") || p.contains("central"));
    }

    boolean isWingerLike() {
      final String p = posText.toLowerCase(Locale.ROOT);
      return pos == 4 && (p.contains("ponta"));
    }

    boolean isDropStrikerLike() {
      final String p = posText.toLowerCase(Locale.ROOT);
      return pos == 4 && (p.contains("recu") || p.contains("seg") || p.contains("seg.") || p.contains("segundo"));
    }

    boolean isStrikerLike() {
      final String p = posText.toLowerCase(Locale.ROOT);
      return pos == 4 && (p.contains("centroav") || p.contains("9") || (p.contains("atac") && !p.contains("recu") && !p.contains("seg")));
    }

    boolean isAttackerLike() {
      return pos == 4;
    }

    boolean isEnduranceMotor() {
      // Regra global v3.5.x
      return mp >= 3200 && playRate >= 0.85 && rotation <= 0.25;
    }

    long playerKeySeed() {
      // seed deterministico estavel sem state externo
      long x = 1469598103934665603L;
      x = fnv1a(x, pos);
      x = fnv1a(x, played);
      x = fnv1a(x, goals);
      x = fnv1a(x, assists);
      x = fnv1a(x, mp);
      x = fnv1a(x, (int) Math.round(height * 100));
      x = fnv1a(x, (age == null ? 0 : age));
      x = fnv1a(x, (int) Math.round(rotation * 1000));
      return x;
    }

    private static long fnv1a(long h, int v) {
      long z = h;
      z ^= (v & 0xff);
      z *= 1099511628211L;
      z ^= ((v >> 8) & 0xff);
      z *= 1099511628211L;
      z ^= ((v >> 16) & 0xff);
      z *= 1099511628211L;
      z ^= ((v >> 24) & 0xff);
      z *= 1099511628211L;
      return z;
    }
  }

  // -------------------------
  // Math helpers
  // -------------------------
  private static double sat(double x, double lo, double hi) {
    if (hi <= lo) return 0.0;
    if (x <= lo) return 0.0;
    if (x >= hi) return 1.0;
    return (x - lo) / (hi - lo);
  }

  private static double clamp01(double v) {
    if (v < 0) return 0;
    if (v > 1) return 1;
    return v;
  }

  // Converte bonus para escala de score (pequeno)
  private static double bonus(double x) {
    return x; // ja passamos em escala 0..1
  }

  private static double tieNoise(long seed, int a, int b) {
    long z = seed ^ ((long) a * 0x9E3779B97F4A7C15L) ^ ((long) b * 0xC2B2AE3D27D4EB4FL);
    z ^= (z >>> 33);
    z *= 0xff51afd7ed558ccdL;
    z ^= (z >>> 33);
    z *= 0xc4ceb9fe1a85ec53L;
    z ^= (z >>> 33);
    double u = ((z >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    return (u - 0.5) * TIE_EPS;
  }
}
