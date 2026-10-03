import { Component, input, output, signal } from '@angular/core';

/**
 * Mot de passe provisoire d'un compte créé : le serveur ne le renvoie qu'une
 * fois, il faut le transmettre à la personne (qui le changera à sa première connexion).
 */
@Component({
  selector: 'app-mot-de-passe-temporaire',
  template: `
    <div class="alerte succes" role="status">
      <p>
        <strong>{{ titre() }}</strong><br />
        Téléphone <strong>{{ telephone() }}</strong>, mot de passe provisoire
        <span class="secret">{{ motDePasse() }}</span>
        <button type="button" class="bouton discret petit" (click)="copier()">{{ copie() ? 'Copié' : 'Copier' }}</button>
      </p>
      <p class="doux">
        Notez-le et transmettez-le à la personne : il ne sera plus affiché. Elle le changera à sa première connexion.
      </p>
      <button type="button" class="bouton secondaire petit" (click)="fermer.emit()">J'ai noté le mot de passe</button>
    </div>
  `,
})
export class MotDePasseTemporaireComponent {
  readonly titre = input.required<string>();
  readonly telephone = input.required<string>();
  readonly motDePasse = input.required<string>();
  readonly fermer = output<void>();
  protected readonly copie = signal(false);

  protected async copier(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.motDePasse());
      this.copie.set(true);
    } catch {
      // Presse-papiers indisponible (HTTP sur le réseau local) : le mot de passe reste lisible à l'écran
    }
  }
}
