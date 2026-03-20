package br.brasfoot.compiler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Color;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

public final class BanCompiler {

  private static final Gson GSON = new Gson();

  // Liga logs se executar: java -Dbrasfoot.debug=true -jar ...
  private static final boolean DEBUG =
      Boolean.parseBoolean(System.getProperty("brasfoot.debug", "false"));

  private BanCompiler() {}

  public static void compileTeamJsonToBan(
      Path inputJson,
      Path templateBan,
      Path outBan,
      Integer teamIdOverride,
      Integer countryIdOverride,
      boolean competitive          // modo competitivo: limite 25 jogadores, sem titulares
  ) throws IOException {

    JsonElement rootEl = readJsonRoot(inputJson);

    // 1) Carrega ou cria time
    e.t team = loadOrCreateTeam(templateBan);

    // 2) Aplica time (schema antigo apenas). No schema novo (array), mantém nome do template.
    JsonObject rootObj = rootEl.isJsonObject() ? rootEl.getAsJsonObject() : null;
    applyTeamFromJson(team, rootObj, teamIdOverride, countryIdOverride);

    // 3) Resolve lista de jogadores
    JsonArray players = null;
    if (rootEl.isJsonArray()) {
      players = rootEl.getAsJsonArray();
    } else if (rootObj != null) {
      players = JsonUtil.getArray(rootObj, "roster", "players");
    }

    // Listas separadas: seniors → l (jogadores), juniors → m (juniores)
    ArrayList<Object> jogadores  = new ArrayList<>();
    ArrayList<Object> juniores   = new ArrayList<>();

    // Listas paralelas para cálculo de titulares (apenas seniors)
    ArrayList<int[]> minutesByIndex = new ArrayList<>();

    // Lista paralela para ordenar juniores com desempate em cascata e limitar a MAX_JUNIORES.
    // Cada entrada: [índice em juniores, minutesPlayed, matchesPlayed, matchesRelated]
    ArrayList<int[]> juniorStats = new ArrayList<>();
    final int MAX_JUNIORES = 20;
    final int TITULARES_JUNIORES = 7;

    // ── Resolução adiada de lado ──────────────────────────────────────────────
    // Jogadores com lado ambíguo têm o side definido APÓS processar todo o elenco.
    // allBuiltPlayers: todos os jogadores construídos (seniors + juniores), na ordem.
    // deferredSideEntries: [índice em allBuiltPlayers, tipo: 1=ambidestro, 2=sem-pé]
    ArrayList<Object> allBuiltPlayers     = new ArrayList<>();
    ArrayList<int[]>  deferredSideEntries = new ArrayList<>();

    // Primeiro passo: verifica se ALGUM jogador do elenco tem minutesPlayedSeason.
    // Se sim, usamos minutesPlayedSeason para todos (com fallback 0 para quem não tem).
    // Se nenhum tem, usamos minutesPlayed (carreira) como critério de titulares.
    boolean anyHasSeasonMins = false;
    if (players != null) {
      for (JsonElement el : players) {
        if (!el.isJsonObject()) continue;
        JsonObject pj = el.getAsJsonObject();
        StatsReader.Stats st = StatsReader.read(pj);
        if (st.minutesPlayedSeason >= 0) { anyHasSeasonMins = true; break; }
      }
    }

    if (DEBUG) System.out.println("[DEBUG] anyHasSeasonMins=" + anyHasSeasonMins);

    // 4) Constrói jogadores
    if (players != null) {
      for (JsonElement el : players) {
        if (!el.isJsonObject()) continue;
        JsonObject pj = el.getAsJsonObject();

        boolean isJunior = "junior".equalsIgnoreCase(JsonUtil.getString(pj, "category"));

        e.g p = buildPlayerFromJson(pj, team, countryIdOverride);

        // Registra para resolução adiada de lado
        int allIdx = allBuiltPlayers.size();
        allBuiltPlayers.add(p);

        String footCheck    = JsonUtil.getString(pj, "foot");
        String posTextCheck = PositionUtil.readPosInfo(pj).bestText();

        // ── Decisão de deferral ──────────────────────────────────────────────
        // Critério: pé ambíguo (ambidestro/null) E posição não implica lado.
        // Posições que implicam lado (Lateral Esq, Ponta Dir, etc.) ficam fixas.
        boolean shouldDefer = shouldDeferSide(footCheck, posTextCheck);

        if (shouldDefer) {
          int deferType = ("ambidestro".equalsIgnoreCase(footCheck)) ? 1 : 2;
          deferredSideEntries.add(new int[]{allIdx, deferType});
          if (DEBUG) {
            Object nomeP = getAnyField(p, "a");
            System.out.println("[DEBUG] lado-adiado: " + nomeP
                + " foot=" + footCheck
                + " posText=" + posTextCheck
                + " tipo=" + (deferType == 1 ? "ambidestro" : "sem-pe"));
          }
        } else if (!positionImpliesSide(posTextCheck) && isExplicitFoot(footCheck)) {
          // ── Pé explícito + posição neutra: mapeia diretamente, ignora SideResolver ─
          // O SideResolver pode aplicar lógica de "winger" (pé direito → lado esquerdo
          // para cortar para dentro), o que é INCORRETO para posições neutras como
          // Centroavante, Zagueiro, Meia Central, etc.
          // Regra direta: direito → 0 (Direito), esquerdo → 1 (Esquerdo).
          int sideFromFoot = resolveExplicitFootSide(footCheck);
          setAnyField(p, sideFromFoot, "i", "lado");
          if (DEBUG) {
            Object nomeP = getAnyField(p, "a");
            System.out.println("[DEBUG] lado-direto-do-pe: " + nomeP
                + " foot=" + footCheck
                + " posText=" + posTextCheck
                + " lado=" + (sideFromFoot == 1 ? "Esquerdo(1)" : "Direito(0)"));
          }
        }

        if (isJunior) {
          // Juniores não participam do cálculo de titulares seniors.
          // Usamos minutesPlayed (carreira) como critério primário; desempate por
          // matchesPlayed e depois matchesRelated.
          StatsReader.Stats stJ = StatsReader.read(pj);
          juniorStats.add(new int[]{
              juniores.size(),
              stJ.minutesPlayed,
              stJ.matchesPlayed,
              stJ.matchesRelated
          });
          juniores.add(p);
          if (DEBUG) {
            Object nomeJ = getAnyField(p, "a");
            System.out.println("[DEBUG] junior: " + nomeJ
                + " mins=" + stJ.minutesPlayed
                + " apps=" + stJ.matchesPlayed
                + " rel=" + stJ.matchesRelated);
          }
        } else {
          // Senior: registra minutos para seleção de titulares
          StatsReader.Stats st = StatsReader.read(pj);
          int minsParaOrdem;
          if (anyHasSeasonMins) {
            minsParaOrdem = (st.minutesPlayedSeason >= 0) ? st.minutesPlayedSeason : 0;
          } else {
            minsParaOrdem = st.minutesPlayed;
          }
          // [0]=índice no ArrayList jogadores, [1]=minsParaOrdem (season ou carreira), [2]=minutesPlayed carreira
          minutesByIndex.add(new int[]{jogadores.size(), minsParaOrdem, st.minutesPlayed});
          jogadores.add(p);
        }
      }
    }

    // ── Resolução adiada de lado (pós-loop) ───────────────────────────────────
    // Conta quantos jogadores com lado DEFINITIVO estão no elenco (senior + junior).
    // Brasfoot: 0=Direito, 1=Esquerdo.
    int countRight = 0;
    int countLeft  = 0;
    for (Object p : allBuiltPlayers) {
      Object ladoObj = getAnyField(p, "i");
      int lado = (ladoObj instanceof Number) ? ((Number) ladoObj).intValue() : 0;
      if (lado == 0) countRight++;
      else           countLeft++;
    }

    // Remove contagens dos jogadores com lado adiado (ainda têm o default 0=Direito)
    for (int[] entry : deferredSideEntries) {
      countRight--; // cada adiado contribuiu com 0 (Direito) ao default
    }
    if (countRight < 0) countRight = 0;

    if (DEBUG) {
      System.out.println("[DEBUG] lado-adiado: total=" + deferredSideEntries.size()
          + " definitivos: D=" + countRight + " E=" + countLeft);
    }

    for (int[] entry : deferredSideEntries) {
      int allIdx   = entry[0];
      int deferType = entry[1]; // 1=ambidestro, 2=sem-pé

      Object p = allBuiltPlayers.get(allIdx);
      int ladoFinal;

      if (deferType == 1) {
        // Ambidestro: atribui ao lado com MENOS jogadores (para equilibrar)
        ladoFinal = (countLeft <= countRight) ? 1 : 0;
      } else {
        // Sem pé: sorteio 50/50
        ladoFinal = (Math.random() < 0.5) ? 0 : 1;
      }

      setAnyField(p, ladoFinal, "i", "lado");
      if (ladoFinal == 0) countRight++;
      else               countLeft++;

      if (DEBUG) {
        Object nomeP = getAnyField(p, "a");
        System.out.println("[DEBUG] lado-resolvido: " + nomeP
            + " tipo=" + (deferType == 1 ? "ambidestro" : "sem-pe")
            + " -> " + (ladoFinal == 1 ? "Esquerdo(1)" : "Direito(0)")
            + " [D=" + countRight + " E=" + countLeft + "]");
      }
    }

    // 5) Titulares seniors (top-15 por minutagem, exceto modo competitivo)
    if (!competitive && !jogadores.isEmpty()) {
      final int TITULARES = Math.min(15, jogadores.size());

      // Ordena por minutos (desc), desempate por minutesPlayed (carreira)
      ArrayList<int[]> sorted = new ArrayList<>(minutesByIndex);
      sorted.sort(Comparator
          .comparingInt((int[] e) -> e[1]).reversed()
          .thenComparingInt((int[] e) -> e[2]).reversed()
      );

      if (DEBUG) System.out.println("[DEBUG] Titulares seniors (top " + TITULARES + "):");
      for (int rank = 0; rank < sorted.size(); rank++) {
        int[] entry = sorted.get(rank);
        Object pSenior = jogadores.get(entry[0]);
        Object nomeJ = getAnyField(pSenior, "a");

        if (rank < TITULARES) {
          setAnyField(pSenior, 1, "f"); // f=1 = boneco verde (titular)
          if (DEBUG) System.out.println("[DEBUG]  " + (rank + 1) + ". " + nomeJ
              + " mins=" + entry[1]
              + " apps=" + entry[2]);
        } else {
          setAnyField(pSenior, 0, "f");
          if (DEBUG) System.out.println("[DEBUG]  (reserva) " + nomeJ
              + " mins=" + entry[1]
              + " apps=" + entry[2]);
        }
      }
    } else if (competitive) {
      // Modo competitivo: nenhum titular, limita a 25 jogadores
      for (Object p : jogadores) setAnyField(p, 0, "f");
      while (jogadores.size() > 25) jogadores.remove(jogadores.size() - 1);
      if (DEBUG) System.out.println("[DEBUG] competitive: jogadores limitados a " + jogadores.size());
    }

    setAnyField(team, jogadores, "l", "jogadores");

    // 5b) Juniores — seleciona top MAX_JUNIORES por minutagem (com desempate)
    if (!juniores.isEmpty()) {
      ArrayList<int[]> junSorted = new ArrayList<>(juniorStats);
      junSorted.sort(Comparator
          .comparingInt((int[] e) -> e[1]).reversed()
          .thenComparingInt((int[] e) -> e[2]).reversed()
          .thenComparingInt((int[] e) -> e[3]).reversed()
      );

      ArrayList<Object> junioresFinal = new ArrayList<>();
      int rank = 0;
      for (int[] entry : junSorted) {
        if (junioresFinal.size() >= MAX_JUNIORES) break;
        junioresFinal.add(juniores.get(entry[0]));
        if (DEBUG) {
          Object nomeJ = getAnyField(juniores.get(entry[0]), "a");
          System.out.println("[DEBUG] junior rank " + (rank + 1) + ": " + nomeJ
              + " mins=" + entry[1]
              + " apps=" + entry[2]
              + " rel=" + entry[3]);
        }
        rank++;
      }
      if (DEBUG) System.out.println("[DEBUG] juniores selecionados: " + junioresFinal.size() + "/" + juniores.size());

      // ── Marca os top-TITULARES_JUNIORES como titulares (f=1) ─────────────
      int markedJun = 0;
      if (DEBUG) System.out.println("[DEBUG] Titulares juniores (top " + TITULARES_JUNIORES + "):");
      for (Object pJun : junioresFinal) {
        if (markedJun >= TITULARES_JUNIORES) break;
        Object nomeJ = getAnyField(pJun, "a");
        setAnyField(pJun, 1, "f"); // f=1 = boneco verde (titular)
        markedJun++;
        if (DEBUG) System.out.println("[DEBUG]  " + markedJun + ". " + nomeJ);
      }
      if (DEBUG) System.out.println("[DEBUG] Total titulares juniores: " + markedJun + "/" + TITULARES_JUNIORES);

      setAnyField(team, junioresFinal, "m", "juniores");
    } else {
      // Sem juniores no JSON: preserva lista do template (ou garante ArrayList vazio)
      Object junExistente = getAnyField(team, "m", "juniores");
      if (junExistente == null) {
        setAnyField(team, new ArrayList<>(), "m", "juniores");
      }
      if (DEBUG) System.out.println("[DEBUG] juniores: nenhum no JSON, preservando template.");
    }

    // 6) Salva
    ensureParentDir(outBan);
    writeSerialized(outBan, team);

    if (DEBUG) {
      System.out.println("[DEBUG] wrote outBan=" + outBan);
      System.out.println("[DEBUG] jogadores(senior).size=" + jogadores.size());
      System.out.println("[DEBUG] juniores no .ban=" + Math.min(MAX_JUNIORES, juniores.size()) + "/" + juniores.size() + " (limite=" + MAX_JUNIORES + ")");
    }
  }

  // -------------------------
  // Team
  // -------------------------

  private static e.t loadOrCreateTeam(Path templateBan) throws IOException {
    if (templateBan != null) {
      Object templateObj;
      try {
        templateObj = readSerialized(templateBan);
      } catch (ClassNotFoundException e) {
        throw new IOException("Template .ban incompatível (ClassNotFound): " + e.getMessage(), e);
      }

      if (templateObj instanceof e.t) return (e.t) templateObj;
      throw new IllegalArgumentException("Template nao eh e.t: " + templateObj.getClass());
    }
    return new e.t();
  }

  // =========================================================================
  // applyTeamFromJson — MODIFICADO
  //
  // Campos confirmados via análise binária do palmeiras.ban (e.t):
  //   nome (String) = nome do time
  //   f    (String) = nome do estádio      → ex.: "Parque Antarctica" / "Allianz Parque"
  //   g    (int)    = capacidade do estádio → ex.: 27650 (Parque Antarctica, confirmado)
  //   h    (String) = nome do treinador    → ex.: "Vanderlei Luxemburgo" / "Abel Ferreira"
  //   c    (int)    = candidato para nacionalidade do treinador (mesmo ID do mapping.json)
  //
  // ⚠️ Nota sobre campo `c`:
  //   Se após o teste a bandeira do treinador não aparecer no Brasfoot, tente
  //   trocar "c" por "aid" ou "n" na linha de setAnyField abaixo e recompile.
  // =========================================================================
  private static void applyTeamFromJson(
      e.t time, JsonObject root,
      Integer teamIdOverride, Integer countryIdOverride
  ) {
    // ── Nome do time ──────────────────────────────────────────────────────────
    if (root != null) {
      String nome = JsonUtil.getString(root, "team", "displayName");
      if (nome != null && !nome.isBlank()) {
        setAnyField(time, nome, "nome");
      }
      if (DEBUG) System.out.println("[DEBUG] team.nome=" + nome);
    } else {
      if (DEBUG) System.out.println("[DEBUG] team.nome=<mantido do template>");
    }

    // ── IDs externos ─────────────────────────────────────────────────────────
    if (teamIdOverride != null) setAnyField(time, teamIdOverride, "id");

    if (countryIdOverride != null) {
      setAnyField(time, countryIdOverride, "vid");
      setAnyField(time, countryIdOverride, "aid");
    }

    // ── Cores padrão ─────────────────────────────────────────────────────────
    Object cor1 = getAnyField(time, "cor1");
    Object cor2 = getAnyField(time, "cor2");
    if (cor1 == null) setAnyField(time, Color.WHITE, "cor1");
    if (cor2 == null) setAnyField(time, Color.BLACK, "cor2");

    setAnyField(time, Boolean.TRUE,  "valid");
    setAnyField(time, Boolean.FALSE, "mark");

    // ── NOVOS: estádio, capacidade, treinador, nacionalidade do treinador ─────
    if (root != null) {
      // O scrape.js agora gera:
      // {
      //   "team": {
      //     "stadiumName":      "Allianz Parque",
      //     "stadiumCapacity":  43713,
      //     "coachName":        "Abel Ferreira",
      //     "coachNationality": "Portugal"
      //   },
      //   "roster": [ ... jogadores ... ]
      // }
      JsonObject teamMeta = null;
      if (root.has("team") && !root.get("team").isJsonNull()
          && root.get("team").isJsonObject()) {
        teamMeta = root.getAsJsonObject("team");
      }

      if (teamMeta != null) {

        // f = nome do estádio
        String stadiumName = JsonUtil.getString(teamMeta, "stadiumName");
        if (stadiumName != null && !stadiumName.isBlank()) {
          setAnyField(time, stadiumName, "f");
          if (DEBUG) System.out.println("[DEBUG] team.f (stadiumName)=" + stadiumName);
        }

        // g = capacidade do estádio (int)
        if (teamMeta.has("stadiumCapacity")
            && !teamMeta.get("stadiumCapacity").isJsonNull()) {
          try {
            int capacity = teamMeta.get("stadiumCapacity").getAsInt();
            if (capacity > 0) {
              setAnyField(time, capacity, "g");
              if (DEBUG) System.out.println("[DEBUG] team.g (stadiumCapacity)=" + capacity);
            }
          } catch (Exception ex) {
            if (DEBUG) System.out.println("[DEBUG] team.stadiumCapacity parse error: " + ex.getMessage());
          }
        }

        // h = nome do treinador
        String coachName = JsonUtil.getString(teamMeta, "coachName");
        if (coachName != null && !coachName.isBlank()) {
          setAnyField(time, coachName, "h");
          if (DEBUG) System.out.println("[DEBUG] team.h (coachName)=" + coachName);
        }

        // c = ID de nacionalidade do treinador (via mapping.json, igual aos jogadores)
        // ⚠️ Se a bandeira do treinador não aparecer no Brasfoot após o teste,
        //    troque "c" por "aid" ou "n" e recompile.
        String coachNat = JsonUtil.getString(teamMeta, "coachNationality");
        if (coachNat != null && !coachNat.isBlank()) {
          Integer coachNatId = NationalityUtil.resolveCountryId(coachNat);
          if (coachNatId != null) {
            setAnyField(time, coachNatId, "c");
            if (DEBUG) System.out.println("[DEBUG] team.c (coachNat)=" + coachNat + " → id=" + coachNatId);
          } else {
            if (DEBUG) System.out.println("[DEBUG] team.coachNationality não mapeada: " + coachNat);
          }
        }
      }
    }

    if (DEBUG) {
      System.out.println("[DEBUG] teamIdOverride=" + teamIdOverride
          + " countryIdOverride=" + countryIdOverride);
    }
  }

  // -------------------------
  // Player
  // -------------------------

  private static e.g buildPlayerFromJson(JsonObject pj, e.t team, Integer countryIdOverride) {
    e.g p = new e.g();

    // ===== Nome =====
    String nome = JsonUtil.getString(pj, "name");
    if (nome == null || nome.isBlank()) nome = "SEM NOME";
    setAnyField(p, nome, "a");

    // ===== Idade =====
    Integer idade = JsonUtil.getInt(pj, "age");
    if (idade == null) idade = 20;
    setAnyField(p, idade, "d", "idade");

    // ===== Altura (m) =====
    double heightM = StatsReader.readHeightMeters(pj);

    // ===== Posição =====
    PositionUtil.PosInfo pi = PositionUtil.readPosInfo(pj);
    String posText = pi.bestText(); // nunca nulo
    int pos = PositionUtil.mapPositionFromMapping(posText);
    setAnyField(p, pos, "e", "posicao");

    // ===== Secundárias (texto) =====
    ArrayList<String> secondaryPositions = PositionUtil.readSecondaryPositionsAsText(pj);

    // ===== Lado (Brasfoot: 0=Direito, 1=Esquerdo) =====
    String foot = JsonUtil.getString(pj, "foot");
    int ladoCalc = SideResolver.resolveSideForBrasfoot(
        foot,
        pi.sideHint,
        posText,
        nome,
        pj,
        secondaryPositions
    );

    // Campo "i" = lado no Brasfoot (0=Direito, 1=Esquerdo) — confirmado: sem isso todos ficam "D".
    // Campo "f" = flag de titular (0=reserva, 1=titular) — confirmado: f=1 acende o boneco verde.
    // Campo "b" = estrela de qualidade — não tocamos aqui.
    setAnyField(p, ladoCalc, "i", "lado");
    // f começa como 0 (reserva) e será setado como 1 no loop de titulares

    // ===== Nacionalidade =====
    // Lê a primeira nacionalidade do JSON e converte para o ID do Brasfoot.
    // Fallback: usa vid do time, ou 0 caso não mapeie.
    String natName = NationalityUtil.readFirstNationalityName(pj);
    Integer paisId = NationalityUtil.resolveCountryId(natName);
    if (paisId == null) {
      if (countryIdOverride != null) {
        paisId = countryIdOverride;
      } else {
        Object vid = getAnyField(team, "vid", "aid");
        if (vid instanceof Number) paisId = ((Number) vid).intValue();
      }
    }
    if (paisId == null) paisId = 0;
    setAnyField(p, paisId, "c");

    if (DEBUG) System.out.println("[DEBUG] " + nome + " nat=" + natName + " paisId=" + paisId);

    // ===== Características (cr1, cr2) =====
    StatsReader.Stats st = StatsReader.read(pj);

    Integer idadeParam = JsonUtil.getInt(pj, "age");

    int[] top2 = HeuristicsEngine.pickTop2CharacteristicsByManual(
        pos,
        posText,
        secondaryPositions,
        st.matchesRelated,
        st.matchesPlayed,
        st.goals,
        st.assists,
        st.ownGoals,
        st.fromBench,
        st.substituted,
        st.yellow,
        st.yellowRed,
        st.red,
        st.penaltyGoals,
        st.minutesPerGoal,
        st.minutesPlayed,
        st.goalsConceded,
        st.cleanSheets,
        st.penFaced,
        st.penSaved,
        idadeParam,
        heightM
    );

    if (DEBUG) {
      System.out.println("[DEBUG] buildPlayer: " + nome
          + " pos=" + pos
          + " apps=" + st.matchesPlayed
          + " g=" + st.goals
          + " a=" + st.assists
          + " y=" + st.yellow
          + " yr=" + st.yellowRed
          + " r=" + st.red
          + " penG=" + st.penaltyGoals
          + " mpg=" + st.minutesPerGoal
          + " min=" + st.minutesPlayed
          + " gc=" + st.goalsConceded
          + " cs=" + st.cleanSheets
          + " penFaced=" + st.penFaced
          + " penSaved=" + st.penSaved
          + " -> cr1=" + top2[0] + " cr2=" + top2[1]
      );
    }

    // top2[2] = resolvedPos calculado pelo HeuristicsEngine.
    int resolvedPos = (top2.length >= 3) ? top2[2] : pos;
    if (resolvedPos != pos) {
      if (DEBUG) {
        System.out.println("[DEBUG] Posição ajustada para " + nome
            + ": pos original=" + pos + " → resolvedPos=" + resolvedPos);
      }
      setAnyField(p, resolvedPos, "e", "posicao");
    }
    setAnyField(p, top2[0], "g", "cr1");
    setAnyField(p, top2[1], "h", "cr2");

    if (DEBUG) {
      Object storedLado = getAnyField(p, "i");
      Object storedTitular = getAnyField(p, "f");
      System.out.println("[DEBUG] " + nome
          + " posText=" + TextUtil.safe(posText)
          + " sec=" + secondaryPositions
          + " foot=" + TextUtil.safe(foot)
          + " -> ladoCalc=" + ladoCalc + " lado(i)=" + storedLado + " titular(f)=" + storedTitular
          + " apps=" + st.matchesPlayed
          + " g=" + st.goals
          + " a=" + st.assists
          + " y=" + st.yellow
          + " yr=" + st.yellowRed
          + " r=" + st.red
          + " cs=" + st.cleanSheets
          + " gc=" + st.goalsConceded
          + " cr1=" + top2[0] + " cr2=" + top2[1]
      );
    }

    return p;
  }

  // -------------------------
  // Side deferral helpers
  // -------------------------

  /**
   * Determina se o lado do jogador deve ser resolvido de forma adiada (após processar
   * todo o elenco), analisando diretamente o texto da posição — sem usar pi.sideHint,
   * que pode retornar valor não-nulo mesmo para posições que não implicam lado.
   */
  private static boolean shouldDeferSide(String foot, String posText) {
    if (positionImpliesSide(posText)) return false;
    // Pé ambíguo (ambidestro ou null) + posição neutra → adiar
    if (foot == null || foot.isBlank()) return true;
    String f = foot.trim().toLowerCase(java.util.Locale.ROOT);
    return f.equals("ambidestro") || f.equals("both") || f.equals("ambi");
  }

  private static boolean isExplicitFoot(String foot) {
    if (foot == null || foot.isBlank()) return false;
    String f = foot.trim().toLowerCase(java.util.Locale.ROOT);
    return f.contains("direit") || f.contains("esquerd") || f.equals("right") || f.equals("left");
  }

  private static int resolveExplicitFootSide(String foot) {
    String f = (foot == null) ? "" : foot.trim().toLowerCase(java.util.Locale.ROOT);
    return (f.equals("esquerdo") || f.equals("left")) ? 1 : 0;
  }

  private static boolean positionImpliesSide(String posText) {
    if (posText == null || posText.isBlank()) return false;

    String norm = java.text.Normalizer.normalize(posText, java.text.Normalizer.Form.NFD);
    StringBuilder sb = new StringBuilder();
    for (char c : norm.toCharArray()) {
      int type = Character.getType(c);
      if (type != Character.NON_SPACING_MARK
          && type != Character.COMBINING_SPACING_MARK
          && type != Character.ENCLOSING_MARK) {
        sb.append(c);
      }
    }
    String p = sb.toString().toLowerCase(java.util.Locale.ROOT);

    if (p.contains("lateral esq") || p.contains("lateral dir")) return true;
    if (p.contains("ponta esq")   || p.contains("ponta dir"))   return true;
    if (p.contains("left wing")   || p.contains("right wing"))  return true;
    if (p.contains("meia esq")    || p.contains("meia dir"))    return true;
    if (p.contains("ala esq")     || p.contains("ala dir"))     return true;
    if (p.contains("extremo esq") || p.contains("extremo dir")) return true;
    if (p.equals("lw") || p.equals("rw"))           return true;
    if (p.startsWith("lw ") || p.startsWith("rw ")) return true;
    if (p.endsWith(" lw") || p.endsWith(" rw"))     return true;
    return false;
  }

  // -------------------------
  // IO
  // -------------------------

  private static JsonElement readJsonRoot(Path jsonPath) throws IOException {
    String raw = Files.readString(jsonPath);
    JsonElement el = GSON.fromJson(raw, JsonElement.class);
    if (el == null) throw new IllegalArgumentException("JSON invalido (vazio): " + jsonPath);
    return el;
  }

  private static Object readSerialized(Path file) throws IOException, ClassNotFoundException {
    try (ObjectInputStream ois =
             new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {

      ois.setObjectInputFilter(
          ObjectInputFilter.merge(
              SafeDeserialization.createFilter(),
              ObjectInputFilter.Config.createFilter("maxdepth=20;maxrefs=50000;maxbytes=5242880")
          )
      );

      return ois.readObject();
    }
  }

  private static void writeSerialized(Path file, Object obj) throws IOException {
    try (ObjectOutputStream oos = new ObjectOutputStream(
        new BufferedOutputStream(Files.newOutputStream(file)))) {
      oos.writeObject(obj);
      oos.flush();
    }
  }

  private static void ensureParentDir(Path outBan) throws IOException {
    Path parent = outBan.getParent();
    if (parent == null) return;

    if (Files.exists(parent)) {
      if (!Files.isDirectory(parent)) {
        throw new IOException("Saida invalida: o pai existe mas nao eh diretorio: " + parent);
      }
      return;
    }
    Files.createDirectories(parent);
  }

  // -------------------------
  // Reflection helpers
  // -------------------------

  private static void setAnyField(Object target, Object value, String... candidateNames) {
    for (String name : candidateNames) {
      Field f = findField(target.getClass(), name);
      if (f == null) continue;
      try {
        f.setAccessible(true);

        Class<?> ft = f.getType();
        Object coerced = coerce(value, ft);
        if (coerced == null && ft.isPrimitive()) continue;

        f.set(target, coerced);
        return;
      } catch (Exception e) {
        if (DEBUG) {
          System.out.println("[DEBUG] setAnyField FAIL field=" + target.getClass().getName() + "." + name
              + " type=" + f.getType().getName()
              + " value=" + value
              + " err=" + e.getClass().getSimpleName() + ":" + (e.getMessage() == null ? "" : e.getMessage()));
        }
      }
    }
  }

  private static Object getAnyField(Object target, String... candidateNames) {
    for (String name : candidateNames) {
      Field f = findField(target.getClass(), name);
      if (f == null) continue;
      try {
        f.setAccessible(true);
        return f.get(target);
      } catch (Exception ignored) {}
    }
    return null;
  }

  private static Field findField(Class<?> cls, String name) {
    Class<?> cur = cls;
    while (cur != null && cur != Object.class) {
      try {
        return cur.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        cur = cur.getSuperclass();
      }
    }
    return null;
  }

  private static Object coerce(Object v, Class<?> targetType) {
    if (v == null) return null;
    if (targetType.isInstance(v)) return v;

    if (targetType.isPrimitive()) {
      if (targetType == int.class)          targetType = Integer.class;
      else if (targetType == short.class)   targetType = Short.class;
      else if (targetType == byte.class)    targetType = Byte.class;
      else if (targetType == long.class)    targetType = Long.class;
      else if (targetType == boolean.class) targetType = Boolean.class;
      else if (targetType == double.class)  targetType = Double.class;
      else if (targetType == float.class)   targetType = Float.class;
      else if (targetType == char.class)    targetType = Character.class;
    }

    if (v instanceof Number n) {
      if (targetType == Integer.class) return n.intValue();
      if (targetType == Short.class)   return n.shortValue();
      if (targetType == Byte.class)    return n.byteValue();
      if (targetType == Long.class)    return n.longValue();
      if (targetType == Double.class)  return n.doubleValue();
      if (targetType == Float.class)   return n.floatValue();
    }

    if (v instanceof String s) {
      String t = s.trim();
      try {
        if (targetType == Integer.class) return Integer.parseInt(t);
        if (targetType == Short.class)   return Short.parseShort(t);
        if (targetType == Byte.class)    return Byte.parseByte(t);
        if (targetType == Long.class)    return Long.parseLong(t);
        if (targetType == Double.class)  return Double.parseDouble(t);
        if (targetType == Float.class)   return Float.parseFloat(t);
      } catch (Exception ignored) {}
    }

    if (targetType == Boolean.class) {
      if (v instanceof Boolean b) return b;
      if (v instanceof String s)   return Boolean.parseBoolean(s.trim());
      if (v instanceof Number n)   return n.intValue() != 0;
    }

    if (targetType == String.class) return String.valueOf(v);

    return v;
  }
}
