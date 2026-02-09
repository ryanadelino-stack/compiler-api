package br.brasfoot.compiler;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

public final class HeuristicsEngine {

  private HeuristicsEngine() {}

  // IDs das características (0..13)
  // GK
  public static final int COL = 0;
  public static final int DPE = 1;
  public static final int REF = 2;
  public static final int SGO = 3;

  // Linha
  public static final int ARM = 4;
  public static final int CAB = 5;
  public static final int CRU = 6;
  public static final int DES = 7;
  public static final int DRI = 8;
  public static final int FIN = 9;
  public static final int MAR = 10;
  public static final int PAS = 11;
  public static final int RES = 12;
  public static final int VEL = 13;

  /**
   * REGRAS (V3):
   * - Posição primária manda (posCode / posText).
   * - Depois entram números (gols/assistências/cartões/minutos/CS/GC etc).
   * - Posição secundária apenas “puxa” arquétipo quando faz sentido (ex: lateral defensivo/ofensivo).
   *
   * Saída: {cr1, cr2} (IDs 0..13).
   */
  public static int[] pickTop2CharacteristicsByManual(
      int posCode,
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
      Integer age,
      double heightM
  ) {

    final int apps = Math.max(0, matchesPlayed);
    final int a = (age == null ? 0 : age);

    final String p = norm(posText);

    // Métricas base
    final double gJ = div(goals, apps);
    final double aJ = div(assists, apps);
    final double caJ = div(yellow, apps);
    final double vrJ = div(red + yellowRed, apps); // expulsões por jogo (vermelho + 2o amarelo)
    final double gaJ = div(goals + assists, apps);

    // Minutos úteis (quando tem)
    final double minJ = (apps <= 0 ? 0.0 : (double) Math.max(0, minutesPlayed) / (double) apps);
    final double mpg = (minutesPerGoal > 0 ? minutesPerGoal : 0.0);

    // GK stats
    final double csJ = div(cleanSheets, apps);
    final double gcJ = div(goalsConceded, apps);

    // --------------
    // 1) Determinar POSIÇÃO PRIMÁRIA “real” (prioridade máxima)
    // --------------
    final boolean primaryGk = (posCode == 0) || p.contains("gole");
    final boolean primaryLat = (posCode == 1) || p.contains("lateral");
    final boolean primaryZag = (posCode == 2) || p.contains("zague");
    final boolean primaryMei = (posCode == 3) || p.contains("meia") || p.contains("volante");
    final boolean primaryAta = (posCode == 4) || p.contains("atac") || p.contains("ponta") || p.contains("centroav");

    // --------------
    // 2) GOLEIRO (lista do TXT + desempate por stats)
    // --------------
    if (primaryGk) {
      // candidatos conforme seu TXT: Col/Ref, Ref/Col, Ref/DPe, DPe/Ref, SGo/Ref, Ref/SGo, etc.
      int[][] candidates = new int[][] {
          {REF, COL},
          {COL, REF},
          {REF, DPE},
          {DPE, REF},
          {SGO, REF},
          {REF, SGO},
          {COL, DPE},
          {DPE, COL},
          {SGO, DPE},
          {DPE, SGO},
          {SGO, COL},
          {COL, SGO},
      };

      // “puxões” fortes (determinísticos)
      // veterano + muita carreira + boa taxa de CS → Ref/DPe
      if (a >= 30 && apps > 100 && csJ > 0.30) return pair(REF, DPE);
      // muito alto + CS alto → SGo/Ref
      if (heightM >= 1.95 && csJ > 0.40) return pair(SGO, REF);

      return pickBestPairByScore(
          candidates,
          primaryGk, primaryLat, primaryZag, primaryMei, primaryAta,
          a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
          csJ, gcJ,
          secondaryPositions
      );
    }

    // --------------
    // 3) LINHA: decidir arquétipo por posição + secundária (apenas quando pertinente)
    // --------------

    // Helpers secundária
    final boolean secHasZag = hasAnySecondary(secondaryPositions, "zague", "def", "cb");
    final boolean secHasVol = hasAnySecondary(secondaryPositions, "volante");
    final boolean secHasMei = hasAnySecondary(secondaryPositions, "meia", "arm", "mid");
    final boolean secHasPonta = hasAnySecondary(secondaryPositions, "ponta", "wing", "rw", "lw");
    final boolean secHasAta = hasAnySecondary(secondaryPositions, "atac", "centroav", "st", "fw", "seg");

    // Subtipos por texto primário (quando existir)
    final boolean txtVolante = p.contains("volante");
    final boolean txtMeiaOf = p.contains("meia ofen") || p.contains("meia ofens");
    final boolean txtMeiaCentral = (p.contains("meia") && !txtMeiaOf && !txtVolante);

    final boolean txtCentroav = p.contains("centroav");
    final boolean txtPonta = p.contains("ponta") || p.contains("wing");

    // --------------------
    // LATERAL
    // --------------------
    if (primaryLat) {
      // conforme TXT:
      // - Defensivo: Mar/Cru, Cru/Mar, Mar/Fin, Mar/Vel, Vel/Mar, Des/Cru, Cru/Des, Vel/Pas, Pas/Vel
      // - Ofensivo: Cru/Vel, Vel/Cru, Cru/Pas, Vel/Pas, Cru/Fin
      boolean lateralDef = secHasZag; // critério principal do TXT
      boolean lateralOf = (secHasMei || secHasPonta || secHasAta);

      // se não houver pista clara, usa números para desempatar
      if (!lateralDef && !lateralOf) {
        lateralOf = (aJ >= 0.08) || (gaJ >= 0.18);
        lateralDef = !lateralOf;
      }

      int[][] candidates = lateralDef
          ? new int[][] {
              {MAR, CRU},
              {CRU, MAR},
              {MAR, FIN},
              {MAR, VEL},
              {VEL, MAR},
              {DES, CRU},
              {CRU, DES},
              {VEL, PAS},
              {PAS, VEL},
            }
          : new int[][] {
              {CRU, VEL},
              {VEL, CRU},
              {CRU, PAS},
              {VEL, PAS},
              {CRU, FIN},
            };

      return pickBestPairByScore(
          candidates,
          false, true, false, false, false,
          a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
          0.0, 0.0,
          secondaryPositions
      );
    }

    // --------------------
    // ZAGUEIRO
    // --------------------
    if (primaryZag) {
      // TXT:
      // - Normal: Des/Mar, Mar/Des, Des/Pas, Mar/Pas, Des/Res, Mar/Res
      // - Ofensivo: Mar/Vel, Des/Cab, Cab/Des, Mar/Cab, Cab/Mar
      boolean zagueiroOf = ((goals + assists) >= 8) || (gJ >= 0.05) || (heightM >= 1.86 && gJ >= 0.03);

      int[][] candidates = zagueiroOf
          ? new int[][] {
              {DES, CAB},
              {CAB, DES},
              {MAR, CAB},
              {CAB, MAR},
              {MAR, VEL},
            }
          : new int[][] {
              {DES, MAR},
              {MAR, DES},
              {DES, PAS},
              {MAR, PAS},
              {DES, RES},
              {MAR, RES},
            };

      return pickBestPairByScore(
          candidates,
          false, false, true, false, false,
          a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
          0.0, 0.0,
          secondaryPositions
      );
    }

    // --------------------
    // MEIA (inclui VOLANTE)
    // --------------------
    if (primaryMei) {
      // VOLANTE (TXT)
      if (txtVolante || secHasVol) {
        int[][] candidates = new int[][] {
            {DES, PAS},
            {MAR, PAS},
            {MAR, RES},
            {DES, RES},
            {DES, MAR},
            {MAR, DES},
            {MAR, FIN},
            {DES, FIN},
            {DES, VEL},
        };

        return pickBestPairByScore(
            candidates,
            false, false, false, true, false,
            a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
            0.0, 0.0,
            secondaryPositions
        );
      }

      // MEIA CENTRAL (TXT)
      if (txtMeiaCentral) {
        int[][] candidates = new int[][] {
            {PAS, VEL},
            {VEL, PAS},
            {ARM, VEL},
            {ARM, DRI},
            {DRI, PAS},
            {PAS, DRI},
            {DES, VEL},
            {ARM, PAS},
        };

        return pickBestPairByScore(
            candidates,
            false, false, false, true, false,
            a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
            0.0, 0.0,
            secondaryPositions
        );
      }

      // MEIA OFENSIVO (TXT) – default para meias não-volantes
      // Fin/Pas, Arm/Fin, Arm/Pas, Fin/Arm, Dri/Pas, Dri/Fin, Fin/Dri
      int[][] candidates = new int[][] {
          {FIN, PAS},
          {ARM, FIN},
          {ARM, PAS},
          {FIN, ARM},
          {DRI, PAS},
          {DRI, FIN},
          {FIN, DRI},
      };

      return pickBestPairByScore(
          candidates,
          false, false, false, true, false,
          a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
          0.0, 0.0,
          secondaryPositions
      );
    }

    // --------------------
    // ATACANTE
    // --------------------
    if (primaryAta) {
      // subtipos do TXT:
      // - Recuado: apropriado se secundária tem Seg. Atacante
      // - Pelo Meio: Centroavante principal
      // - Pela Ponta: Ponta principal
      boolean secSegAtac = hasAnySecondary(secondaryPositions, "seg", "second striker", "ss");

      if (secSegAtac) {
        int[][] candidates = new int[][] {
            {DRI, PAS},
            {DRI, FIN},
            {PAS, FIN},
        };
        return pickBestPairByScore(
            candidates,
            false, false, false, false, true,
            a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
            0.0, 0.0,
            secondaryPositions
        );
      }

      if (txtCentroav) {
        int[][] candidates = new int[][] {
            {FIN, PAS},
            {FIN, CAB},
            {CAB, FIN},
            {FIN, DRI},
            {FIN, RES},
            {CAB, VEL},
        };
        return pickBestPairByScore(
            candidates,
            false, false, false, false, true,
            a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
            0.0, 0.0,
            secondaryPositions
        );
      }

      if (txtPonta || secHasPonta) {
        int[][] candidates = new int[][] {
            {VEL, FIN},
            {FIN, VEL},
            {VEL, DRI},
            {FIN, DRI},
            {DRI, FIN},
        };
        return pickBestPairByScore(
            candidates,
            false, false, false, false, true,
            a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
            0.0, 0.0,
            secondaryPositions
        );
      }

      // Atacante “genérico” (se não veio centroav/ponta no texto)
      int[][] candidates = new int[][] {
          {FIN, PAS},
          {FIN, DRI},
          {VEL, FIN},
          {DRI, PAS},
          {FIN, RES},
      };
      return pickBestPairByScore(
          candidates,
          false, false, false, false, true,
          a, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg,
          0.0, 0.0,
          secondaryPositions
      );
    }

    // fallback ultra defensivo/seguro (não deveria ocorrer se posCode vier correto)
    return pair(DES, PAS);
  }

  // ============================================================
  // Core: escolhe o melhor par dentro do “cardápio” do arquétipo
  // usando “intensidade”/score por atributo (para reduzir clones).
  // ============================================================
  private static int[] pickBestPairByScore(
      int[][] candidates,
      boolean primaryGk,
      boolean primaryLat,
      boolean primaryZag,
      boolean primaryMei,
      boolean primaryAta,
      int age,
      double heightM,
      int apps,
      double gJ,
      double aJ,
      double caJ,
      double vrJ,
      double gaJ,
      double minJ,
      double mpg,
      double csJ,
      double gcJ,
      ArrayList<String> secondaryPositions
  ) {
    if (candidates == null || candidates.length == 0) return pair(DES, PAS);

    double best = -1e18;
    int[] bestPair = candidates[0];

    for (int[] pr : candidates) {
      if (pr == null || pr.length < 2) continue;

      int c1 = pr[0];
      int c2 = pr[1];
      if (c1 == c2) continue;

      double s1 = traitScore(
          c1, primaryGk, primaryLat, primaryZag, primaryMei, primaryAta,
          age, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg, csJ, gcJ, secondaryPositions
      );
      double s2 = traitScore(
          c2, primaryGk, primaryLat, primaryZag, primaryMei, primaryAta,
          age, heightM, apps, gJ, aJ, caJ, vrJ, gaJ, minJ, mpg, csJ, gcJ, secondaryPositions
      );

      // Intensidade: 1º atributo pesa mais do que o 2º (regra que você pediu)
      double score = (1.00 * s1) + (0.78 * s2);

      // bônus de sinergia leve (refina o desempate)
      score += synergyBonus(c1, c2, primaryLat, primaryZag, primaryMei, primaryAta, gJ, aJ, caJ, gaJ, heightM);

      // penaliza combinações “genéricas” quando as stats indicam outra coisa
      score += specificityBonus(c1, c2, primaryLat, primaryZag, primaryMei, primaryAta, gJ, aJ, caJ, gaJ, age, heightM);

      if (score > best) {
        best = score;
        bestPair = pr;
      }
    }

    return pair(bestPair[0], bestPair[1]);
  }

  // ============================================================
  // Score por característica (determinístico) usando só seus campos do JSON.
  // A ideia é: atributos “puxam” a partir de indicadores compatíveis com a função.
  // ============================================================
  private static double traitScore(
      int trait,
      boolean primaryGk,
      boolean primaryLat,
      boolean primaryZag,
      boolean primaryMei,
      boolean primaryAta,
      int age,
      double heightM,
      int apps,
      double gJ,
      double aJ,
      double caJ,
      double vrJ,
      double gaJ,
      double minJ,
      double mpg,
      double csJ,
      double gcJ,
      ArrayList<String> secondaryPositions
  ) {

    // fator de amostragem: se quase não jogou, reduz “certeza” (sem zerar)
    double sample = clamp01(apps / 15.0);           // 0..1
    double sample2 = clamp01(apps / 35.0);          // mais exigente
    double youth = (age > 0 ? clamp01((28.0 - age) / 10.0) : 0.0); // jovem → +1

    switch (trait) {
      // GK
      case REF:
        // Reflexo: CS alto e GC baixo (quando há jogos)
        return (2.6 * csJ) + (1.6 * (1.6 - gcJ)) + (0.25 * sample2);
      case COL:
        // Colocação: estabilidade/regularidade + perfil menos “explosivo”
        return (2.0 * csJ) + (1.1 * (1.4 - gcJ)) + (0.35 * clamp01(minJ / 90.0));
      case DPE:
        // Penal: aqui não temos “defesas de pênalti”; então usamos proxy “experiência/controle”
        return (0.55 * clamp01(apps / 120.0)) + (0.35 * clamp01(age / 35.0)) + (0.45 * csJ);
      case SGO:
        // Saída do gol: proxy por altura + consistência (evita sempre cair em REF/COL)
        return (0.95 * clamp01((heightM - 1.78) / 0.22)) + (0.65 * csJ) + (0.25 * sample);

      // Linha
      case FIN:
        // Finalização: gols/jogo e “eficiência” (mpg menor é melhor)
        double eff = (mpg > 0 ? clamp01((240.0 - mpg) / 240.0) : 0.0);
        return (3.2 * gJ) + (0.9 * eff) + (0.35 * gaJ) + (0.20 * sample);
      case PAS:
        // Passe: assistências/jogo e participação em gols
        return (2.9 * aJ) + (0.8 * gaJ) + (0.25 * clamp01(minJ / 90.0)) + (0.20 * sample);
      case ARM:
        // Armação: mistura assistência + participação (mais “criativo” que PAS puro)
        return (2.2 * aJ) + (1.0 * gaJ) + (0.30 * youth) + (0.15 * sample);
      case DRI:
        // Drible: proxy por participação ofensiva + juventude (sem métrica de dribles no JSON)
        return (1.35 * gaJ) + (0.85 * youth) + (0.35 * aJ) + (0.15 * sample);
      case VEL:
        // Velocidade: juventude + perfil de ponta/lateral por secundária
        double secWing = hasAnySecondary(secondaryPositions, "ponta", "wing", "rw", "lw") ? 0.35 : 0.0;
        return (1.45 * youth) + (0.55 * secWing) + (0.25 * gaJ) + (0.10 * sample);
      case CRU:
        // Cruzamento: proxy por assistências e perfil de lateral/ponta
        double secLat = hasAnySecondary(secondaryPositions, "lateral", "ala") ? 0.30 : 0.0;
        return (2.4 * aJ) + (0.45 * secLat) + (0.25 * youth) + (0.10 * sample);
      case CAB:
        // Cabeceio: proxy por altura + gols (especialmente útil pra zagueiro/centroavante)
        return (1.25 * clamp01((heightM - 1.74) / 0.26)) + (1.25 * gJ) + (0.20 * sample);
      case DES:
        // Desarme: proxy por “perfil combativo” (cartões/jogo) + função defensiva
        double defBias = (primaryZag ? 0.55 : primaryLat ? 0.35 : primaryMei ? 0.25 : 0.0);
        return (1.8 * caJ) + (0.9 * vrJ) + defBias + (0.20 * sample);
      case MAR:
        // Marcação: mais “controle” do que DES (menos expulsão, mais amarelo)
        double control = (caJ > 0 ? 1.0 / (1.0 + (2.0 * vrJ)) : 1.0);
        double marBase = (1.55 * caJ) + (0.45 * control);
        double marBias = (primaryZag ? 0.55 : primaryLat ? 0.40 : primaryMei ? 0.25 : 0.0);
        return marBase + marBias + (0.15 * sample);
      case RES:
        // Resistência: minutos/jogo altos + tendência a ser menos substituído (proxy fraco)
        double stamina = clamp01(minJ / 90.0);
        double mature = (age > 0 ? clamp01((age - 24.0) / 10.0) : 0.0);
        return (1.9 * stamina) + (0.55 * mature) + (0.20 * sample2);
      default:
        return 0.0;
    }
  }

  private static double synergyBonus(
      int c1,
      int c2,
      boolean primaryLat,
      boolean primaryZag,
      boolean primaryMei,
      boolean primaryAta,
      double gJ,
      double aJ,
      double caJ,
      double gaJ,
      double heightM
  ) {
    // bônus bem leve só pra “ajustar” combinações que fazem sentido
    if ((c1 == FIN && c2 == CAB) || (c1 == CAB && c2 == FIN)) {
      return (heightM >= 1.82 ? 0.22 : 0.05) + (gJ >= 0.18 ? 0.18 : 0.0);
    }
    if ((c1 == CRU && c2 == VEL) || (c1 == VEL && c2 == CRU)) {
      return (aJ >= 0.10 ? 0.20 : 0.05) + (primaryLat ? 0.10 : 0.0);
    }
    if ((c1 == DES && c2 == MAR) || (c1 == MAR && c2 == DES)) {
      return (primaryZag || primaryMei ? 0.18 : 0.08) + (caJ >= 0.18 ? 0.10 : 0.0);
    }
    if ((c1 == FIN && c2 == PAS) || (c1 == PAS && c2 == FIN)) {
      return (gaJ >= 0.22 ? 0.18 : 0.05) + (primaryAta ? 0.08 : 0.0);
    }
    if ((c1 == ARM && c2 == FIN) || (c1 == FIN && c2 == ARM)) {
      return (primaryMei && gJ >= 0.12 ? 0.18 : 0.06);
    }
    return 0.0;
  }

  private static double specificityBonus(
      int c1,
      int c2,
      boolean primaryLat,
      boolean primaryZag,
      boolean primaryMei,
      boolean primaryAta,
      double gJ,
      double aJ,
      double caJ,
      double gaJ,
      int age,
      double heightM
  ) {
    double bonus = 0.0;

    // Evita “todo mundo” virar PAS/VEL quando não há sinal estatístico
    if ((c1 == PAS || c2 == PAS) && aJ < 0.06) bonus -= 0.15;
    if ((c1 == VEL || c2 == VEL) && age > 29) bonus -= 0.10;

    // Atacante com muito gol: favorece FIN + algo (tirar combinações defensivas)
    if (primaryAta && gJ >= 0.20) {
      if (c1 == DES || c1 == MAR || c2 == DES || c2 == MAR) bonus -= 0.18;
    }

    // Zagueiro “ofensivo” (gols/altura): favorece CAB
    if (primaryZag && (heightM >= 1.86 && gJ >= 0.03)) {
      if (c1 == CAB || c2 == CAB) bonus += 0.12;
    }

    // Lateral com muita assistência: favorece CRU e PAS
    if (primaryLat && aJ >= 0.12) {
      if (c1 == CRU || c2 == CRU) bonus += 0.10;
      if (c1 == PAS || c2 == PAS) bonus += 0.06;
    }

    // Meia com muita participação: favorece ARM/FIN
    if (primaryMei && gaJ >= 0.22) {
      if (c1 == ARM || c2 == ARM) bonus += 0.08;
      if (c1 == FIN || c2 == FIN) bonus += 0.06;
    }

    return bonus;
  }

  // -----------------
  // Helpers
  // -----------------
  private static double div(int num, int den) {
    if (den <= 0) return 0.0;
    return (double) num / (double) den;
  }

  private static int[] pair(int a, int b) {
    return new int[] { a, b };
  }

  private static String norm(String s) {
    if (s == null) return "";
    return deaccent(s).toLowerCase(Locale.ROOT);
  }

  private static boolean hasAnySecondary(ArrayList<String> sec, String... needles) {
    if (sec == null || sec.isEmpty() || needles == null || needles.length == 0) return false;
    for (String s : sec) {
      String t = norm(s);
      for (String n : needles) {
        if (n == null || n.isBlank()) continue;
        if (t.contains(n.toLowerCase(Locale.ROOT))) return true;
      }
    }
    return false;
  }

  private static String deaccent(String s) {
    if (s == null) return "";
    String n = Normalizer.normalize(s, Normalizer.Form.NFD);
    return n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
  }

  private static double clamp01(double x) {
    if (x < 0) return 0;
    if (x > 1) return 1;
    return x;
  }
}
