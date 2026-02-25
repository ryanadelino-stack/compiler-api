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
      Integer countryIdOverride
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

    // Primeiro passo: verifica se ALGUM jogador do elenco tem minutesPlayedSeason.
    // Se sim, estamos num JSON novo — jogadores sem o campo realmente não jogaram na
    // temporada (minutesPlayedSeason = 0). Se NENHUM tem o campo, é um JSON antigo e
    // usamos minutesPlayed (carreira) como fallback para não perder a ordenação.
    boolean anyHasSeasonMins = false;
    if (players != null) {
      for (JsonElement el : players) {
        if (!el.isJsonObject()) continue;
        StatsReader.Stats st = StatsReader.read(el.getAsJsonObject());
        if (st.minutesPlayedSeason >= 0) { anyHasSeasonMins = true; break; }
      }
    }
    if (DEBUG) System.out.println("[DEBUG] anyHasSeasonMins=" + anyHasSeasonMins);

    if (players != null) {
      for (JsonElement el : players) {
        if (!el.isJsonObject()) continue;
        JsonObject pj = el.getAsJsonObject();

        // ── Roteamento por categoria ──────────────────────────────────────────
        // "junior" → aba Juniores (.ban campo m)
        // "senior" / ausente → aba Jogadores (.ban campo l)
        String category = JsonUtil.getString(pj, "category");
        boolean isJunior = "junior".equalsIgnoreCase(category);

        e.g p = buildPlayerFromJson(pj, team, countryIdOverride);

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
          minutesByIndex.add(new int[]{jogadores.size(), minsParaOrdem});
          jogadores.add(p);
        }
      }
    }

    // Marca os 15 jogadores com mais minutos na temporada como titulares (f=1 = boneco verde).
    // Jogadores com 0 minutos na temporada ficam com f=0 (reserva).
    minutesByIndex.sort((a, b) -> Integer.compare(b[1], a[1]));

    int TITULARES_COUNT = 15;
    int marked = 0;
    if (DEBUG) System.out.println("[DEBUG] Titulares (top " + TITULARES_COUNT + " por minutos):");
    for (int k = 0; k < minutesByIndex.size() && marked < TITULARES_COUNT; k++) {
      int idx  = minutesByIndex.get(k)[0];
      int mins = minutesByIndex.get(k)[1];

      if (mins <= 0) break; // demais também serão 0

      setAnyField(jogadores.get(idx), 1, "f"); // f=1 = boneco verde (titular)
      marked++;

      if (DEBUG) {
        Object nomeJ = getAnyField(jogadores.get(idx), "a");
        System.out.println("[DEBUG]  " + marked + ". " + nomeJ + " mins=" + mins);
      }
    }
    if (DEBUG) System.out.println("[DEBUG] Total titulares marcados: " + marked + "/" + TITULARES_COUNT);

    // 4) Seta jogadores seniors no campo l
    setAnyField(team, jogadores, "l", "jogadores");

    // 5) Seta juniores no campo m:
    //    - Se o JSON trouxe juniores → seleciona os top-MAX_JUNIORES por minutos
    //    - Se não trouxe (campo category ausente em todos) → preserva o que estava no template
    if (!juniores.isEmpty()) {
      // Comparator com desempate em cascata:
      //   1º minutesPlayed (carreira) DESC
      //   2º matchesPlayed DESC
      //   3º matchesRelated DESC
      java.util.Comparator<int[]> junCmp = (a, b) -> {
        if (b[1] != a[1]) return Integer.compare(b[1], a[1]); // minutesPlayed
        if (b[2] != a[2]) return Integer.compare(b[2], a[2]); // matchesPlayed
        return Integer.compare(b[3], a[3]);                   // matchesRelated
      };
      juniorStats.sort(junCmp);

      // ── Seleciona os top MAX_JUNIORES ─────────────────────────────────────
      ArrayList<Object> junioresFinal = new ArrayList<>();
      // Usamos Set de índices para controlar quem entrou
      java.util.Set<Integer> selectedIdx = new java.util.LinkedHashSet<>();

      int junLimit = Math.min(MAX_JUNIORES, juniorStats.size());
      for (int k = 0; k < junLimit; k++) {
        selectedIdx.add(juniorStats.get(k)[0]);
      }

      // ── Garante ao menos 1 goleiro na lista ───────────────────────────────
      // Verifica se algum dos selecionados é goleiro (pos=0, campo "e")
      boolean hasGk = false;
      for (int idx : selectedIdx) {
        Object posVal = getAnyField(juniores.get(idx), "e", "posicao");
        if (posVal instanceof Number && ((Number) posVal).intValue() == 0) {
          hasGk = true;
          break;
        }
      }

      if (!hasGk) {
        // Procura o melhor goleiro fora da lista atual (já na ordem junCmp)
        int bestGkIdx = -1;
        for (int[] entry : juniorStats) {
          if (selectedIdx.contains(entry[0])) continue; // já está dentro
          Object posVal = getAnyField(juniores.get(entry[0]), "e", "posicao");
          if (posVal instanceof Number && ((Number) posVal).intValue() == 0) {
            bestGkIdx = entry[0];
            break;
          }
        }
        if (bestGkIdx >= 0) {
          // Remove o último da lista (pior por critério) e adiciona o goleiro
          java.util.Iterator<Integer> it = selectedIdx.iterator();
          int lastIdx = -1;
          while (it.hasNext()) lastIdx = it.next();
          if (lastIdx >= 0) {
            selectedIdx.remove(lastIdx);
            selectedIdx.add(bestGkIdx);
            if (DEBUG) {
              Object gkNome = getAnyField(juniores.get(bestGkIdx), "a");
              Object rmNome = getAnyField(juniores.get(lastIdx), "a");
              System.out.println("[DEBUG] junior GK obrigatorio: adicionou " + gkNome
                  + ", removeu " + rmNome);
            }
          }
        } else {
          if (DEBUG) System.out.println("[DEBUG] junior: nenhum goleiro disponivel fora do top");
        }
      }

      // ── Monta lista final na ordem do comparator ──────────────────────────
      if (DEBUG) System.out.println("[DEBUG] Juniores selecionados (top " + MAX_JUNIORES + "):");
      int rank = 1;
      for (int[] entry : juniorStats) {
        if (!selectedIdx.contains(entry[0])) continue;
        junioresFinal.add(juniores.get(entry[0]));
        if (DEBUG) {
          Object nomeJ = getAnyField(juniores.get(entry[0]), "a");
          System.out.println("[DEBUG]  " + rank + ". " + nomeJ
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
        // Pega os minutos do objeto já construído via campo paralelo
        // (junioresFinal está na mesma ordem do comparator — basta contar)
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

  private static void applyTeamFromJson(e.t time, JsonObject root, Integer teamIdOverride, Integer countryIdOverride) {
    // Se root == null (schema novo: array), mantém nome do template.
    if (root != null) {
      String nome = JsonUtil.getString(root, "team", "displayName");
      if (nome != null && !nome.isBlank()) {
        setAnyField(time, nome, "nome");
      }
      if (DEBUG) System.out.println("[DEBUG] team.nome=" + nome);
    } else {
      if (DEBUG) System.out.println("[DEBUG] team.nome=<mantido do template>");
    }

    if (teamIdOverride != null) setAnyField(time, teamIdOverride, "id");

    if (countryIdOverride != null) {
      setAnyField(time, countryIdOverride, "vid");
      setAnyField(time, countryIdOverride, "aid");
    }

    Object cor1 = getAnyField(time, "cor1");
    Object cor2 = getAnyField(time, "cor2");
    if (cor1 == null) setAnyField(time, Color.WHITE, "cor1");
    if (cor2 == null) setAnyField(time, Color.BLACK, "cor2");

    setAnyField(time, Boolean.TRUE, "valid");
    setAnyField(time, Boolean.FALSE, "mark");

    if (DEBUG) {
      System.out.println("[DEBUG] teamIdOverride=" + teamIdOverride + " countryIdOverride=" + countryIdOverride);
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
          + " -> cr1=" + top2[0] + " cr2=" + top2[1]
      );
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
    try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
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
