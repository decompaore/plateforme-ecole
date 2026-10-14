import { defineConfig } from 'vitest/config';

// Options Vitest complémentaires de celles du builder Angular (@angular/build:unit-test).
// Les tests de pages (TestBed, rendu, HttpTestingController) dépassent parfois les 5 s par défaut sur
// un poste chargé (Docker, Maven et IDE en parallèle) : on laisse plus de marge, sans masquer un vrai
// blocage, qui se verrait toujours par l'échec de http.verify().
export default defineConfig({
  test: {
    testTimeout: 30_000,
    hookTimeout: 30_000,
  },
});
