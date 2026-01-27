package br.brasfoot.compiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

public class BanCompilerRegressionTest {

  /**
   * Smoke test:
   * - só roda se -Dbrasfoot.templateBan e (opcionalmente) -Dbrasfoot.fixtureJson estiverem OK
   * - compila sem crash
   * - gera .ban não-vazio
   */
  @Test
  void smoke_shouldCompileJsonIntoBan_withRequiredTemplate() {
    Path templateBan = resolveTemplateBanOrSkip();
    Path fixtureJson = resolveFixtureJsonOrSkip();

    Path outBan = tempOutBan("smoke");

    assertDoesNotThrow(() -> BanCompiler.compileTeamJsonToBan(
        fixtureJson,
        templateBan,
        outBan,
        null,
        null
    ));

    assertTrue(Files.exists(outBan), "Arquivo .ban de saída não foi gerado");
    assertTrue(outBan.toFile().length() > 0, ".ban gerado está vazio");
  }

  /**
   * Contrato do modo template:
   * - elenco profissional deve bater exatamente o JSON
   * - juniores devem ser preservados do template (não-null e não-vazios)
   *
   * Observação: não valida heurísticas e nem posições (para não travar evolução do projeto).
   */
  @Test
  void templateMode_shouldPreserveJuniors_andReplaceSeniorSquad() throws Exception {
    Path templateBan = resolveTemplateBanOrSkip();
    Path fixtureJson = resolveFixtureJsonOrSkip();

    Path outBan = tempOutBan("template");

    BanCompiler.compileTeamJsonToBan(
        fixtureJson,
        templateBan,
        outBan,
        null,
        null
    );

    // Lê JSON de entrada para referência de quantidade
    JsonArray jsonPlayers;
    try (var r = Files.newBufferedReader(fixtureJson)) {
      jsonPlayers = JsonParser.parseReader(r).getAsJsonArray();
    }
    assertNotNull(jsonPlayers, "JSON inválido / não parseável");
    assertTrue(jsonPlayers.size() > 0, "JSON de teste não possui jogadores");

    // Carrega o time do .ban via reflection (readSerialized não é visível)
    Object team = readTeamFromBanViaReflection(outBan);
    assertNotNull(team, "Não foi possível ler o objeto do .ban gerado");

    // Encontra elenco profissional (jogadores) e juniores por reflection
    Object senior = getFirstFieldValueByCandidates(team,
        "jogadores", "players", "plantel", "squad", "elenco"
    );
    Object juniors = getFirstFieldValueByCandidates(team,
        "juniores", "juniors", "youth", "base", "sub20", "sub_20"
    );

    // Valida elenco principal
    int seniorSize = sizeOfCollectionLike(senior);
    assertEquals(
        jsonPlayers.size(),
        seniorSize,
        "Quantidade de jogadores no .ban deve ser exatamente igual ao JSON"
    );

    // Valida juniores preservados
    int juniorsSize = sizeOfCollectionLike(juniors);
    assertTrue(juniorsSize > 0, "Juniores devem ser preservados do template (não podem ficar vazios)");
  }

  // ----------------------------------------------------------------------
  // Helpers: Fixture / Template / Temp
  // ----------------------------------------------------------------------

  /**
   * JSON de fixture:
   * - Pode ser sobrescrito via:
   *   -Dbrasfoot.fixtureJson=CAMINHO
   * - Caso não exista, o teste é SKIPPED.
   *
   * Default: src/test/resources/palmeiras.json (se existir no seu repo).
   */
  private Path resolveFixtureJsonOrSkip() {
    String p = System.getProperty("brasfoot.fixtureJson", "src/test/resources/palmeiras.json");
    Path path = Paths.get(p);

    if (!Files.exists(path)) {
      System.err.println("[SKIP] Fixture JSON não encontrado: " + path.toAbsolutePath());
      Assumptions.assumeTrue(false);
    }
    return path;
  }

  /**
   * Template .ban:
   * - Pode ser passado via:
   *   -Dbrasfoot.templateBan=CAMINHO_DO_BAN
   * - Se não existir ou não for informado: SKIP (não falha o build).
   *
   * Motivo: permitir `mvn clean package` sem precisar de arquivos locais do usuário.
   */
  private Path resolveTemplateBanOrSkip() {
    String p = System.getProperty("brasfoot.templateBan");

    if (p == null || p.isBlank()) {
      System.err.println("[SKIP] -Dbrasfoot.templateBan não informado; pulando testes dependentes de template.");
      Assumptions.assumeTrue(false);
    }

    Path path = Paths.get(p);
    if (!Files.exists(path)) {
      System.err.println("[SKIP] templateBan não existe: " + path.toAbsolutePath());
      Assumptions.assumeTrue(false);
    }

    return path;
  }

  private Path tempOutBan(String suffix) {
    try {
      return Files.createTempFile("brasfoot-test-" + suffix + "-", ".ban");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ----------------------------------------------------------------------
  // Helpers: Reflection (BanCompiler + Team fields)
  // ----------------------------------------------------------------------

  /**
   * Invoca BanCompiler.readSerialized(Path) mesmo não sendo visível.
   */
  private static Object readTeamFromBanViaReflection(Path ban) throws Exception {
    Method m = BanCompiler.class.getDeclaredMethod("readSerialized", Path.class);
    m.setAccessible(true);
    return m.invoke(null, ban);
  }

  /**
   * Procura um campo no objeto por uma lista de nomes candidatos.
   * Retorna o primeiro que existir e for acessível via reflection.
   */
  private static Object getFirstFieldValueByCandidates(Object target, String... names) throws Exception {
    Class<?> c = target.getClass();

    for (String n : names) {
      Field f = findFieldRecursive(c, n);
      if (f != null) {
        f.setAccessible(true);
        return f.get(target);
      }
    }

    // fallback: primeiro campo Collection-like encontrado
    Field[] all = getAllFieldsRecursive(c);
    for (Field f : all) {
      f.setAccessible(true);
      Object v = f.get(target);
      if (isCollectionLike(v)) return v;
    }

    fail("Não foi possível localizar campos esperados em " + c.getName()
        + " (ajuste os candidates no teste).");
    return null;
  }

  private static Field findFieldRecursive(Class<?> c, String name) {
    Class<?> cur = c;
    while (cur != null && cur != Object.class) {
      try {
        return cur.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        cur = cur.getSuperclass();
      }
    }
    return null;
  }

  private static Field[] getAllFieldsRecursive(Class<?> c) {
    java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
    Class<?> cur = c;
    while (cur != null && cur != Object.class) {
      Field[] fs = cur.getDeclaredFields();
      java.util.Collections.addAll(fields, fs);
      cur = cur.getSuperclass();
    }
    return fields.toArray(new Field[0]);
  }

  private static boolean isCollectionLike(Object v) {
    if (v == null) return false;
    if (v instanceof Collection) return true;
    return v.getClass().isArray();
  }

  private static int sizeOfCollectionLike(Object v) {
    assertNotNull(v, "Campo esperado está null (não deveria)");
    if (v instanceof Collection<?> col) return col.size();
    if (v.getClass().isArray()) return java.lang.reflect.Array.getLength(v);
    fail("Objeto não é Collection nem array: " + v.getClass().getName());
    return 0;
  }
}