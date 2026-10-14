import { AfterViewInit, Component, DestroyRef, ElementRef, inject, signal, viewChild } from '@angular/core';

import { Action } from '../admin/action';
import { dateHeureCourte } from '../core/outils';
import { EtatSignature, PhotosApi } from '../partage/photos-api.service';

/** Cadre utile autour du tracé (pixels du canvas). */
const MARGE = 8;

/**
 * Ma signature (v0.44) : l'enseignant la dessine au doigt ou à la souris, ou dépose la photo de sa
 * signature sur papier blanc. Elle s'imprime sur les bulletins, devant chaque matière qu'il a visée.
 * Elle appartient au compte : un enseignant de deux établissements la dépose une fois.
 */
@Component({
  selector: 'app-signature',
  template: `
    <div class="page">
      <h1>Ma signature</h1>
      <p class="doux">
        Elle s'imprime sur les bulletins, dans la colonne « Visa », devant chaque matière que vous avez visée (avec la date du
        visa). Vous seul la voyez et la modifiez ; elle vaut pour tous vos établissements.
      </p>

      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <section class="carte">
        <h2>Signature actuelle</h2>
        @if (actuelle(); as url) {
          <img class="actuelle" [src]="url" alt="Votre signature" />
          <p class="doux">Déposée le {{ etat()?.modifieeLe ? dateHeureCourte(etat()!.modifieeLe!) : '—' }}</p>
          <button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="retirer()">Retirer ma signature</button>
        } @else {
          <p class="doux">
            Aucune signature : vos visas s'impriment « Visé » sans signature. Dessinez-la ci-dessous ou déposez-en une photo.
          </p>
        }
      </section>

      <section class="carte">
        <h2>Dessiner ma signature</h2>
        <p class="doux">Signez dans le cadre, au doigt sur un téléphone ou à la souris.</p>
        <canvas
          #zone
          class="zone"
          width="600"
          height="200"
          aria-label="Zone de signature"
          (pointerdown)="debut($event)"
          (pointermove)="trace($event)"
          (pointerup)="fin()"
          (pointerleave)="fin()"
          (pointercancel)="fin()"
        ></canvas>
        <div class="actions-ligne">
          <button type="button" class="bouton" [disabled]="action.enCours() || !dessinee()" (click)="enregistrerDessin()">Enregistrer cette signature</button>
          <button type="button" class="bouton secondaire" [disabled]="!dessinee()" (click)="effacer()">Effacer</button>
        </div>
      </section>

      <section class="carte">
        <h2>Ou déposer une photo</h2>
        <p class="doux">Signez sur une feuille blanche, au stylo noir ou bleu, et photographiez-la de près (PNG ou JPEG).</p>
        <label class="bouton secondaire">
          Choisir ou prendre une photo
          <input type="file" accept="image/png,image/jpeg" capture="environment" (change)="deposerFichier($event)" [disabled]="action.enCours()" hidden />
        </label>
      </section>
    </div>
  `,
  styles: `
    .actuelle {
      display: block;
      max-width: 300px;
      max-height: 100px;
      background: #fff;
      border: 1px solid var(--bordure);
      border-radius: 6px;
      padding: 0.25rem;
    }
    .zone {
      display: block;
      width: 100%;
      max-width: 600px;
      aspect-ratio: 3 / 1;
      background: #fff;
      border: 2px dashed var(--bordure);
      border-radius: 8px;
      touch-action: none;
      cursor: crosshair;
      margin-bottom: 0.75rem;
    }
    label.bouton {
      cursor: pointer;
    }
  `,
})
export class SignaturePage implements AfterViewInit {
  private readonly api = inject(PhotosApi);
  private readonly zone = viewChild.required<ElementRef<HTMLCanvasElement>>('zone');

  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly action = new Action();
  protected readonly etat = signal<EtatSignature | null>(null);
  protected readonly actuelle = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly dessinee = signal(false);

  private crayon = false;
  private bornes = { x1: Infinity, y1: Infinity, x2: -Infinity, y2: -Infinity };

  constructor() {
    void this.charger();
    inject(DestroyRef).onDestroy(() => this.afficher(null));
  }

  ngAfterViewInit(): void {
    const ctx = this.contexte();
    if (ctx) {
      ctx.lineWidth = 3;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.strokeStyle = '#10235a';
    }
  }

  private async charger(): Promise<void> {
    await this.action.executer(async () => {
      const etat = await this.api.etatSignature();
      this.etat.set(etat);
      this.afficher(etat.presente ? await this.api.maSignature() : null);
    });
  }

  // ---------- Dessin

  protected debut(e: PointerEvent): void {
    const ctx = this.contexte();
    if (!ctx) {
      return;
    }
    this.crayon = true;
    (e.target as Element).setPointerCapture?.(e.pointerId);
    const { x, y } = this.position(e);
    ctx.beginPath();
    ctx.moveTo(x, y);
    this.etendre(x, y);
  }

  protected trace(e: PointerEvent): void {
    const ctx = this.contexte();
    if (!this.crayon || !ctx) {
      return;
    }
    const { x, y } = this.position(e);
    ctx.lineTo(x, y);
    ctx.stroke();
    this.etendre(x, y);
    this.dessinee.set(true);
  }

  protected fin(): void {
    this.crayon = false;
  }

  protected effacer(): void {
    const c = this.zone().nativeElement;
    this.contexte()?.clearRect(0, 0, c.width, c.height);
    this.bornes = { x1: Infinity, y1: Infinity, x2: -Infinity, y2: -Infinity };
    this.dessinee.set(false);
  }

  /** Le tracé seul, recadré : il remplit la case du bulletin quelle que soit sa place dans le cadre. */
  protected async enregistrerDessin(): Promise<void> {
    const source = this.zone().nativeElement;
    const b = this.bornes;
    const x = Math.max(0, Math.floor(b.x1 - MARGE));
    const y = Math.max(0, Math.floor(b.y1 - MARGE));
    const l = Math.min(source.width, Math.ceil(b.x2 + MARGE)) - x;
    const h = Math.min(source.height, Math.ceil(b.y2 + MARGE)) - y;
    if (l <= 0 || h <= 0) {
      return;
    }
    const recadre = document.createElement('canvas');
    recadre.width = l;
    recadre.height = h;
    recadre.getContext('2d')?.drawImage(source, x, y, l, h, 0, 0, l, h);
    const png = await new Promise<Blob | null>((ok) => recadre.toBlob(ok, 'image/png'));
    if (png) {
      await this.envoyer(png);
      this.effacer();
    }
  }

  protected async deposerFichier(e: Event): Promise<void> {
    const champ = e.target as HTMLInputElement;
    const fichier = champ.files?.[0];
    champ.value = '';
    if (fichier) {
      await this.envoyer(fichier);
    }
  }

  private async envoyer(image: Blob): Promise<void> {
    this.message.set(null);
    const etat = await this.action.executer(() => this.api.deposerSignature(image), "La signature n'a pas pu être enregistrée.");
    if (etat) {
      this.etat.set(etat);
      this.afficher(await this.api.maSignature().catch(() => null));
      this.message.set('Signature enregistrée. Elle apparaîtra sur les bulletins générés après vos visas.');
    }
  }

  protected async retirer(): Promise<void> {
    if (!window.confirm('Retirer votre signature ? Vos visas s’imprimeront « Visé », sans signature.')) {
      return;
    }
    const etat = await this.action.executer(() => this.api.retirerSignature());
    if (etat) {
      this.etat.set(etat);
      this.afficher(null);
      this.message.set('Signature retirée.');
    }
  }

  // ----------

  private contexte(): CanvasRenderingContext2D | null {
    try {
      return this.zone().nativeElement.getContext('2d');
    } catch {
      return null; // pas de canvas (tests)
    }
  }

  /** Position dans le repère du canvas (600 × 200), quelle que soit sa taille à l'écran. */
  private position(e: PointerEvent): { x: number; y: number } {
    const c = this.zone().nativeElement;
    const r = c.getBoundingClientRect();
    const ex = r.width ? c.width / r.width : 1;
    const ey = r.height ? c.height / r.height : 1;
    return { x: (e.clientX - r.left) * ex, y: (e.clientY - r.top) * ey };
  }

  private etendre(x: number, y: number): void {
    const b = this.bornes;
    this.bornes = { x1: Math.min(b.x1, x), y1: Math.min(b.y1, y), x2: Math.max(b.x2, x), y2: Math.max(b.y2, y) };
  }

  private afficher(blob: Blob | null): void {
    const ancienne = this.actuelle();
    if (ancienne) {
      URL.revokeObjectURL(ancienne);
    }
    this.actuelle.set(blob ? URL.createObjectURL(blob) : null);
  }
}
