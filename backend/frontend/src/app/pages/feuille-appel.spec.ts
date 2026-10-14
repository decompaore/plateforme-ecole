import { ajouterMinutes, dateLocale, nouvelIdentifiant } from '../core/outils';
import { EleveLocal } from '../hors-ligne/listes.service';
import { StockageMemoire } from '../hors-ligne/stockage';
import { FeuilleAppel, MINUTES_PAR_DEFAUT } from './feuille-appel';

const ELEVES: EleveLocal[] = [
  { inscriptionId: 'i1', nom: 'COMPAORE', prenoms: 'Aïcha', matricule: null, sexe: 'F' },
  { inscriptionId: 'i2', nom: 'KABORE', prenoms: 'Ali', matricule: null, sexe: 'M' },
  { inscriptionId: 'i3', nom: 'ZONGO', prenoms: 'Rasmata', matricule: null, sexe: 'F' },
];

describe('FeuilleAppel', () => {
  it('tout le monde est présent au départ', () => {
    const f = new FeuilleAppel(ELEVES);
    expect(f.presents()).toBe(3);
    expect(f.marques()).toEqual([]);
  });

  it('marque absents et retards, et revient à présent sur un second appui', () => {
    const f = new FeuilleAppel(ELEVES);
    f.basculer('i1', 'ABSENCE');
    f.basculer('i2', 'RETARD');
    f.minutes('i2', 25);
    expect(f.absents()).toBe(1);
    expect(f.retards()).toBe(1);
    expect(f.presents()).toBe(2);
    expect(f.marques()).toEqual([
      { inscriptionId: 'i1', type: 'ABSENCE', minutesRetard: null },
      { inscriptionId: 'i2', type: 'RETARD', minutesRetard: 25 },
    ]);

    f.basculer('i1', 'ABSENCE');
    expect(f.absents()).toBe(0);

    // Passer d'absent à retard garde une durée de retard
    f.basculer('i3', 'ABSENCE');
    f.basculer('i3', 'RETARD');
    expect(f.etat('i3')).toEqual({ type: 'RETARD', minutes: MINUTES_PAR_DEFAUT });
  });

  it('borne les minutes de retard comme le serveur (1 à 600)', () => {
    const f = new FeuilleAppel(ELEVES);
    f.basculer('i1', 'RETARD');
    f.minutes('i1', 0);
    expect(f.etat('i1')?.minutes).toBe(1);
    f.minutes('i1', 900);
    expect(f.etat('i1')?.minutes).toBe(600);
    f.minutes('i2', 30); // pas en retard : ignoré
    expect(f.etat('i2')).toBeUndefined();
  });

  it('récapitulatif dans l’ordre de la liste, et remise à zéro', () => {
    const f = new FeuilleAppel(ELEVES);
    f.basculer('i3', 'ABSENCE');
    f.basculer('i1', 'RETARD');
    expect(f.detail().map((d) => d.eleve.inscriptionId)).toEqual(['i1', 'i3']);
    f.toutPresent();
    expect(f.presents()).toBe(3);
  });
});

describe('outils', () => {
  it('date locale et non UTC', () => {
    expect(dateLocale(new Date(2026, 9, 5, 23, 30))).toBe('2026-10-05');
  });

  it('ajoute des minutes sans dépasser minuit', () => {
    expect(ajouterMinutes('08:00', 120)).toBe('10:00');
    expect(ajouterMinutes('07:30', 45)).toBe('08:15');
    expect(ajouterMinutes('23:00', 120)).toBe('23:59');
  });

  it('identifiants UUID v4 distincts', () => {
    const a = nouvelIdentifiant();
    expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(nouvelIdentifiant()).not.toBe(a);
  });
});

describe('StockageMemoire', () => {
  it('liste et purge par préfixe, dans l’ordre des clés, en copiant les valeurs', async () => {
    const s = new StockageMemoire();
    await s.ecrire('envoi:u1:b', { n: 2 });
    await s.ecrire('envoi:u1:a', { n: 1 });
    await s.ecrire('envoi:u2:a', { n: 3 });
    expect(await s.lister('envoi:u1:')).toEqual([{ n: 1 }, { n: 2 }]);

    const lu = await s.lire<{ n: number }>('envoi:u1:a');
    lu!.n = 99;
    expect((await s.lire<{ n: number }>('envoi:u1:a'))?.n).toBe(1);

    await s.purger('envoi:u1:');
    expect(await s.lister('envoi:')).toEqual([{ n: 3 }]);
  });
});
