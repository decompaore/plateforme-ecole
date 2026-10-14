import { PeriodeLocale } from './listes.service';

/**
 * Période proposée par défaut pour un profil : celle qui contient le jour,
 * sinon la dernière commencée, sinon la première.
 */
export function periodeParDefaut(periodes: PeriodeLocale[], profilId: string | undefined, jour: string): PeriodeLocale | undefined {
  const duProfil = periodes.filter((p) => !profilId || p.profilId === profilId).sort((a, b) => a.ordre - b.ordre);
  return (
    duProfil.find((p) => p.debut <= jour && jour <= p.fin) ??
    [...duProfil].reverse().find((p) => p.debut <= jour) ??
    duProfil[0]
  );
}
