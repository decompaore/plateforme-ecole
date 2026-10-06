package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.ArticleVue;
import bf.edutech.plateforme.ateliers.Vues.AtelierResumeVue;
import bf.edutech.plateforme.ateliers.Vues.AtelierVue;
import bf.edutech.plateforme.ateliers.Vues.EquipementVue;
import bf.edutech.plateforme.ateliers.Vues.InventaireVue;
import bf.edutech.plateforme.ateliers.Vues.LigneEquipementVue;
import bf.edutech.plateforme.ateliers.Vues.LigneMatiereVue;
import bf.edutech.plateforme.ateliers.Vues.LigneStockVue;
import bf.edutech.plateforme.ateliers.Vues.MouvementVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.BesoinResumeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.BesoinVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.CampagneVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.CommandeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.FiliereConsolideeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LigneBesoinVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LigneCommandeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LigneConsolideeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LigneLivraisonVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LivraisonVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.PartAtelierVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.socle.export.ExportTableaux;
import bf.edutech.plateforme.socle.export.ExportTableaux.Format;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;
import bf.edutech.plateforme.socle.export.Tableau.Image;
import bf.edutech.plateforme.socle.export.Tableau.Montant;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;

/**
 * Exports Excel et PDF des pages du chef des travaux et des responsables d'atelier. Chaque export
 * passe par le service de lecture correspondant : il applique exactement les mêmes droits.
 */
@Service
public class ExportsAteliers {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    private static final Map<EtatEquipement, String> ETATS = Map.of(EtatEquipement.BON, "En service",
            EtatEquipement.EN_PANNE, "En panne", EtatEquipement.MANQUANT, "Manquant", EtatEquipement.REFORME, "Réformé");
    private static final Map<TypeMouvement, String> MOUVEMENTS = Map.of(TypeMouvement.ENTREE, "Entrée",
            TypeMouvement.SORTIE, "Sortie", TypeMouvement.INVENTAIRE, "Correction d'inventaire");
    private static final Map<StatutBesoin, String> STATUTS_BESOIN = Map.of(StatutBesoin.BROUILLON, "En préparation",
            StatutBesoin.TRANSMIS, "Transmis", StatutBesoin.VALIDE, "Validé");
    private static final Map<PasseePar, String> PASSEE_PAR = Map.of(PasseePar.DIRECTION_REGIONALE,
            "Direction régionale", PasseePar.ETABLISSEMENT, "Établissement");

    private final AteliersService ateliers;
    private final CatalogueService catalogue;
    private final EquipementsService equipements;
    private final StockService stock;
    private final InventairesService inventaires;
    private final BesoinsService besoins;
    private final CommandesService commandes;
    private final NotificationsService notifications;

    ExportsAteliers(AteliersService ateliers, CatalogueService catalogue, EquipementsService equipements, StockService stock,
            InventairesService inventaires, BesoinsService besoins, CommandesService commandes,
            NotificationsService notifications) {
        this.ateliers = ateliers;
        this.catalogue = catalogue;
        this.equipements = equipements;
        this.stock = stock;
        this.inventaires = inventaires;
        this.besoins = besoins;
        this.commandes = commandes;
        this.notifications = notifications;
    }

    private ResponseEntity<byte[]> fichier(String nom, Format format, Tableau... tableaux) {
        return ExportTableaux.reponse(notifications.nomEtablissement(), nom + "-" + LocalDate.now(FUSEAU), format, tableaux);
    }

    private static String jour(LocalDate d) {
        return d == null ? "" : d.format(JOUR);
    }

    private static String jour(Instant i) {
        return i == null ? "" : LocalDate.ofInstant(i, FUSEAU).format(JOUR);
    }

    private static String nom(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    // ------------------------------------------------------------------ chef des travaux

    @Transactional
    public ResponseEntity<byte[]> tableauDeBord(Format format) {
        List<AtelierResumeVue> liste = ateliers.lister();
        Tableau t = new Tableau("Ateliers", "Tableau de bord des ateliers").sousTitre(liste.size() + " atelier(s)")
                .colonnes(Colonne.texte("Code", 4), Colonne.texte("Atelier", 12), Colonne.texte("Filières", 8),
                        Colonne.texte("Emplacement", 8), Colonne.nombre("Postes", 4), Colonne.texte("Responsable", 10),
                        Colonne.texte("Fin du mandat", 6), Colonne.nombre("Équipements", 5),
                        Colonne.nombre("Matières", 5), Colonne.texte("À surveiller", 16));
        for (AtelierResumeVue a : liste) {
            t.ligne(a.code(), a.nom() + (a.ouvert() ? "" : " (fermé)"),
                    a.filieres().stream().map(f -> f.code()).collect(Collectors.joining(", ")), a.emplacement(),
                    a.postes() == null ? null : a.postes().intValue(),
                    a.responsable() == null ? "aucun" : a.responsable().enseignant(),
                    a.responsable() == null || a.responsable().finPrevue() == null ? "" : jour(a.responsable().finPrevue()),
                    a.equipements(), a.articles(), String.join(" ; ", alertes(a.alertes())));
        }
        return fichier("ateliers", format, t);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> catalogue(Format format) {
        List<ArticleVue> liste = catalogue.lister();
        Tableau t = new Tableau("Catalogue", "Catalogue des prix : matière d'œuvre et équipements")
                .sousTitre(liste.size() + " article(s) · prix de référence en francs CFA")
                .colonnes(Colonne.image("Photo", 4), Colonne.texte("Code", 6), Colonne.texte("Désignation", 14),
                        Colonne.texte("Unité", 5), Colonne.texte("Spécialité", 5), Colonne.texte("Spécifications", 18),
                        Colonne.texte("Normes", 8), Colonne.montant("Prix", 6), Colonne.texte("Prix du", 5));
        NatureArticle courante = null;
        for (ArticleVue a : liste) {
            if (a.nature() != courante) {
                courante = a.nature();
                t.intertitre(courante == NatureArticle.MATIERE_OEUVRE ? "Matière d'œuvre" : "Équipements");
            }
            t.ligne(format == Format.PDF ? photo(a.id(), a.photo()) : (a.photo() ? "oui" : ""), a.code(),
                    a.designation() + (a.actif() ? "" : " (retiré)"), a.unite(), a.filiereCode(), a.specifications(),
                    a.normes(), a.prixReference() == null ? null : new Montant(a.prixReference()), jour(a.prixModifieLe()));
        }
        return fichier("catalogue-des-prix", format, t);
    }

    /** Stock de matière d'œuvre et parc d'équipements, regroupés par filière. */
    @Transactional
    public ResponseEntity<byte[]> stockParFiliere(Format format) {
        List<AtelierResumeVue> liste = ateliers.lister();
        Map<UUID, String> filieres = besoins.libellesFilieres(liste.stream().map(AtelierResumeVue::id).toList());
        Map<String, Map<String, Object[]>> matieres = new TreeMap<>();
        Map<String, Map<String, int[]>> parc = new TreeMap<>();
        long valeurTotale = 0;
        for (AtelierResumeVue a : liste) {
            String filiere = filieres.getOrDefault(a.id(), "Sans filière");
            for (LigneStockVue l : stock.stock(a.id())) {
                Object[] cumul = matieres.computeIfAbsent(filiere, k -> new TreeMap<>())
                        .computeIfAbsent(l.designation(), k -> new Object[] { l.unite(), BigDecimal.ZERO, 0L, new ArrayList<String>() });
                cumul[1] = ((BigDecimal) cumul[1]).add(l.quantite());
                long valeur = BesoinsService.montant(l.quantite(), l.prixReference());
                cumul[2] = (Long) cumul[2] + valeur;
                valeurTotale += valeur;
                @SuppressWarnings("unchecked")
                List<String> ou = (List<String>) cumul[3];
                ou.add(a.code() + " : " + ExportTableauxFormat.nombre(l.quantite()));
            }
            for (EquipementVue e : equipements.lister(a.id())) {
                int[] c = parc.computeIfAbsent(filiere, k -> new TreeMap<>()).computeIfAbsent(e.designation(), k -> new int[4]);
                c[e.etat().ordinal()]++;
            }
        }
        Tableau tm = new Tableau("Matière d'œuvre", "Stock de matière d'œuvre par filière")
                .colonnes(Colonne.texte("Désignation", 16), Colonne.texte("Unité", 6), Colonne.nombre("Quantité", 6),
                        Colonne.texte("Répartition par atelier", 16), Colonne.montant("Valeur", 7));
        for (var f : matieres.entrySet()) {
            tm.intertitre(f.getKey());
            for (var l : f.getValue().entrySet()) {
                Object[] c = l.getValue();
                tm.ligne(l.getKey(), c[0], c[1], ((List<?>) c[3]).stream().map(String::valueOf).collect(Collectors.joining(", ")), new Montant((Long) c[2]));
            }
        }
        tm.total("TOTAL", null, null, null, new Montant(valeurTotale)).note("Valeur au prix de référence du catalogue.");
        Tableau te = new Tableau("Équipements", "Équipements par filière et par état")
                .colonnes(Colonne.texte("Désignation", 18), Colonne.nombre("En service", 6), Colonne.nombre("En panne", 6),
                        Colonne.nombre("Manquants", 6), Colonne.nombre("Réformés", 6), Colonne.nombre("Total", 6));
        for (var f : parc.entrySet()) {
            te.intertitre(f.getKey());
            for (var l : f.getValue().entrySet()) {
                int[] c = l.getValue();
                te.ligne(l.getKey(), c[0], c[1], c[2], c[3], c[0] + c[1] + c[2] + c[3]);
            }
        }
        return fichier("stock-par-filiere", format, tm, te);
    }

    // ------------------------------------------------------------------ responsable d'atelier

    @Transactional
    public ResponseEntity<byte[]> equipements(UUID atelierId, Format format) {
        AtelierVue a = ateliers.fiche(atelierId);
        List<EquipementVue> liste = equipements.lister(atelierId);
        Tableau t = new Tableau("Équipements", "Inventaire des équipements — " + a.code() + " · " + a.nom())
                .sousTitre(responsable(a) + " · " + liste.size() + " équipement(s)")
                .colonnes(Colonne.texte("N° d'inventaire", 7), Colonne.texte("Désignation", 14), Colonne.texte("Marque", 6),
                        Colonne.texte("N° de série", 6), Colonne.texte("Acquis le", 5), Colonne.montant("Valeur", 6),
                        Colonne.texte("État", 5), Colonne.texte("Panne en cours / observations", 16));
        long valeur = 0;
        for (EquipementVue e : liste) {
            valeur += e.valeur() == null ? 0 : e.valeur();
            t.ligne(e.numeroInventaire(), e.designation(), e.marque(), e.numeroSerie(), jour(e.dateAcquisition()),
                    e.valeur() == null ? null : new Montant(e.valeur()), ETATS.get(e.etat()),
                    e.panneOuverte() != null ? "Panne du " + jour(e.panneOuverte().signaleeLe()) + " : " + e.panneOuverte().description()
                            : e.observations());
        }
        t.total("TOTAL", null, null, null, null, new Montant(valeur), null, null)
                .signatures("Le responsable de l'atelier", "Le chef des travaux");
        return fichier("equipements-" + nom(a.code()), format, t);
    }

    @Transactional
    public ResponseEntity<byte[]> stock(UUID atelierId, Format format) {
        AtelierVue a = ateliers.fiche(atelierId);
        List<LigneStockVue> lignes = stock.stock(atelierId);
        Tableau ts = new Tableau("Stock", "Stock de matière d'œuvre — " + a.code() + " · " + a.nom())
                .sousTitre(responsable(a))
                .colonnes(Colonne.texte("Code", 6), Colonne.texte("Désignation", 16), Colonne.texte("Unité", 6),
                        Colonne.nombre("Stock", 6), Colonne.nombre("Seuil d'alerte", 6), Colonne.texte("À recompléter", 6),
                        Colonne.montant("Prix", 6), Colonne.montant("Valeur", 7));
        long valeur = 0;
        for (LigneStockVue l : lignes) {
            long v = BesoinsService.montant(l.quantite(), l.prixReference());
            valeur += v;
            ts.ligne(l.code(), l.designation(), l.unite(), l.quantite(), l.seuilAlerte(), l.sousLeSeuil() ? "oui" : "",
                    l.prixReference() == null ? null : new Montant(l.prixReference()), new Montant(v));
        }
        ts.total("TOTAL", null, null, null, null, null, null, new Montant(valeur));
        List<MouvementVue> mvts = stock.mouvements(atelierId, null);
        Tableau tm = new Tableau("Mouvements", "Mouvements de matière d'œuvre — " + a.code())
                .colonnes(Colonne.texte("Date", 5), Colonne.texte("Matière d'œuvre", 14), Colonne.texte("Mouvement", 7),
                        Colonne.nombre("Quantité", 5), Colonne.nombre("Stock après", 5), Colonne.texte("Motif", 14),
                        Colonne.texte("Par", 8));
        for (MouvementVue m : mvts) {
            tm.ligne(jour(m.date()), m.article(), MOUVEMENTS.get(m.type()), m.quantite(), m.stockApres(), m.motif(), m.auteur());
        }
        return fichier("stock-" + nom(a.code()), format, ts, tm);
    }

    /** Feuille d'inventaire : à imprimer pour compter (colonnes vides tant que l'inventaire est en cours). */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> inventaire(UUID id, Format format) {
        InventaireVue inv = inventaires.fiche(id);
        String sous = "Ouvert le " + jour(inv.ouvertLe()) + (inv.ouvertPar() == null ? "" : " par " + inv.ouvertPar())
                + (inv.closLe() == null ? " · en cours" : " · clos le " + jour(inv.closLe()));
        Tableau tm = new Tableau("Matière d'œuvre", "Inventaire « " + inv.libelle() + " » — " + inv.atelierCode() + " · "
                + inv.atelierNom() + " — matière d'œuvre").sousTitre(sous)
                .colonnes(Colonne.texte("Code", 6), Colonne.texte("Désignation", 16), Colonne.texte("Unité", 6),
                        Colonne.nombre("Théorique", 6), Colonne.nombre("Constaté", 6), Colonne.nombre("Écart", 6));
        for (LigneMatiereVue l : inv.matieres()) {
            tm.ligne(l.code(), l.designation(), l.unite(), l.quantiteTheorique(), l.quantiteConstatee(), l.ecart());
        }
        Tableau te = new Tableau("Équipements", "Inventaire « " + inv.libelle() + " » — " + inv.atelierCode() + " — équipements")
                .sousTitre(sous)
                .colonnes(Colonne.texte("N° d'inventaire", 7), Colonne.texte("Désignation", 14),
                        Colonne.texte("État enregistré", 6), Colonne.texte("État constaté", 6), Colonne.texte("Observation", 14));
        for (LigneEquipementVue l : inv.equipements()) {
            te.ligne(l.numeroInventaire(), l.designation(), ETATS.get(l.etatTheorique()),
                    l.etatConstate() == null ? "" : ETATS.get(l.etatConstate()), l.observation());
        }
        if (inv.observations() != null) {
            te.note("Observations : " + inv.observations());
        }
        te.signatures("Le responsable de l'atelier", "Le chef des travaux");
        return fichier("inventaire-" + nom(inv.atelierCode() + "-" + inv.libelle()), format, tm, te);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> besoin(UUID id, Format format) {
        BesoinVue b = besoins.besoin(id);
        Tableau t = new Tableau("Besoins", "Besoins de l'atelier " + b.atelierCode() + " · " + b.atelierNom())
                .sousTitre(b.campagne() + " · " + STATUTS_BESOIN.get(b.statut())
                        + (b.transmisLe() == null ? "" : " · transmis le " + jour(b.transmisLe()))
                        + (b.valideLe() == null ? "" : " · validé le " + jour(b.valideLe())))
                .colonnes(Colonne.image("Photo", 4), Colonne.texte("Désignation", 12), Colonne.texte("Spécifications et normes", 16),
                        Colonne.texte("Unité", 5), Colonne.nombre("Demandé", 5), Colonne.nombre("Retenu", 5),
                        Colonne.montant("Prix", 6), Colonne.montant("Montant", 7), Colonne.texte("Justification", 12));
        for (LigneBesoinVue l : b.lignes()) {
            t.ligne(format == Format.PDF ? photo(l.articleId(), l.photo()) : (l.photo() ? "oui" : ""), l.designation(),
                    specifications(l.specifications(), l.normes()), l.unite(), l.quantiteDemandee(), l.quantiteRetenue(),
                    l.prixUnitaire() == null ? null : new Montant(l.prixUnitaire()), new Montant(l.montant()),
                    l.justification() == null ? l.proposePar() : l.justification() + (l.proposePar() == null ? "" : " (" + l.proposePar() + ")"));
        }
        t.total("TOTAL", null, null, null, null, null, null, new Montant(b.montant()), null)
                .signatures("Le responsable de l'atelier", "Le chef des travaux");
        return fichier("besoins-" + nom(b.atelierCode()), format, t);
    }

    // ------------------------------------------------------------------ circuit des besoins

    /** État des besoins par filière, à transmettre à la direction régionale. */
    @Transactional
    public ResponseEntity<byte[]> etatCampagne(UUID id, Format format) {
        CampagneVue c = besoins.fiche(id);
        String sous = (c.transmiseLe() == null ? "Besoins validés au " + jour(LocalDate.now(FUSEAU)) : "Transmis le " + jour(c.transmiseLe()))
                + " · prix du catalogue convenu avec l'intendant";
        Tableau t = new Tableau("État des besoins", "État des besoins en matière d'œuvre et en équipements — " + c.libelle())
                .sousTitre(sous)
                .colonnes(Colonne.image("Photo", 4), Colonne.texte("Désignation", 13), Colonne.texte("Spécifications et normes", 18),
                        Colonne.texte("Unité", 5), Colonne.nombre("Quantité", 5), Colonne.montant("Prix unitaire", 6),
                        Colonne.montant("Montant", 7));
        Map<UUID, ArticleVue> articles = catalogue.lister().stream().collect(Collectors.toMap(ArticleVue::id, a -> a));
        for (FiliereConsolideeVue f : c.filieres()) {
            t.intertitre("Filière " + f.filiere() + " — " + ExportTableauxFormat.fcfa(f.montant()));
            for (LigneConsolideeVue l : f.lignes()) {
                ArticleVue a = articles.get(l.articleId());
                t.ligne(format == Format.PDF ? photo(l.articleId(), a != null && a.photo()) : (a != null && a.photo() ? "oui" : ""),
                        l.designation(), a == null ? null : specifications(a.specifications(), a.normes()), l.unite(), l.quantite(),
                        l.prixUnitaire() == null ? null : new Montant(l.prixUnitaire()), new Montant(l.montant()));
            }
        }
        t.total("TOTAL", null, null, null, null, null, new Montant(c.montant()))
                .note("Arrêté le présent état à la somme de " + ExportTableauxFormat.fcfa(c.montant()) + ".")
                .signatures("Le chef des travaux", "L'intendant", "Le proviseur");
        Tableau recap = new Tableau("Récapitulatif", "Récapitulatif par article — " + c.libelle())
                .colonnes(Colonne.texte("Code", 6), Colonne.texte("Désignation", 16), Colonne.texte("Unité", 5),
                        Colonne.nombre("Quantité", 5), Colonne.montant("Prix unitaire", 6), Colonne.montant("Montant", 7),
                        Colonne.nombre("Commandé", 5), Colonne.nombre("Livré conforme", 6));
        for (LigneConsolideeVue l : c.totaux()) {
            recap.ligne(l.code(), l.designation(), l.unite(), l.quantite(), l.prixUnitaire() == null ? null : new Montant(l.prixUnitaire()),
                    new Montant(l.montant()), l.commandee(), l.livree());
        }
        recap.total("TOTAL", null, null, null, null, new Montant(c.montant()), null, null);
        Tableau ateliersT = new Tableau("Par atelier", "Besoins par atelier — " + c.libelle())
                .colonnes(Colonne.texte("Atelier", 12), Colonne.texte("Filières", 8), Colonne.texte("Responsable", 10),
                        Colonne.texte("Statut", 6), Colonne.nombre("Articles", 5), Colonne.montant("Montant", 7));
        for (BesoinResumeVue b : c.besoins()) {
            ateliersT.ligne(b.atelierCode() + " · " + b.atelierNom(), b.filieres().stream().map(f -> f.code()).collect(Collectors.joining(", ")),
                    b.responsable(), STATUTS_BESOIN.get(b.statut()), b.lignes(), new Montant(b.montant()));
        }
        return fichier("etat-des-besoins-" + nom(c.libelle()), format, t, recap, ateliersT);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> commande(UUID id, Format format) {
        CommandeVue co = commandes.fiche(id);
        Tableau t = new Tableau("Commande", "Bon de commande n° " + co.reference())
                .sousTitre("Fournisseur : " + co.fournisseur() + " · passée par : " + PASSEE_PAR.get(co.passeePar()) + " · le "
                        + jour(co.dateCommande()) + " · " + co.campagne())
                .colonnes(Colonne.texte("Code", 6), Colonne.texte("Désignation", 16), Colonne.texte("Unité", 5),
                        Colonne.nombre("Quantité", 5), Colonne.montant("Prix unitaire", 6), Colonne.montant("Montant", 7),
                        Colonne.nombre("Livré conforme", 6), Colonne.nombre("Reste à livrer", 6));
        for (LigneCommandeVue l : co.lignes()) {
            t.ligne(l.code(), l.designation(), l.unite(), l.quantite(), new Montant(l.prixUnitaire()), new Montant(l.montant()),
                    l.conforme(), l.reste());
        }
        t.total("TOTAL", null, null, null, null, new Montant(co.montant()), null, null);
        if (co.observations() != null) {
            t.note("Observations : " + co.observations());
        }
        t.signatures("Le chef des travaux", "L'intendant", "Le proviseur");
        return fichier("commande-" + nom(co.reference()), format, t);
    }

    /** Procès-verbal de réception (contrôle des quantités et de la conformité) et de répartition entre les ateliers. */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> livraison(UUID id, Format format) {
        LivraisonVue l = commandes.livraison(id);
        String sous = "Commande " + l.commandeReference() + " · " + l.fournisseur() + " · reçue le " + jour(l.dateReception())
                + (l.bonLivraison() == null ? "" : " · bon de livraison " + l.bonLivraison())
                + (l.recuePar() == null ? "" : " · par " + l.recuePar());
        Tableau tr = new Tableau("Réception", "Procès-verbal de réception").sousTitre(sous)
                .colonnes(Colonne.texte("Code", 6), Colonne.texte("Désignation", 16), Colonne.texte("Unité", 5),
                        Colonne.nombre("Reçu", 5), Colonne.nombre("Conforme", 5), Colonne.nombre("Refusé", 5),
                        Colonne.texte("Motif du refus (spécifications, normes, état)", 18));
        for (LigneLivraisonVue r : l.lignes()) {
            tr.ligne(r.code(), r.designation(), r.unite(), r.recue(), r.conforme(), r.recue().subtract(r.conforme()),
                    r.motifNonConformite());
        }
        if (l.observations() != null) {
            tr.note("Observations : " + l.observations());
        }
        tr.note("Réception faite après vérification des quantités et de la conformité aux spécifications et normes "
                + "définies par les enseignants de la spécialité.");
        tr.signatures("Le chef des travaux", "Le fournisseur", "L'intendant");
        Tableau tp = new Tableau("Répartition", "Procès-verbal de répartition entre les ateliers").sousTitre(sous
                + (l.repartieLe() == null ? " · répartition non validée" : " · répartie le " + jour(l.repartieLe())))
                .colonnes(Colonne.texte("Désignation", 16), Colonne.texte("Unité", 5), Colonne.texte("Atelier", 10),
                        Colonne.nombre("Besoin retenu", 6), Colonne.nombre("Quantité attribuée", 6));
        for (LigneLivraisonVue r : l.lignes()) {
            for (PartAtelierVue p : r.repartition()) {
                if (p.quantite() != null || p.retenu().signum() > 0) {
                    tp.ligne(r.designation(), r.unite(), p.atelierCode(), p.retenu(), p.quantite());
                }
            }
        }
        tp.signatures("Le chef des travaux", "Les responsables d'atelier");
        return fichier("reception-" + nom(l.commandeReference()), format, tr, tp);
    }

    // ------------------------------------------------------------------ outils

    private Object photo(UUID articleId, boolean presente) {
        if (!presente) {
            return null;
        }
        try {
            CatalogueService.Photo p = catalogue.photo(articleId);
            return p.type().equals("image/webp") ? null : new Image(p.contenu());
        } catch (RessourceIntrouvableException e) {
            return null;
        }
    }

    private static String specifications(String specifications, String normes) {
        List<String> parts = new ArrayList<>();
        if (specifications != null) {
            parts.add(specifications);
        }
        if (normes != null) {
            parts.add("Normes : " + normes);
        }
        return String.join("\n", parts);
    }

    private static String responsable(AtelierVue a) {
        return "Responsable : " + (a.responsable() == null ? "aucun" : a.responsable().enseignant());
    }

    private static List<String> alertes(Vues.AlertesAtelier a) {
        List<String> t = new ArrayList<>();
        if (a.sansResponsable()) {
            t.add("sans responsable");
        }
        if (a.mandatEchu()) {
            t.add("mandat échu");
        } else if (a.mandatAEcheance()) {
            t.add("mandat bientôt à échéance");
        }
        if (a.equipementsEnPanne() > 0) {
            t.add(a.equipementsEnPanne() + " en panne");
        }
        if (a.equipementsManquants() > 0) {
            t.add(a.equipementsManquants() + " manquant(s)");
        }
        if (a.articlesSousSeuil() > 0) {
            t.add(a.articlesSousSeuil() + " matière(s) sous le seuil");
        }
        if (a.inventaireEnRetard()) {
            t.add("inventaire à faire");
        }
        return t;
    }

    /** Petits formats pour les textes des exports. */
    static final class ExportTableauxFormat {
        private ExportTableauxFormat() {
        }

        static String nombre(BigDecimal n) {
            BigDecimal b = n.stripTrailingZeros();
            return b.scale() <= 0 ? b.toBigInteger().toString() : b.toPlainString().replace('.', ',');
        }

        static String fcfa(long montant) {
            return java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(montant)
                    .replace(' ', ' ').replace(' ', ' ') + " FCFA";
        }
    }
}
