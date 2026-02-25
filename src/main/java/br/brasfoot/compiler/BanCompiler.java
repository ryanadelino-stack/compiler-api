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

    ArrayList<Object> jogadores = new ArrayList<>();
    // Lista paralela: [índice, minutesPlayedSeason ou minutesPlayed]
    ArrayList<int[]> minutesByIndex = new ArrayList<>();

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
        e.g p = buildPlayerFromJson(pj, team, countryIdOverride);

        StatsReader.Stats st = StatsReader.read(pj);
        int minsParaOrdem;
        if (anyHasSeasonMins) {
          // JSON novo: campo ausente = jogador não entrou em campo nessa temporada → 0
          minsParaOrdem = (st.minutesPlayedSeason >= 0) ? st.minutesPlayedSeason : 0;
        } else {
          // JSON antigo (sem minutesPlayedSeason em nenhum jogador): usa carreira
          minsParaOrdem = st.minutesPlayed;
        }
        minutesByIndex.add(new int[]{jogadores.size(), minsParaOrdem});

        jogadores.add(p);
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

    // Lista na ordem original do JSON — o campo f=1 é o mecanismo de titular, não a ordem.
    ArrayList<Object> jogadoresOrdenados = jogadores;

    // 4) Seta jogadores/juniores
    setAnyField(team, jogadoresOrdenados, "l", "jogadores");
    Object jun = getAnyField(team, "m", "juniores");
    if (jun == null) setAnyField(team, new ArrayList<>(), "m", "juniores");

    // 5) Salva
    ensureParentDir(outBan);
    writeSerialized(outBan, team);

    if (DEBUG) {
      System.out.println("[DEBUG] wrote outBan=" + outBan);
      System.out.println("[DEBUG] jogadores.size=" + jogadores.size());
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
      if (targetType == int.class)     targetType = Integer.class;
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
      if (v instanceof String s) return Boolean.parseBoolean(s.trim());
      if (v instanceof Number n) return n.intValue() != 0;
    }

    if (targetType == String.class) return String.valueOf(v);

    return v;
  }
}
