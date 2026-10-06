import { Component } from '@angular/core';

import { AdminNavComponent } from '../admin-nav.component';
import { ExportsDonneesComponent } from '../exports-donnees.component';

/**
 * Données de l'établissement : export complet (réversibilité). Les données appartiennent à
 * l'établissement ; il peut les récupérer à tout moment dans des formats ouverts.
 */
@Component({
  selector: 'app-donnees',
  imports: [AdminNavComponent, ExportsDonneesComponent],
  template: `
    <div class="page large">
      <h1>Données de l'établissement</h1>
      <app-admin-nav />

      <section class="carte">
        <h2>Export complet</h2>
        <p class="doux">
          Les données de l'établissement lui appartiennent. L'export complet rassemble dans une archive ZIP toutes ses
          données (élèves, parents, notes, bulletins, absences, paiements, cahier de textes, emplois du temps, ateliers,
          journal d'audit…) : une table par fichier CSV, qui s'ouvre dans Excel ou LibreOffice, avec une description de
          chaque colonne pour les reprendre dans un autre logiciel. Les mots de passe n'y figurent pas.
        </p>
        <p class="doux">
          L'archive est préparée en quelques minutes et reste téléchargeable 7 jours, puis elle est effacée du serveur.
          Elle contient des données personnelles d'élèves mineurs : conservez-la en lieu sûr.
        </p>
        <app-exports-donnees />
      </section>
    </div>
  `,
})
export class DonneesPage {}
