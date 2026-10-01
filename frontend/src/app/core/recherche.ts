/** Texte comparable : sans accents, en minuscules, espaces simplifiés (« KABORÉ  Aïcha » → « kabore aicha »). */
export function normaliser(texte: string | null | undefined): string {
  return (texte ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Vrai si chaque mot de la recherche se trouve dans l'un des champs : « paul san » trouve
 * « SANOU Paul ». Les chiffres sont aussi cherchés sans espaces ni « + » (téléphones).
 */
export function correspond(requete: string, ...champs: (string | number | null | undefined)[]): boolean {
  const mots = normaliser(requete).split(' ').filter(Boolean);
  if (mots.length === 0) {
    return true;
  }
  const texte = normaliser(champs.filter((c) => c !== null && c !== undefined).join(' '));
  const chiffres = texte.replace(/[^0-9]/g, '');
  return mots.every((m) => texte.includes(m) || (/^[0-9+ ]+$/.test(m) && chiffres.includes(m.replace(/[^0-9]/g, ''))));
}

/** Filtre une liste selon la recherche ; `champs` donne le texte cherchable de chaque élément. */
export function filtrer<T>(liste: readonly T[], requete: string, champs: (x: T) => (string | number | null | undefined)[]): T[] {
  return normaliser(requete) ? liste.filter((x) => correspond(requete, ...champs(x))) : [...liste];
}
