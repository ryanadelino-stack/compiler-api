package br.brasfoot.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "br.brasfoot")
public class CompilerApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(CompilerApiApplication.class, args);
  }
}
