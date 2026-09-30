/** Date locale de l'appareil au format AAAA-MM-JJ (et non la date UTC). */
export function dateLocale(d: Date = new Date()): string {
  return `${d.getFullYear()}-${deux(d.getMonth() + 1)}-${deux(d.getDate())}`;
}

/** Heure locale HH:MM. */
export function heureLocale(d: Date = new Date()): string {
  return `${deux(d.getHours())}:${deux(d.getMinutes())}`;
}

/** Ajoute des minutes à une heure HH:MM, sans dépasser 23:59. */
export function ajouterMinutes(heure: string, minutes: number): string {
  const [h, m] = heure.split(':').map(Number);
  const total = Math.min(h * 60 + m + minutes, 23 * 60 + 59);
  return `${deux(Math.floor(total / 60))}:${deux(total % 60)}`;
}

function deux(n: number): string {
  return String(n).padStart(2, '0');
}

/**
 * Identifiant unique. crypto.randomUUID n'existe que sur HTTPS ou localhost :
 * sur un téléphone qui teste l'application en HTTP sur le réseau local, on
 * construit un UUID v4 avec crypto.getRandomValues, disponible partout.
 */
export function nouvelIdentifiant(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const o = crypto.getRandomValues(new Uint8Array(16));
  o[6] = (o[6] & 0x0f) | 0x40;
  o[8] = (o[8] & 0x3f) | 0x80;
  const h = Array.from(o, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** « lun. 5 oct. à 08:12 » */
export function dateHeureCourte(iso: string): string {
  return new Date(iso).toLocaleString('fr-FR', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** « lundi 5 octobre » pour une date AAAA-MM-JJ. */
export function dateLongue(jour: string): string {
  const [a, m, j] = jour.split('-').map(Number);
  return new Date(a, m - 1, j).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
}
