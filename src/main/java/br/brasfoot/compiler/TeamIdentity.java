package br.brasfoot.compiler;

import java.net.URI;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identidade determinística do time/temporada, derivada da URL do Transfermarkt.
 * Ex:
 * https://www.transfermarkt.com.br/se-palmeiras-sao-paulo/startseite/verein/1023/saison_id/2025
 *
 * -> country=br, teamId=1023, season=2025, slug=se-palmeiras-sao-paulo
 */
public record TeamIdentity(String country, int teamId, int season, String slug) {

  private static final Pattern TEAM_ID = Pattern.compile("/verein/(\\d+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SEASON_ID = Pattern.compile("/saison_id/(\\d+)", Pattern.CASE_INSENSITIVE);

  public TeamIdentity {
    Objects.requireNonNull(country, "country");
    Objects.requireNonNull(slug, "slug");
    if (country.isBlank()) throw new IllegalArgumentException("country vazio");
    if (slug.isBlank()) throw new IllegalArgumentException("slug vazio");
    if (teamId <= 0) throw new IllegalArgumentException("teamId inválido: " + teamId);
    if (season <= 0) throw new IllegalArgumentException("season inválido: " + season);
  }

  public static TeamIdentity fromTeamUrl(String teamUrl) {
    if (teamUrl == null || teamUrl.isBlank()) {
      throw new IllegalArgumentException("teamUrl é obrigatório.");
    }

    URI uri = safeUri(teamUrl);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    String path = uri.getPath() == null ? "" : uri.getPath();

    int teamId = extractInt(TEAM_ID, path, "teamId (verein)");
    int season = extractInt(SEASON_ID, path, "season (saison_id)");

    String country = inferCountryFromHost(host);
    String slug = inferSlugFromPath(path);

    return new TeamIdentity(country, teamId, season, slug);
  }

  private static URI safeUri(String url) {
    String u = url.trim();
    // Permite colar sem schema
    if (!u.startsWith("http://") && !u.startsWith("https://")) {
      u = "https://" + u;
    }
    return URI.create(u);
  }

  private static int extractInt(Pattern p, String s, String label) {
    Matcher m = p.matcher(s);
    if (!m.find()) {
      throw new IllegalArgumentException("Não foi possível extrair " + label + " da URL: " + s);
    }
    return Integer.parseInt(m.group(1));
  }

  private static String inferCountryFromHost(String host) {
    // Transfermarkt BR costuma ser transfermarkt.com.br
    if (host.endsWith(".com.br")) return "br";
    if (host.endsWith(".de")) return "de";
    if (host.endsWith(".com")) return "com";
    // fallback “genérico”
    return host.isBlank() ? "unknown" : host;
  }

  private static String inferSlugFromPath(String path) {
    // Esperado: /<slug>/<page>/verein/<id>/saison_id/<season>
    // Pegamos o 1º segmento
    String p = path == null ? "" : path.trim();
    if (p.startsWith("/")) p = p.substring(1);
    if (p.isBlank()) return "team";

    String[] parts = p.split("/");
    String raw = parts.length > 0 ? parts[0] : "team";
    raw = raw.trim();
    if (raw.isBlank()) raw = "team";

    // Normaliza para garantir nome de arquivo seguro
    return toSafeSlug(raw);
  }

  private static String toSafeSlug(String s) {
    String t = s.toLowerCase(Locale.ROOT);
    t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    t = t.replaceAll("[^a-z0-9\\-]+", "-");
    t = t.replaceAll("-{2,}", "-");
    t = t.replaceAll("(^-+|-+$)", "");
    return t.isBlank() ? "team" : t;
  }
}