import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SignaturePage } from '../pages/signature.page';
import { attendre, configurer } from '../testing/outils-test';
import { PhotoIdentiteComponent, VignetteComponent } from './photo-identite.component';
import { PhotosApi, reduirePhoto } from './photos-api.service';

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement | undefined {
  f.detectChanges();
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle));
}

/** Simule le choix d'un fichier dans un champ de type file. */
function choisirFichier<T>(f: ComponentFixture<T>, fichier: File): void {
  const champ = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=file]')!;
  Object.defineProperty(champ, 'files', { value: [fichier], configurable: true });
  champ.dispatchEvent(new Event('change'));
}

const JPEG = new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xe0])], { type: 'image/jpeg' });

describe('Photos d’identité, cartes et signature (v0.44)', () => {
  let http: HttpTestingController;
  let urls: number;

  beforeEach(() => {
    ({ http } = configurer());
    urls = 0;
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:${++urls}`);
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  it('photo d’un élève : aucune, puis déposée depuis l’appareil photo, puis retirée', async () => {
    const f = TestBed.createComponent(PhotoIdentiteComponent);
    f.componentRef.setInput('chemin', '/eleves/el1/photo');
    f.componentRef.setInput('nom', 'Awa OUEDRAOGO');
    f.componentRef.setInput('modifiable', true);
    const changements: boolean[] = [];
    f.componentInstance.change.subscribe((v) => changements.push(v));
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/eleves/el1/photo').flush(null, { status: 404, statusText: 'Not Found' });
    await attendre();
    expect(texte(f)).toContain('Pas de photo');
    expect(texte(f)).toContain('Ajouter une photo');
    const champ = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=file]')!;
    expect(champ.getAttribute('capture')).toBe('user'); // caméra frontale du téléphone
    expect(champ.accept).toBe('image/jpeg,image/png');

    choisirFichier(f, new File([JPEG], 'awa.jpg', { type: 'image/jpeg' }));
    await attendre();
    const depot = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/eleves/el1/photo');
    expect(depot.request.body instanceof FormData).toBe(true);
    expect((depot.request.body as FormData).get('fichier')).toBeInstanceOf(Blob);
    depot.flush(null);
    await attendre();
    http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/eleves/el1/photo').flush(JPEG);
    await attendre();
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelector('img')?.getAttribute('alt')).toBe('Photo de Awa OUEDRAOGO');
    expect(texte(f)).toContain('Changer la photo');
    expect(changements).toEqual([true]);

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    bouton(f, 'Retirer')!.click();
    await attendre();
    http.expectOne((r) => r.method === 'DELETE' && r.url === '/api/v1/eleves/el1/photo').flush(null);
    await attendre();
    expect(texte(f)).toContain('Pas de photo');
    expect(changements).toEqual([true, false]);
  });

  it('photo refusée par le serveur : le message s’affiche', async () => {
    const f = TestBed.createComponent(PhotoIdentiteComponent);
    f.componentRef.setInput('chemin', '/personnes/u9/photo');
    f.componentRef.setInput('modifiable', true);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/personnes/u9/photo').flush(null, { status: 404, statusText: 'Not Found' });
    await attendre();
    choisirFichier(f, new File(['pas une image'], 'x.png', { type: 'image/png' }));
    await attendre();
    http
      .expectOne((r) => r.method === 'POST')
      .flush({ detail: 'Image illisible : déposez une photo JPEG ou PNG' }, { status: 400, statusText: 'Bad Request' });
    await attendre();
    expect(texte(f)).toContain('Image illisible');
  });

  it('lecture seule : pas de bouton ; miniature demandée seulement si la photo existe', async () => {
    const f = TestBed.createComponent(PhotoIdentiteComponent);
    f.componentRef.setInput('chemin', '/eleves/el2/photo');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/eleves/el2/photo').flush(JPEG);
    await attendre();
    expect(bouton(f, 'Retirer')).toBeUndefined();
    expect((f.nativeElement as HTMLElement).querySelector('input[type=file]')).toBeNull();

    const v = TestBed.createComponent(VignetteComponent);
    v.componentRef.setInput('initiales', 'OA');
    v.detectChanges();
    await attendre();
    expect(texte(v)).toContain('OA'); // pas de photo connue : aucune requête
    v.componentRef.setInput('chemin', '/eleves/el2/photo');
    v.detectChanges();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/eleves/el2/photo');
    expect(req.request.params.get('miniature')).toBe('true');
    req.flush(JPEG);
    await attendre();
    v.detectChanges();
    expect((v.nativeElement as HTMLElement).querySelector('img.vignette')).not.toBeNull();
  });

  it('cartes : classe, élève, personnel (une personne ou tous)', async () => {
    const api = TestBed.inject(PhotosApi);
    const pdf = new Blob(['%PDF'], { type: 'application/pdf' });
    const promesses = [api.cartesClasse('c1'), api.carteEleve('el1'), api.cartesPersonnel(), api.cartesPersonnel('u7')];
    http.expectOne('/api/v1/classes/c1/cartes').flush(pdf);
    http.expectOne('/api/v1/eleves/el1/carte').flush(pdf);
    const personnel = http.match((r) => r.url === '/api/v1/personnes/cartes');
    expect(personnel.map((r) => r.request.params.get('utilisateur'))).toEqual([null, 'u7']);
    personnel.forEach((r) => r.flush(pdf));
    for (const p of promesses) {
      expect(await p).toBeInstanceOf(Blob);
    }
    const avec = api.elevesAvecPhoto('c1');
    http.expectOne('/api/v1/classes/c1/photos').flush(['el1']);
    expect(await avec).toEqual(['el1']);
  });

  it('réduction côté navigateur : sans canvas, le fichier part tel quel', async () => {
    const fichier = new File([JPEG], 'p.jpg', { type: 'image/jpeg' });
    expect(await reduirePhoto(fichier)).toBe(fichier);
  });

  it('ma signature : déposée depuis une photo, puis retirée', async () => {
    const f = TestBed.createComponent(SignaturePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/moi/signature/etat').flush({ presente: false, modifieeLe: null });
    await attendre();
    let t = texte(f);
    expect(t).toContain('Aucune signature');
    expect(bouton(f, 'Enregistrer cette signature')?.disabled).toBe(true); // rien de dessiné

    choisirFichier(f, new File([JPEG], 'signature.jpg', { type: 'image/jpeg' }));
    await attendre();
    const depot = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/moi/signature');
    expect((depot.request.body as FormData).get('fichier')).toBeInstanceOf(Blob);
    depot.flush({ presente: true, modifieeLe: '2026-03-31T08:00:00Z' });
    await attendre();
    http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/moi/signature').flush(new Blob(['png'], { type: 'image/png' }));
    await attendre();
    t = texte(f);
    expect(t).toContain('Signature enregistrée');
    expect((f.nativeElement as HTMLElement).querySelector('img.actuelle')).not.toBeNull();

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    bouton(f, 'Retirer ma signature')!.click();
    await attendre();
    http.expectOne((r) => r.method === 'DELETE' && r.url === '/api/v1/moi/signature').flush({ presente: false, modifieeLe: null });
    await attendre();
    expect(texte(f)).toContain('Signature retirée');
    expect((f.nativeElement as HTMLElement).querySelector('img.actuelle')).toBeNull();
  });
});
