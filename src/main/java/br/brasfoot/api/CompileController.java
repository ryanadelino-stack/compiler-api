package br.brasfoot.api;

import br.brasfoot.compiler.BanCompiler;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;

import java.io.InvalidClassException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
public class CompileController {

  public CompileController() {
    br.brasfoot.compiler.SafeDeserialization.clearLastRejected();
  }

  @PostMapping(value = "/compile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<byte[]> compile(
      @RequestPart("template") MultipartFile templateBan,
      @RequestPart("teamJson") MultipartFile teamJson,
      @RequestPart(value = "teamIdOverride", required = false) Integer teamIdOverride,
      @RequestPart(value = "countryIdOverride", required = false) Integer countryIdOverride,
      // ── Modo Competitivo ─────────────────────────────────────────────────────
      // Quando true: elenco sênior limitado a 25 jogadores (top por minutos),
      // sem marcação de titulares (f=0 para todos).
      @RequestParam(value = "competitive", required = false, defaultValue = "false") String competitiveStr
  ) {
    boolean competitive = "true".equalsIgnoreCase(competitiveStr);

    try {
      if (templateBan == null || templateBan.isEmpty()) {
        return ResponseEntity.badRequest().body("template (.ban) ausente".getBytes());
      }
      if (teamJson == null || teamJson.isEmpty()) {
        return ResponseEntity.badRequest().body("teamJson (.json) ausente".getBytes());
      }

      Path tmpDir = Files.createTempDirectory("bf-compile-");
      Path templatePath = tmpDir.resolve("template.ban");
      Path inputJsonPath = tmpDir.resolve("team.json");
      Path outBanPath = tmpDir.resolve("out.ban");

      Files.write(templatePath, templateBan.getBytes());
      Files.write(inputJsonPath, teamJson.getBytes());

      BanCompiler.compileTeamJsonToBan(
          inputJsonPath,
          templatePath,
          outBanPath,
          teamIdOverride,
          countryIdOverride,
          competitive          // ← novo parâmetro
      );

      byte[] outBytes = Files.readAllBytes(outBanPath);

      // limpeza best-effort
      try { Files.deleteIfExists(templatePath); } catch (Exception ignored) {}
      try { Files.deleteIfExists(inputJsonPath); } catch (Exception ignored) {}
      try { Files.deleteIfExists(outBanPath); } catch (Exception ignored) {}
      try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) {}

      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"time.ban\"")
          .body(outBytes);

    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .contentType(MediaType.TEXT_PLAIN)
          .body(("Template inválido/incompatível: " + e.getMessage()).getBytes());
    } catch (InvalidClassException e) {
      String rejected = br.brasfoot.compiler.SafeDeserialization.getLastRejected();
      String extra = (rejected != null)
          ? " [classe rejeitada: " + rejected + "]"
          : "";
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .contentType(MediaType.TEXT_PLAIN)
          .body(("Template incompatível com esta versão do Brasfoot" + extra).getBytes());
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .contentType(MediaType.TEXT_PLAIN)
          .body(("Erro interno: " + e.getMessage()).getBytes());
    }
  }
}
