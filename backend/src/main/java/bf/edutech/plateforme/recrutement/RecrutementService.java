package bf.edutech.plateforme.recrutement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.LienParente;
import bf.edutech.plateforme.eleves.ModeAdmission;
import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.recrutement.Vues.CandidatureVue;
import bf.edutech.plateforme.recrutement.Vues.ClasseOffre;
import bf.edutech.plateforme.recrutement.Vues.ClassementOffre;
import bf.edutech.plateforme.recrutement.Vues.CommissionResume;
import bf.edutech.plateforme.recrutement.Vues.CommissionVue;
import bf.edutech.plateforme.recrutement.Vues.DemandeDeliberation;
import bf.edutech.plateforme.recrutement.Vues.DemandeDesistement;
import bf.edutech.plateforme.recrutement.Vues.DemandeInscription;
import bf.edutech.plateforme.recrutement.Vues.DemandeMembres;
import bf.edutech.plateforme.recrutement.Vues.DemandeOffres;
import bf.edutech.plateforme.recrutement.Vues.DemandeRecevabilite;
import bf.edutech.plateforme.recrutement.Vues.DonneesCandidature;
import bf.edutech.plateforme.recrutement.Vues.DonneesCommission;
import bf.edutech.plateforme.recrutement.Vues.Droits;
import bf.edutech.plateforme.recrutement.Vues.LigneClassement;
import bf.edutech.plateforme.recrutement.Vues.MembreCommissionVue;
import bf.edutech.plateforme.recrutement.Vues.MembreSaisi;
import bf.edutech.plateforme.recrutement.Vues.OffreSaisie;
import bf.edutech.plateforme.recrutement.Vues.OffreVue;
import bf.edutech.plateforme.recrutement.Vues.ResultatDesistement;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembreVue;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Commission de recrutement (v0.49) : complément d'effectif.
 * <ul>
 * <li>Le chef d'établissement crée la commission de l'année : membres proposés d'après le personnel (président,
 * vice-président, rapporteur, intendant, représentant de l'APE), places disponibles par niveau et filière (effectif
 * maximal des classes moins les élèves inscrits), critères (moyenne minimale, poids de la note d'examen, taille de la
 * liste d'attente).</li>
 * <li>Le secrétariat, le censeur ou le CPE enregistrent les candidatures ; le censeur ou le chef d'établissement
 * statuent sur leur recevabilité.</li>
 * <li>Classement au mérite (score = moyenne annuelle, ou moyenne pondérée avec la note d'examen ; à égalité, le plus
 * jeune, puis le premier déposé). Le président délibère : retenus dans la limite des places, liste d'attente, non
 * retenus (dont ceux sous la moyenne minimale) ; les résultats sont figés et le procès-verbal s'imprime.</li>
 * <li>L'intendance inscrit chaque retenu dans une classe du niveau et de la filière (dossier de l'élève et du
 * responsable créés, mode d'admission « Complément d'effectif ») ; un désistement repêche le premier de la liste
 * d'attente.</li>
 * </ul>
 */
@Service
public class RecrutementService {

    static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    static final String[] PERSONNEL_COMMISSION = { "ADMIN_ECOLE", "CENSEUR", "SECRETARIAT", "SURVEILLANT", "INTENDANT" };

    private final JdbcTemplate jdbc;
    private final Compteurs compteurs;
    private final MembresService membres;
    private final ElevesService eleves;
    private final InscriptionsService inscriptions;
    private final AuditService audit;

    RecrutementService(JdbcTemplate jdbc, Compteurs compteurs, MembresService membres, ElevesService eleves,
            InscriptionsService inscriptions, AuditService audit) {
        this.jdbc = jdbc;
        this.compteurs = compteurs;
        this.membres = membres;
        this.eleves = eleves;
        this.inscriptions = inscriptions;
        this.audit = audit;
    }

    // ================================================================== commissions

    @Transactional(readOnly = true)
    public List<CommissionResume> lister(UUID anneeId) {
        exigerLecture(null);
        return jdbc.query("""
                select c.id, c.numero, c.intitule, c.date_session, c.statut,
                       (select coalesce(sum(o.places), 0) from offre_commission o where o.commission_id = c.id),
                       (select count(*) from candidature x where x.commission_id = c.id),
                       (select count(*) from candidature x where x.commission_id = c.id and x.statut in ('RETENUE', 'INSCRITE')),
                       (select count(*) from candidature x where x.commission_id = c.id and x.statut = 'INSCRITE')
                from commission_recrutement c where c.annee_id = ? order by c.date_session desc, c.numero desc""",
                (l, n) -> new CommissionResume(l.getObject(1, UUID.class), l.getString(2), l.getString(3),
                        l.getObject(4, LocalDate.class), StatutCommission.valueOf(l.getString(5)), l.getInt(6),
                        l.getInt(7), l.getInt(8), l.getInt(9)),
                anneeId);
    }

    @Transactional
    public CommissionVue creer(UUID anneeId, DonneesCommission d) {
        exigerPresident();
        List<String> annee = jdbc.queryForList("select libelle from annee_scolaire where id = ?", String.class, anneeId);
        if (annee.isEmpty()) {
            throw new RessourceIntrouvableException("Année scolaire introuvable");
        }
        Parametres p = valider(d);
        UUID id = UUID.randomUUID();
        int an = LocalDate.now(FUSEAU).getYear();
        String numero = "CR-" + an + "-" + String.format("%02d", compteurs.suivant("COMMISSION_RECRUTEMENT_" + an));
        jdbc.update("""
                insert into commission_recrutement (tenant_id, id, annee_id, numero, intitule, date_session, lieu,
                    date_limite_depot, criteres, moyenne_minimale, poids_examen, taille_liste_attente, cree_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, anneeId, numero, p.intitule, Date.valueOf(p.dateSession), p.lieu,
                p.dateLimite == null ? null : Date.valueOf(p.dateLimite), p.criteres, p.moyenneMinimale, p.poidsExamen,
                p.listeAttente, UtilisateurConnecte.id());
        membresParDefaut(id);
        offresParDefaut(id, anneeId);
        audit.enregistrer("COMMISSION_RECRUTEMENT_CREEE", numero, Map.of("annee", annee.get(0)));
        return commission(id);
    }

    @Transactional(readOnly = true)
    public CommissionVue commission(UUID id) {
        Commission c = charger(id);
        exigerLecture(c.id);
        String delibereePar = c.delibereePar == null ? null : nomUtilisateur(c.delibereePar);
        List<MembreCommissionVue> lesMembres = jdbc.query("""
                select id, qualite, utilisateur_id, nom, fonction, present from membre_commission
                where commission_id = ? order by ordre""",
                (l, n) -> new MembreCommissionVue(l.getObject(1, UUID.class), Qualite.valueOf(l.getString(2)),
                        l.getObject(3, UUID.class), l.getString(4), l.getString(5), l.getBoolean(6)),
                id);
        return new CommissionVue(c.id, c.numero, c.anneeId, c.annee, c.intitule, c.dateSession, c.lieu,
                c.dateLimite, c.criteres, c.moyenneMinimale, c.poidsExamen, c.listeAttente, c.statut, c.observations,
                c.delibereeLe, delibereePar, c.closeLe, lesMembres, offres(c), droits(c));
    }

    @Transactional
    public CommissionVue modifier(UUID id, DonneesCommission d) {
        Commission c = charger(id);
        exigerPresident();
        exigerPreparation(c);
        Parametres p = valider(d);
        jdbc.update("""
                update commission_recrutement set intitule = ?, date_session = ?, lieu = ?, date_limite_depot = ?,
                    criteres = ?, moyenne_minimale = ?, poids_examen = ?, taille_liste_attente = ? where id = ?""",
                p.intitule, Date.valueOf(p.dateSession), p.lieu, p.dateLimite == null ? null : Date.valueOf(p.dateLimite),
                p.criteres, p.moyenneMinimale, p.poidsExamen, p.listeAttente, id);
        return commission(id);
    }

    @Transactional
    public CommissionVue definirMembres(UUID id, DemandeMembres demande) {
        Commission c = charger(id);
        exigerPresident();
        exigerPreparation(c);
        List<MembreSaisi> liste = demande == null || demande.membres() == null ? List.of() : demande.membres();
        Map<Qualite, Integer> nombres = new HashMap<>();
        for (MembreSaisi m : liste) {
            if (m.qualite() == null) {
                throw new IllegalArgumentException("Qualité obligatoire pour chaque membre");
            }
            nombres.merge(m.qualite(), 1, Integer::sum);
        }
        for (Qualite q : List.of(Qualite.PRESIDENT, Qualite.VICE_PRESIDENT, Qualite.RAPPORTEUR)) {
            if (nombres.getOrDefault(q, 0) > 1) {
                throw new RegleMetierException("QUALITE_EN_DOUBLE", "Un seul " + q.libelle().toLowerCase() + " par commission");
            }
        }
        if (nombres.getOrDefault(Qualite.PRESIDENT, 0) != 1) {
            throw new RegleMetierException("PRESIDENT_REQUIS", "La commission a un président");
        }
        Map<UUID, MembreVue> personnel = new HashMap<>();
        for (MembreVue m : membres.lister()) {
            if (m.actif()) {
                personnel.putIfAbsent(m.utilisateurId(), m);
            }
        }
        jdbc.update("delete from membre_commission where commission_id = ?", id);
        int ordre = 0;
        List<MembreSaisi> tries = new ArrayList<>(liste);
        tries.sort(Comparator.comparingInt(m -> m.qualite().ordinal()));
        for (MembreSaisi m : tries) {
            String nom = m.nom();
            if (m.utilisateurId() != null) {
                MembreVue p = personnel.get(m.utilisateurId());
                if (p == null) {
                    throw new IllegalArgumentException("Ce compte n'est pas membre du personnel de l'établissement");
                }
                if (nom == null || nom.isBlank()) {
                    nom = p.nom() + " " + p.prenoms();
                }
            }
            String n = texte(nom, 160, "Nom du membre");
            String f = texte(m.fonction(), 100, "Fonction du membre");
            jdbc.update("""
                    insert into membre_commission (tenant_id, id, commission_id, qualite, utilisateur_id, nom, fonction,
                        present, ordre) values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), id, m.qualite().name(), m.utilisateurId(), n, f,
                    m.present() == null || m.present(), ordre++);
        }
        return commission(id);
    }

    @Transactional
    public CommissionVue definirOffres(UUID id, DemandeOffres demande) {
        Commission c = charger(id);
        exigerPreparateur();
        exigerPreparation(c);
        Map<UUID, OffreVue> offres = new HashMap<>();
        for (OffreVue o : offres(c)) {
            offres.put(o.id(), o);
        }
        for (OffreSaisie s : demande == null || demande.offres() == null ? List.<OffreSaisie>of() : demande.offres()) {
            OffreVue o = offres.get(s.offreId());
            if (o == null) {
                throw new RessourceIntrouvableException("Offre introuvable dans cette commission");
            }
            int places = s.places() == null ? 0 : s.places();
            if (places < 0) {
                throw new IllegalArgumentException("Nombre de places négatif");
            }
            if (o.disponibles() != null && places > o.disponibles()) {
                throw new RegleMetierException("PLACES_INSUFFISANTES", o.niveau() + " " + o.filiere() + " : "
                        + o.disponibles() + " place(s) disponible(s) dans les classes");
            }
            jdbc.update("update offre_commission set places = ? where id = ?", places, o.id());
        }
        return commission(id);
    }

    /** Recalcule les offres d'après les classes de l'année (nouvelles classes, nouveau niveau). */
    @Transactional
    public CommissionVue actualiserOffres(UUID id) {
        Commission c = charger(id);
        exigerPreparateur();
        exigerPreparation(c);
        offresParDefaut(id, c.anneeId);
        return commission(id);
    }

    // ================================================================== candidatures

    @Transactional(readOnly = true)
    public List<CandidatureVue> candidatures(UUID commissionId, UUID offreId) {
        Commission c = charger(commissionId);
        exigerLecture(c.id);
        return offreId == null
                ? jdbc.query(SELECT_CANDIDATURE + " where x.commission_id = ? order by x.numero", (l, n) -> candidature(l),
                        commissionId)
                : jdbc.query(SELECT_CANDIDATURE + " where x.commission_id = ? and x.offre_id = ? order by x.numero",
                        (l, n) -> candidature(l), commissionId, offreId);
    }

    @Transactional
    public CandidatureVue enregistrer(UUID commissionId, DonneesCandidature d) {
        Commission c = charger(commissionId);
        exigerSaisie();
        exigerPreparation(c);
        Saisie s = valider(c, d);
        UUID id = UUID.randomUUID();
        int an = LocalDate.now(FUSEAU).getYear();
        String numero = "CAND-" + an + "-" + String.format("%04d", compteurs.suivant("CANDIDATURE_" + an));
        jdbc.update("""
                insert into candidature (tenant_id, id, commission_id, offre_id, numero, nom, prenoms, sexe,
                    date_naissance, lieu_naissance, responsable_nom, responsable_prenoms, responsable_telephone,
                    responsable_lien, etablissement_origine, classe_origine, moyenne_annuelle, note_examen, redoublant,
                    observations, saisie_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, commissionId, s.offreId, numero, s.nom, s.prenoms, s.sexe, Date.valueOf(s.dateNaissance),
                s.lieuNaissance, s.responsableNom, s.responsablePrenoms, s.telephone, s.lien, s.origine,
                s.classeOrigine, s.moyenne, s.note, s.redoublant, s.observations, UtilisateurConnecte.id());
        return candidature(id);
    }

    @Transactional
    public CandidatureVue modifierCandidature(UUID id, DonneesCandidature d) {
        Candidature x = chargerCandidature(id);
        Commission c = charger(x.commissionId);
        exigerSaisie();
        exigerPreparation(c);
        Saisie s = valider(c, d);
        jdbc.update("""
                update candidature set offre_id = ?, nom = ?, prenoms = ?, sexe = ?, date_naissance = ?,
                    lieu_naissance = ?, responsable_nom = ?, responsable_prenoms = ?, responsable_telephone = ?,
                    responsable_lien = ?, etablissement_origine = ?, classe_origine = ?, moyenne_annuelle = ?,
                    note_examen = ?, redoublant = ?, observations = ? where id = ?""",
                s.offreId, s.nom, s.prenoms, s.sexe, Date.valueOf(s.dateNaissance), s.lieuNaissance, s.responsableNom,
                s.responsablePrenoms, s.telephone, s.lien, s.origine, s.classeOrigine, s.moyenne, s.note, s.redoublant,
                s.observations, id);
        return candidature(id);
    }

    @Transactional
    public void supprimerCandidature(UUID id) {
        Candidature x = chargerCandidature(id);
        exigerSaisie();
        exigerPreparation(charger(x.commissionId));
        jdbc.update("delete from candidature where id = ?", id);
    }

    /** Le censeur ou le chef d'établissement statue sur la recevabilité (pièces, conditions d'âge…). */
    @Transactional
    public CandidatureVue recevabilite(UUID id, DemandeRecevabilite d) {
        Candidature x = chargerCandidature(id);
        exigerPreparateur();
        exigerPreparation(charger(x.commissionId));
        boolean recevable = d == null || d.recevable() == null || d.recevable();
        String motif = d == null || d.motif() == null || d.motif().isBlank() ? null : d.motif().strip();
        if (!recevable && motif == null) {
            throw new IllegalArgumentException("Motif obligatoire pour une candidature irrecevable");
        }
        if (motif != null && motif.length() > 300) {
            throw new IllegalArgumentException("Motif de 300 caractères au plus");
        }
        jdbc.update("update candidature set statut = ?, motif = ? where id = ?",
                recevable ? "DEPOSEE" : "IRRECEVABLE", recevable ? null : motif, id);
        return candidature(id);
    }

    // ================================================================== classement et délibération

    /** Classement au mérite : aperçu tant que la commission n'a pas délibéré, résultats figés ensuite. */
    @Transactional(readOnly = true)
    public List<ClassementOffre> classement(UUID commissionId) {
        Commission c = charger(commissionId);
        exigerLecture(c.id);
        return c.statut == StatutCommission.PREPARATION ? calculer(c) : figes(c);
    }

    @Transactional
    public CommissionVue deliberer(UUID commissionId, DemandeDeliberation d) {
        Commission c = charger(commissionId);
        exigerPresident();
        exigerPreparation(c);
        Integer nombre = jdbc.queryForObject("select count(*) from candidature where commission_id = ?", Integer.class,
                commissionId);
        if (nombre == null || nombre == 0) {
            throw new RegleMetierException("AUCUNE_CANDIDATURE", "Aucune candidature à examiner");
        }
        String observations = d == null || d.observations() == null || d.observations().isBlank() ? null
                : d.observations().strip();
        if (observations != null && observations.length() > 4000) {
            throw new IllegalArgumentException("Observations de 4000 caractères au plus");
        }
        for (ClassementOffre o : calculer(c)) {
            for (LigneClassement l : o.lignes()) {
                if (l.decision() == StatutCandidature.IRRECEVABLE) {
                    continue;
                }
                jdbc.update("update candidature set statut = ?, score = ?, rang = ?, motif = ? where id = ?",
                        l.decision().name(), l.score(), l.rang(), l.motif(), l.candidatureId());
            }
        }
        jdbc.update("""
                update commission_recrutement set statut = 'DELIBEREE', deliberee_le = now(), deliberee_par = ?,
                    observations_pv = ? where id = ?""", UtilisateurConnecte.id(), observations, commissionId);
        audit.enregistrer("COMMISSION_RECRUTEMENT_DELIBEREE", c.numero, Map.of("candidatures", nombre));
        return commission(commissionId);
    }

    @Transactional
    public CommissionVue clore(UUID commissionId) {
        Commission c = charger(commissionId);
        exigerPresident();
        if (c.statut != StatutCommission.DELIBEREE) {
            throw new RegleMetierException("COMMISSION_NON_DELIBEREE", "La commission doit d'abord délibérer");
        }
        jdbc.update("update commission_recrutement set statut = 'CLOSE', close_le = now() where id = ?", commissionId);
        audit.enregistrer("COMMISSION_RECRUTEMENT_CLOSE", c.numero, null);
        return commission(commissionId);
    }

    // ================================================================== inscription et désistement

    /** L'intendance inscrit un candidat retenu dans une classe du niveau et de la filière de son offre. */
    @Transactional
    public CandidatureVue inscrire(UUID id, DemandeInscription d) {
        Candidature x = chargerCandidature(id);
        Commission c = charger(x.commissionId);
        exigerInscription();
        if (c.statut != StatutCommission.DELIBEREE) {
            throw new RegleMetierException(c.statut == StatutCommission.CLOSE ? "COMMISSION_CLOSE" : "COMMISSION_NON_DELIBEREE",
                    c.statut == StatutCommission.CLOSE ? "La commission est close" : "La commission n'a pas encore délibéré");
        }
        if (x.statut != StatutCandidature.RETENUE) {
            throw new RegleMetierException("CANDIDAT_NON_RETENU", "Seul un candidat retenu est inscrit");
        }
        if (d == null || d.classeId() == null) {
            throw new IllegalArgumentException("Classe obligatoire");
        }
        Boolean conforme = jdbc.queryForObject("""
                select exists (select 1 from classe k join offre_commission o on o.id = ?
                    where k.id = ? and k.annee_id = ? and k.niveau = o.niveau and k.filiere_id = o.filiere_id)""",
                Boolean.class, x.offreId, d.classeId(), c.anneeId);
        if (!Boolean.TRUE.equals(conforme)) {
            throw new RegleMetierException("CLASSE_HORS_OFFRE",
                    "La classe n'est pas du niveau et de la filière pour lesquels le candidat a été retenu");
        }
        Map<String, Object> v = jdbc.queryForMap("""
                select nom, prenoms, sexe, date_naissance, lieu_naissance, responsable_nom, responsable_prenoms,
                       responsable_telephone, responsable_lien, redoublant, numero from candidature where id = ?""", id);
        LocalDate naissance = ((Date) v.get("date_naissance")).toLocalDate();
        UUID eleve = eleves.creer(
                new DonneesEleve(null, (String) v.get("nom"), (String) v.get("prenoms"),
                        Sexe.valueOf((String) v.get("sexe")), naissance, (String) v.get("lieu_naissance"), null, null,
                        null),
                List.of(new DonneesResponsable((String) v.get("responsable_nom"), (String) v.get("responsable_prenoms"),
                        (String) v.get("responsable_telephone"), LienParente.valueOf((String) v.get("responsable_lien")),
                        null, null, true, true)))
                .eleve().id();
        InscriptionVue inscription = inscriptions.inscrire(eleve, d.classeId(), (Boolean) v.get("redoublant"),
                StatutBourse.NON_BOURSIER, ModeAdmission.COMPLEMENT_EFFECTIF);
        jdbc.update("update candidature set statut = 'INSCRITE', inscription_id = ?, inscrite_le = now() where id = ?",
                inscription.id(), id);
        audit.enregistrer("CANDIDAT_INSCRIT", (String) v.get("numero"), Map.of("classe", inscription.classeCode()));
        // L'inscription (JPA) n'est écrite en base qu'au flush, à la fin de la transaction : la jointure de
        // SELECT_CANDIDATURE ne la voit pas encore. La classe vient donc de l'inscription elle-même.
        CandidatureVue k = candidature(id);
        return new CandidatureVue(k.id(), k.numero(), k.offreId(), k.offre(), k.nom(), k.prenoms(), k.sexe(),
                k.dateNaissance(), k.lieuNaissance(), k.responsableNom(), k.responsablePrenoms(),
                k.responsableTelephone(), k.responsableLien(), k.etablissementOrigine(), k.classeOrigine(),
                k.moyenneAnnuelle(), k.noteExamen(), k.redoublant(), k.observations(), k.statut(), k.motif(), k.score(),
                k.rang(), k.repechee(), inscription.id(), k.inscriteLe(), inscription.classeCode());
    }

    /** Désistement d'un retenu (ou d'un candidat en liste d'attente) : le premier de la liste d'attente est repêché. */
    @Transactional
    public ResultatDesistement desister(UUID id, DemandeDesistement d) {
        Candidature x = chargerCandidature(id);
        Commission c = charger(x.commissionId);
        exigerInscription();
        if (c.statut != StatutCommission.DELIBEREE) {
            throw new RegleMetierException("COMMISSION_NON_DELIBEREE", "Le désistement suit la délibération");
        }
        if (x.statut != StatutCandidature.RETENUE && x.statut != StatutCandidature.LISTE_ATTENTE) {
            throw new RegleMetierException("DESISTEMENT_IMPOSSIBLE",
                    "Seul un candidat retenu ou en liste d'attente se désiste");
        }
        String motif = d == null || d.motif() == null || d.motif().isBlank() ? "Désistement" : d.motif().strip();
        if (motif.length() > 300) {
            throw new IllegalArgumentException("Motif de 300 caractères au plus");
        }
        jdbc.update("update candidature set statut = 'DESISTEE', motif = ? where id = ?", motif, id);
        CandidatureVue repechee = null;
        if (x.statut == StatutCandidature.RETENUE) {
            List<UUID> suivant = jdbc.queryForList("""
                    select id from candidature where offre_id = ? and statut = 'LISTE_ATTENTE'
                    order by rang limit 1""", UUID.class, x.offreId);
            if (!suivant.isEmpty()) {
                jdbc.update("""
                        update candidature set statut = 'RETENUE', repechee = true,
                            motif = 'Repêché(e) sur la liste d''attente' where id = ?""", suivant.get(0));
                repechee = candidature(suivant.get(0));
            }
        }
        audit.enregistrer("CANDIDAT_DESISTE", x.numero, repechee == null ? null : Map.of("repeche", repechee.numero()));
        return new ResultatDesistement(candidature(id), repechee);
    }

    // ================================================================== classement : calcul

    List<ClassementOffre> calculer(Commission c) {
        Map<UUID, List<Ligne>> parOffre = new LinkedHashMap<>();
        Map<UUID, String[]> offres = new LinkedHashMap<>();
        jdbc.query("""
                select o.id, o.niveau, f.code, o.places from offre_commission o join filiere f on f.id = o.filiere_id
                where o.commission_id = ? order by o.niveau, f.code""", l -> {
            UUID o = l.getObject(1, UUID.class);
            offres.put(o, new String[] { l.getString(2) + " " + l.getString(3), Integer.toString(l.getInt(4)) });
            parOffre.put(o, new ArrayList<>());
        }, c.id);
        jdbc.query("""
                select id, offre_id, numero, nom, prenoms, sexe, moyenne_annuelle, note_examen, date_naissance, saisie_le,
                       statut, motif
                from candidature where commission_id = ?""", l -> {
            Ligne x = new Ligne(l.getObject(1, UUID.class), l.getObject(2, UUID.class), l.getString(3), l.getString(4),
                    l.getString(5), Sexe.valueOf(l.getString(6)), l.getBigDecimal(7), l.getBigDecimal(8),
                    l.getObject(9, LocalDate.class), l.getTimestamp(10).toInstant(),
                    StatutCandidature.valueOf(l.getString(11)), l.getString(12));
            parOffre.computeIfAbsent(x.offreId, k -> new ArrayList<>()).add(x);
        }, c.id);
        List<ClassementOffre> r = new ArrayList<>();
        for (Map.Entry<UUID, List<Ligne>> e : parOffre.entrySet()) {
            String[] o = offres.get(e.getKey());
            int places = o == null ? 0 : Integer.parseInt(o[1]);
            List<Ligne> admissibles = new ArrayList<>();
            List<LigneClassement> lignes = new ArrayList<>();
            List<LigneClassement> fin = new ArrayList<>();
            for (Ligne x : e.getValue()) {
                BigDecimal score = score(x, c.poidsExamen);
                if (x.statut == StatutCandidature.IRRECEVABLE) {
                    fin.add(new LigneClassement(x.id, x.numero, x.nom, x.prenoms, x.sexe, x.moyenne, x.note, score, null,
                            StatutCandidature.IRRECEVABLE, x.motif));
                } else if (score.compareTo(c.moyenneMinimale) < 0) {
                    fin.add(new LigneClassement(x.id, x.numero, x.nom, x.prenoms, x.sexe, x.moyenne, x.note, score, null,
                            StatutCandidature.NON_RETENUE, "Moyenne inférieure à "
                                    + c.moyenneMinimale.stripTrailingZeros().toPlainString() + "/20"));
                } else {
                    admissibles.add(x.avecScore(score));
                }
            }
            admissibles.sort(Comparator.comparing((Ligne x) -> x.score).reversed()
                    .thenComparing((Ligne x) -> x.naissance, Comparator.reverseOrder())
                    .thenComparing(x -> x.saisieLe));
            int rang = 0;
            for (Ligne x : admissibles) {
                rang++;
                StatutCandidature decision = rang <= places ? StatutCandidature.RETENUE
                        : rang <= places + c.listeAttente ? StatutCandidature.LISTE_ATTENTE
                                : StatutCandidature.NON_RETENUE;
                String motif = decision == StatutCandidature.NON_RETENUE ? "Places et liste d'attente complètes" : null;
                lignes.add(new LigneClassement(x.id, x.numero, x.nom, x.prenoms, x.sexe, x.moyenne, x.note, x.score,
                        rang, decision, motif));
            }
            fin.sort(Comparator.comparing(LigneClassement::numero));
            lignes.addAll(fin);
            r.add(new ClassementOffre(e.getKey(), o == null ? "?" : o[0], places, lignes));
        }
        return r;
    }

    private List<ClassementOffre> figes(Commission c) {
        Map<UUID, ClassementOffre> r = new LinkedHashMap<>();
        jdbc.query("""
                select o.id, o.niveau || ' ' || f.code, o.places from offre_commission o join filiere f on f.id = o.filiere_id
                where o.commission_id = ? order by o.niveau, f.code""", l -> {
            r.put(l.getObject(1, UUID.class), new ClassementOffre(l.getObject(1, UUID.class), l.getString(2), l.getInt(3),
                    new ArrayList<>()));
        }, c.id);
        jdbc.query("""
                select id, offre_id, numero, nom, prenoms, sexe, moyenne_annuelle, note_examen, score, rang, statut, motif
                from candidature where commission_id = ? order by rang nulls last, numero""", l -> {
            ClassementOffre o = r.get(l.getObject(2, UUID.class));
            if (o != null) {
                o.lignes().add(new LigneClassement(l.getObject(1, UUID.class), l.getString(3), l.getString(4),
                        l.getString(5), Sexe.valueOf(l.getString(6)), l.getBigDecimal(7), l.getBigDecimal(8),
                        l.getBigDecimal(9), (Integer) l.getObject(10, Integer.class),
                        StatutCandidature.valueOf(l.getString(11)), l.getString(12)));
            }
        }, c.id);
        return new ArrayList<>(r.values());
    }

    /** Score : moyenne annuelle, ou (1 − p) × moyenne + p × note d'examen si la note est connue. */
    static BigDecimal score(Ligne x, BigDecimal poidsExamen) {
        if (x.note == null || poidsExamen.signum() == 0) {
            return x.moyenne.setScale(3, RoundingMode.HALF_UP);
        }
        return x.moyenne.multiply(BigDecimal.ONE.subtract(poidsExamen)).add(x.note.multiply(poidsExamen))
                .setScale(3, RoundingMode.HALF_UP);
    }

    record Ligne(UUID id, UUID offreId, String numero, String nom, String prenoms, Sexe sexe, BigDecimal moyenne,
            BigDecimal note, LocalDate naissance, Instant saisieLe, StatutCandidature statut, String motif,
            BigDecimal score) {

        Ligne(UUID id, UUID offreId, String numero, String nom, String prenoms, Sexe sexe, BigDecimal moyenne,
                BigDecimal note, LocalDate naissance, Instant saisieLe, StatutCandidature statut, String motif) {
            this(id, offreId, numero, nom, prenoms, sexe, moyenne, note, naissance, saisieLe, statut, motif, null);
        }

        Ligne avecScore(BigDecimal s) {
            return new Ligne(id, offreId, numero, nom, prenoms, sexe, moyenne, note, naissance, saisieLe, statut, motif, s);
        }
    }

    // ================================================================== offres et membres par défaut

    private void offresParDefaut(UUID commissionId, UUID anneeId) {
        jdbc.update("""
                insert into offre_commission (tenant_id, id, commission_id, niveau, filiere_id, places)
                select distinct on (k.niveau, k.filiere_id) tenant_courant(), gen_random_uuid(), ?, k.niveau, k.filiere_id, 0
                from classe k where k.annee_id = ?
                on conflict (tenant_id, commission_id, niveau, filiere_id) do nothing""", commissionId, anneeId);
    }

    private void membresParDefaut(UUID commissionId) {
        List<MembreVue> actifs = membres.lister().stream().filter(MembreVue::actif).toList();
        UUID moi = UtilisateurConnecte.id();
        MembreVue president = actifs.stream().filter(m -> m.role() == Role.ADMIN_ECOLE && m.utilisateurId().equals(moi))
                .findFirst().orElse(actifs.stream().filter(m -> m.role() == Role.ADMIN_ECOLE).findFirst().orElse(null));
        int ordre = 0;
        if (president != null) {
            ajouter(commissionId, Qualite.PRESIDENT, president, "Chef d'établissement", ordre++);
        }
        MembreVue censeur = actifs.stream().filter(m -> m.role() == Role.CENSEUR).findFirst().orElse(null);
        if (censeur != null) {
            ajouter(commissionId, Qualite.VICE_PRESIDENT, censeur, "Censeur", ordre++);
        }
        MembreVue cpe = actifs.stream().filter(m -> m.role() == Role.SURVEILLANT).findFirst().orElse(null);
        if (cpe != null) {
            ajouter(commissionId, Qualite.RAPPORTEUR, cpe, "Conseiller principal d'éducation", ordre++);
        }
        List<UUID> deja = new ArrayList<>();
        for (MembreVue m : actifs) {
            if (m.role() == Role.INTENDANT && !deja.contains(m.utilisateurId())) {
                deja.add(m.utilisateurId());
                ajouter(commissionId, Qualite.MEMBRE, m, "Intendant", ordre++);
            }
        }
        jdbc.update("""
                insert into membre_commission (tenant_id, id, commission_id, qualite, utilisateur_id, nom, fonction, ordre)
                values (tenant_courant(), ?, ?, 'MEMBRE', null, 'À désigner', 'Représentant de l''APE', ?)""",
                UUID.randomUUID(), commissionId, ordre);
    }

    private void ajouter(UUID commissionId, Qualite q, MembreVue m, String fonction, int ordre) {
        jdbc.update("""
                insert into membre_commission (tenant_id, id, commission_id, qualite, utilisateur_id, nom, fonction, ordre)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), commissionId, q.name(), m.utilisateurId(), m.nom() + " " + m.prenoms(), fonction, ordre);
    }

    // ================================================================== offres : lecture

    private List<OffreVue> offres(Commission c) {
        Map<String, List<ClasseOffre>> classes = new HashMap<>();
        jdbc.query("""
                select k.id, k.code, k.effectif_max, k.niveau, k.filiere_id,
                       (select count(*) from inscription i where i.classe_id = k.id and i.statut = 'ACTIVE')
                from classe k where k.annee_id = ? order by k.code""", l -> {
            classes.computeIfAbsent(l.getString(4) + "|" + l.getObject(5, UUID.class), x -> new ArrayList<>())
                    .add(new ClasseOffre(l.getObject(1, UUID.class), l.getString(2), (Short) l.getObject(3, Short.class),
                            l.getInt(6)));
        }, c.anneeId);
        return jdbc.query("""
                select o.id, o.niveau, o.filiere_id, f.code, o.places,
                       (select count(*) from candidature x where x.offre_id = o.id),
                       (select count(*) from candidature x where x.offre_id = o.id and x.statut in ('RETENUE', 'INSCRITE')),
                       (select count(*) from candidature x where x.offre_id = o.id and x.statut = 'LISTE_ATTENTE'),
                       (select count(*) from candidature x where x.offre_id = o.id and x.statut = 'INSCRITE')
                from offre_commission o join filiere f on f.id = o.filiere_id
                where o.commission_id = ? order by o.niveau, f.code""", (l, n) -> {
            List<ClasseOffre> k = classes.getOrDefault(l.getString(2) + "|" + l.getObject(3, UUID.class), List.of());
            Integer disponibles = 0;
            for (ClasseOffre x : k) {
                if (x.effectifMax() == null) {
                    disponibles = null;
                    break;
                }
                disponibles += Math.max(0, x.effectifMax() - x.inscrits());
            }
            return new OffreVue(l.getObject(1, UUID.class), l.getString(2), l.getObject(3, UUID.class), l.getString(4),
                    l.getInt(5), disponibles, l.getInt(6), l.getInt(7), l.getInt(8), l.getInt(9), k);
        }, c.id);
    }

    // ================================================================== droits

    private Droits droits(Commission c) {
        boolean president = UtilisateurConnecte.aUnRole("ADMIN_ECOLE");
        boolean preparation = c.statut == StatutCommission.PREPARATION;
        return new Droits(president && preparation, preparation && UtilisateurConnecte.aUnRole(ROLES_SAISIE),
                president && preparation, c.statut == StatutCommission.DELIBEREE
                        && UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "INTENDANT"),
                president && c.statut == StatutCommission.DELIBEREE);
    }

    static final String[] ROLES_SAISIE = { "ADMIN_ECOLE", "CENSEUR", "SECRETARIAT", "SURVEILLANT" };

    private static void exigerPresident() {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            throw new AccesRefuseException("Réservé au chef d'établissement, président de la commission");
        }
    }

    private static void exigerPreparateur() {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "CENSEUR")) {
            throw new AccesRefuseException("Réservé au président et au vice-président de la commission");
        }
    }

    private static void exigerSaisie() {
        if (!UtilisateurConnecte.aUnRole(ROLES_SAISIE)) {
            throw new AccesRefuseException("Réservé au secrétariat, au censeur, au CPE et au chef d'établissement");
        }
    }

    private static void exigerInscription() {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "INTENDANT")) {
            throw new AccesRefuseException("L'inscription des retenus relève de l'intendance");
        }
    }

    /** Le personnel concerné, ou un membre désigné de la commission (un enseignant, par exemple). */
    void exigerLecture(UUID commissionId) {
        if (UtilisateurConnecte.aUnRole(PERSONNEL_COMMISSION)) {
            return;
        }
        UUID moi = UtilisateurConnecte.id();
        Integer n = commissionId == null
                ? jdbc.queryForObject("select count(*) from membre_commission where utilisateur_id = ?", Integer.class, moi)
                : jdbc.queryForObject("select count(*) from membre_commission where commission_id = ? and utilisateur_id = ?",
                        Integer.class, commissionId, moi);
        if (n == null || n == 0) {
            throw new AccesRefuseException("Réservé aux membres de la commission de recrutement");
        }
    }

    private static void exigerPreparation(Commission c) {
        if (c.statut != StatutCommission.PREPARATION) {
            throw new RegleMetierException("COMMISSION_DELIBEREE",
                    "La commission a délibéré : les places, les membres et les candidatures sont figés");
        }
    }

    // ================================================================== chargement

    record Commission(UUID id, String numero, UUID anneeId, String annee, String intitule, LocalDate dateSession,
            String lieu, LocalDate dateLimite, String criteres, BigDecimal moyenneMinimale, BigDecimal poidsExamen,
            int listeAttente, StatutCommission statut, String observations, Instant delibereeLe, UUID delibereePar,
            Instant closeLe) {
    }

    private record Candidature(UUID id, UUID commissionId, UUID offreId, String numero, StatutCandidature statut) {
    }

    Commission charger(UUID id) {
        return jdbc.query("""
                select c.id, c.numero, c.annee_id, a.libelle, c.intitule, c.date_session, c.lieu, c.date_limite_depot,
                       c.criteres, c.moyenne_minimale, c.poids_examen, c.taille_liste_attente, c.statut, c.observations_pv,
                       c.deliberee_le, c.deliberee_par, c.close_le
                from commission_recrutement c
                join annee_scolaire a on a.tenant_id = c.tenant_id and a.id = c.annee_id where c.id = ?""",
                (l, n) -> new Commission(l.getObject(1, UUID.class), l.getString(2), l.getObject(3, UUID.class),
                        l.getString(4), l.getString(5), l.getObject(6, LocalDate.class), l.getString(7),
                        l.getObject(8, LocalDate.class), l.getString(9), l.getBigDecimal(10), l.getBigDecimal(11),
                        l.getInt(12), StatutCommission.valueOf(l.getString(13)), l.getString(14),
                        instant(l.getTimestamp(15)), l.getObject(16, UUID.class), instant(l.getTimestamp(17))),
                id).stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Commission introuvable"));
    }

    private Candidature chargerCandidature(UUID id) {
        return jdbc.query("select id, commission_id, offre_id, numero, statut from candidature where id = ?",
                (l, n) -> new Candidature(l.getObject(1, UUID.class), l.getObject(2, UUID.class),
                        l.getObject(3, UUID.class), l.getString(4), StatutCandidature.valueOf(l.getString(5))),
                id).stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Candidature introuvable"));
    }

    private static final String SELECT_CANDIDATURE = """
            select x.id, x.numero, x.offre_id, o.niveau || ' ' || f.code, x.nom, x.prenoms, x.sexe, x.date_naissance,
                   x.lieu_naissance, x.responsable_nom, x.responsable_prenoms, x.responsable_telephone,
                   x.responsable_lien, x.etablissement_origine, x.classe_origine, x.moyenne_annuelle, x.note_examen,
                   x.redoublant, x.observations, x.statut, x.motif, x.score, x.rang, x.repechee, x.inscription_id,
                   x.inscrite_le, k.code
            from candidature x
            join offre_commission o on o.tenant_id = x.tenant_id and o.id = x.offre_id
            join filiere f on f.tenant_id = o.tenant_id and f.id = o.filiere_id
            left join inscription i on i.tenant_id = x.tenant_id and i.id = x.inscription_id
            left join classe k on k.tenant_id = i.tenant_id and k.id = i.classe_id""";

    CandidatureVue candidature(UUID id) {
        return jdbc.query(SELECT_CANDIDATURE + " where x.id = ?", (l, n) -> candidature(l), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Candidature introuvable"));
    }

    private static CandidatureVue candidature(ResultSet l) throws SQLException {
        return new CandidatureVue(l.getObject(1, UUID.class), l.getString(2), l.getObject(3, UUID.class), l.getString(4),
                l.getString(5), l.getString(6), Sexe.valueOf(l.getString(7)), l.getObject(8, LocalDate.class),
                l.getString(9), l.getString(10), l.getString(11), l.getString(12), LienParente.valueOf(l.getString(13)),
                l.getString(14), l.getString(15), l.getBigDecimal(16), l.getBigDecimal(17), l.getBoolean(18),
                l.getString(19), StatutCandidature.valueOf(l.getString(20)), l.getString(21), l.getBigDecimal(22),
                (Integer) l.getObject(23, Integer.class), l.getBoolean(24), l.getObject(25, UUID.class),
                instant(l.getTimestamp(26)), l.getString(27));
    }

    private String nomUtilisateur(UUID id) {
        return jdbc.queryForList("select nom || ' ' || prenoms from utilisateur where id = ?", String.class, id).stream()
                .findFirst().orElse(null);
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    // ================================================================== validation

    private record Parametres(String intitule, LocalDate dateSession, String lieu, LocalDate dateLimite, String criteres,
            BigDecimal moyenneMinimale, BigDecimal poidsExamen, int listeAttente) {
    }

    private static Parametres valider(DonneesCommission d) {
        if (d == null) {
            throw new IllegalArgumentException("Données de la commission obligatoires");
        }
        String intitule = texte(d.intitule(), 200, "Intitulé");
        if (d.dateSession() == null) {
            throw new IllegalArgumentException("Date de la session obligatoire");
        }
        if (d.dateLimiteDepot() != null && d.dateLimiteDepot().isAfter(d.dateSession())) {
            throw new IllegalArgumentException("La date limite de dépôt précède la session");
        }
        BigDecimal min = d.moyenneMinimale() == null ? BigDecimal.TEN : d.moyenneMinimale();
        if (min.signum() < 0 || min.compareTo(BigDecimal.valueOf(20)) > 0) {
            throw new IllegalArgumentException("Moyenne minimale entre 0 et 20");
        }
        BigDecimal poids = d.poidsExamen() == null ? BigDecimal.ZERO : d.poidsExamen();
        if (poids.signum() < 0 || poids.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Poids de la note d'examen entre 0 et 1");
        }
        int attente = d.tailleListeAttente() == null ? 5 : d.tailleListeAttente();
        if (attente < 0 || attente > 100) {
            throw new IllegalArgumentException("Liste d'attente de 0 à 100 candidats");
        }
        return new Parametres(intitule, d.dateSession(), facultatif(d.lieu(), 120, "Lieu"), d.dateLimiteDepot(),
                facultatif(d.criteres(), 1000, "Critères"), min, poids, attente);
    }

    private record Saisie(UUID offreId, String nom, String prenoms, String sexe, LocalDate dateNaissance,
            String lieuNaissance, String responsableNom, String responsablePrenoms, String telephone, String lien,
            String origine, String classeOrigine, BigDecimal moyenne, BigDecimal note, boolean redoublant,
            String observations) {
    }

    private Saisie valider(Commission c, DonneesCandidature d) {
        if (d == null || d.offreId() == null) {
            throw new IllegalArgumentException("Niveau demandé obligatoire");
        }
        Integer offre = jdbc.queryForObject("select count(*) from offre_commission where id = ? and commission_id = ?",
                Integer.class, d.offreId(), c.id);
        if (offre == null || offre == 0) {
            throw new RessourceIntrouvableException("Niveau demandé absent de cette commission");
        }
        if (d.sexe() == null || d.dateNaissance() == null || d.responsableLien() == null) {
            throw new IllegalArgumentException("Sexe, date de naissance et lien du responsable obligatoires");
        }
        if (d.dateNaissance().isAfter(LocalDate.now(FUSEAU).minusYears(8))) {
            throw new IllegalArgumentException("Date de naissance invraisemblable");
        }
        if (d.moyenneAnnuelle() == null || d.moyenneAnnuelle().signum() < 0
                || d.moyenneAnnuelle().compareTo(BigDecimal.valueOf(20)) > 0) {
            throw new IllegalArgumentException("Moyenne annuelle entre 0 et 20");
        }
        if (d.noteExamen() != null && (d.noteExamen().signum() < 0 || d.noteExamen().compareTo(BigDecimal.valueOf(20)) > 0)) {
            throw new IllegalArgumentException("Note d'examen entre 0 et 20");
        }
        String tel = texte(d.responsableTelephone(), 20, "Téléphone du responsable").replaceAll("[\\s.-]", "");
        if (!tel.matches("\\+?[0-9]{6,15}")) {
            throw new IllegalArgumentException("Téléphone du responsable invalide");
        }
        return new Saisie(d.offreId(), texte(d.nom(), 80, "Nom").toUpperCase(java.util.Locale.ROOT),
                texte(d.prenoms(), 120, "Prénoms"), d.sexe().name(), d.dateNaissance(),
                facultatif(d.lieuNaissance(), 120, "Lieu de naissance"),
                texte(d.responsableNom(), 80, "Nom du responsable").toUpperCase(java.util.Locale.ROOT),
                texte(d.responsablePrenoms(), 120, "Prénoms du responsable"), tel, d.responsableLien().name(),
                texte(d.etablissementOrigine(), 200, "Établissement d'origine"),
                facultatif(d.classeOrigine(), 40, "Classe d'origine"), d.moyenneAnnuelle(), d.noteExamen(),
                Boolean.TRUE.equals(d.redoublant()), facultatif(d.observations(), 500, "Observations"));
    }

    static String texte(String s, int max, String quoi) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(quoi + " obligatoire");
        }
        String t = s.strip().replaceAll("\\s+", " ");
        if (t.length() > max) {
            throw new IllegalArgumentException(quoi + " de " + max + " caractères au plus");
        }
        return t;
    }

    static String facultatif(String s, int max, String quoi) {
        return s == null || s.isBlank() ? null : texte(s, max, quoi);
    }
}
