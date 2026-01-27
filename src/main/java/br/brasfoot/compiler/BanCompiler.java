package br.brasfoot.compiler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Color;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.io.ObjectInputFilter;
import java.io.InvalidClassException;

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
    if (players != null) {
      for (JsonElement el : players) {
        if (!el.isJsonObject()) continue;
        e.g p = buildPlayerFromJson(el.getAsJsonObject(), team, countryIdOverride);
        jogadores.add(p);
      }
    }

    // 4) Seta jogadores/juniores
    setAnyField(team, jogadores, "l", "jogadores");
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

    // IMPORTANTE: escreva no campo realmente usado.
    // Preferência: "f" (muito comum em builds do brasfoot.jar) e também "i" se existir.
    setAnyField(p, ladoCalc, "f", "lado"); // preferir "f"
    setAnyField(p, ladoCalc, "i");         // fallback/compat

    // ===== Nacionalidade: SEMPRE a primeira =====
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

    // ===== Stats (todos os 15 itens + GK) =====
    StatsReader.Stats st = StatsReader.read(pj);

    // ===== Características =====
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
        idade,
        heightM
    );

    setAnyField(p, top2[0], "g", "cr1");
    setAnyField(p, top2[1], "h", "cr2");

    if (DEBUG) {
      Object storedSideF = getAnyField(p, "f", "lado");
      Object storedSideI = getAnyField(p, "i");
      System.out.println("[DEBUG] " + nome
          + " posText=" + TextUtil.safe(posText)
          + " sec=" + secondaryPositions
          + " foot=" + TextUtil.safe(foot)
          + " -> ladoCalc=" + ladoCalc + " storedSide(f/lado)=" + storedSideF + " storedSide(i)=" + storedSideI
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
      if (targetType == int.class) targetType = Integer.class;
      else if (targetType == short.class) targetType = Short.class;
      else if (targetType == byte.class) targetType = Byte.class;
      else if (targetType == long.class) targetType = Long.class;
      else if (targetType == boolean.class) targetType = Boolean.class;
      else if (targetType == double.class) targetType = Double.class;
      else if (targetType == float.class) targetType = Float.class;
      else if (targetType == char.class) targetType = Character.class;
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