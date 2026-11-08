package bf.edutech.plateforme.personnel;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.personnel.Agents.Agent;
import bf.edutech.plateforme.personnel.Vues.AgentResume;
import bf.edutech.plateforme.personnel.Vues.Anciennete;
import bf.edutech.plateforme.personnel.Vues.Categorie;
import bf.edutech.plateforme.personnel.Vues.DiplomeVue;
import bf.edutech.plateforme.personnel.Vues.DonneesAgent;
import bf.edutech.plateforme.personnel.Vues.DossierVue;
import bf.edutech.plateforme.personnel.Vues.Droits;
import bf.edutech.plateforme.personnel.Vues.NatureDiplome;
import bf.edutech.plateforme.personnel.Vues.NaturePiece;
import bf.edutech.plateforme.personnel.Vues.PieceVue;
import bf.edutech.plateforme.personnel.Vues.ResultatImport;
import bf.edutech.plateforme.personnel.Vues.SignalementVue;
import bf.edutech.plateforme.personnel.Vues.Situation;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Dossiers du personnel (v0.51) : création (agents de soutien sans compte compris), reprise des enseignants et
 * des comptes de l'établissement, identité et situation administrative, ancienneté calculée, diplômes, pièces
 * classées, signalements d'erreur par l'agent.
 */
@Service
public class DossiersService {

    public record ListePersonnel(List<AgentResume> agents, int comptesSansDossier) {
    }

    public record DonneesDiplome(NatureDiplome nature, String intitule, String specialite, String delivrePar,
            Integer annee, UUID pieceId) {
    }

    public record Telechargement(byte[] contenu, String type, String nom) {
    }

    private final JdbcTemplate jdbc;
    private final Agents agents;
    private final ActesService actes;
    private final AbsencesPersonnelService absences;
    private final AuditService audit;
    private final Clock horloge;

    DossiersService(JdbcTemplate jdbc, Agents agents, ActesService actes, AbsencesPersonnelService absences,
            AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.actes = actes;
        this.absences = absences;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ liste et reprise

    @Transactional(readOnly = true)
    public ListePersonnel lister() {
        Personnel.exigerLecture();
        LocalDate jour = aujourdhui();
        List<AgentResume> liste = jdbc.query("select " + Agents.COLONNES + """
                , (select count(*) from piece_agent p where p.agent_id = agent.id),
                  (select count(*) from signalement_dossier s where s.agent_id = agent.id and s.traite_le is null),
                  exists (select 1 from absence_agent b where b.agent_id = agent.id
                           and b.statut in ('ACCORDEE', 'CONSTATEE') and ? between b.debut and b.fin),
                  (select count(*) from diplome_agent d where d.agent_id = agent.id)
                from agent order by date_cessation is not null, nom, prenoms""", (r, n) -> {
            Agent a = Agents.lire(r);
            return new AgentResume(a.id(), a.categorie(), a.nom(), a.prenoms(), a.sexe(), a.matricule(), a.situation(),
                    a.emploi(), a.grade(), a.fonction(), a.datePriseService(), a.dateCessation(), a.utilisateurId() != null,
                    Personnel.duree(a.datePriseService(), fin(a, jour)), r.getInt(26), r.getInt(27), r.getBoolean(28),
                    manquants(a, r.getInt(29)));
        }, jour);
        return new ListePersonnel(liste, comptesSansDossier());
    }

    private int comptesSansDossier() {
        Integer n = jdbc.queryForObject("""
                select count(distinct m.utilisateur_id) from membre_etablissement m
                where m.actif and m.role not in ('PARENT', 'ELEVE')
                  and not exists (select 1 from agent a where a.utilisateur_id = m.utilisateur_id)""", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Ouvre un dossier pour chaque enseignant engagé et chaque compte du personnel qui n'en a pas : identité,
     * matricule et date d'engagement sont repris ; le reste se complète ensuite.
     */
    @Transactional
    public ResultatImport importer() {
        Personnel.exigerGestionnaire();
        int avant = compter();
        // Enseignants engagés (en fonction ou invités) : catégorie, matricule, date d'engagement, vacataire
        jdbc.update("""
                insert into agent (tenant_id, id, utilisateur_id, enseignant_id, categorie, nom, prenoms, sexe, telephone,
                                   matricule, situation, emploi, fonction, date_prise_service, modifie_par)
                select distinct on (en.utilisateur_id) tenant_courant(), gen_random_uuid(), en.utilisateur_id, en.id,
                       'ENSEIGNANT', en.nom, en.prenoms, en.sexe, en.telephone,
                       case when not exists (select 1 from agent x where x.matricule = en.matricule_fp) then en.matricule_fp end,
                       case when e.type = 'VACATAIRE' then 'VACATAIRE' end,
                       case when en.specialite is not null then 'Enseignant de ' || en.specialite end,
                       'Enseignant' || case when e.type = 'VACATAIRE' then ' vacataire' else '' end, e.debut, ?
                from engagement_enseignant e join enseignant en on en.id = e.enseignant_id
                where e.statut in ('INVITE', 'ACTIF')
                  and not exists (select 1 from agent a where a.utilisateur_id = en.utilisateur_id)
                order by en.utilisateur_id, e.debut desc""", UtilisateurConnecte.id());
        // Autres comptes du personnel : la fonction se déduit du rôle
        jdbc.update("""
                insert into agent (tenant_id, id, utilisateur_id, categorie, nom, prenoms, sexe, telephone, fonction,
                                   modifie_par)
                select distinct on (m.utilisateur_id) tenant_courant(), gen_random_uuid(), m.utilisateur_id,
                       case when m.role = 'ENSEIGNANT' then 'ENSEIGNANT' else 'ADMINISTRATIF' end, u.nom, u.prenoms, 'M',
                       u.telephone,
                       case m.role when 'ADMIN_ECOLE' then 'Chef d''établissement' when 'CENSEUR' then 'Censeur'
                                   when 'CHEF_TRAVAUX' then 'Chef des travaux' when 'SECRETARIAT' then 'Secrétaire'
                                   when 'INTENDANT' then 'Intendant' when 'SURVEILLANT' then 'Conseiller principal d''éducation'
                                   else 'Enseignant' end, ?
                from membre_etablissement m join utilisateur u on u.id = m.utilisateur_id
                where m.actif and m.role not in ('PARENT', 'ELEVE')
                  and not exists (select 1 from agent a where a.utilisateur_id = m.utilisateur_id)
                order by m.utilisateur_id,
                         case m.role when 'ADMIN_ECOLE' then 0 when 'CENSEUR' then 1 when 'CHEF_TRAVAUX' then 2
                                     when 'INTENDANT' then 3 when 'SECRETARIAT' then 4 when 'SURVEILLANT' then 5 else 6 end""",
                UtilisateurConnecte.id());
        int crees = compter() - avant;
        audit.enregistrer("PERSONNEL_REPRIS", crees + " dossier(s)", null);
        return new ResultatImport(crees, avant);
    }

    private int compter() {
        Integer n = jdbc.queryForObject("select count(*) from agent", Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ dossier

    @Transactional
    public DossierVue creer(DonneesAgent d) {
        Personnel.exigerGestionnaire();
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into agent (tenant_id, id, categorie, nom, prenoms, sexe, modifie_par) "
                    + "values (tenant_courant(), ?, ?, ?, ?, ?, ?)", id,
                    d.categorie() == null ? Categorie.SOUTIEN.name() : d.categorie().name(),
                    Personnel.obligatoire(d.nom(), "Nom", 80).toUpperCase(java.util.Locale.FRENCH),
                    Personnel.obligatoire(d.prenoms(), "Prénoms", 120), sexe(d.sexe()), UtilisateurConnecte.id());
            ecrire(id, d);
        } catch (DuplicateKeyException e) {
            throw new RegleMetierException("MATRICULE_EXISTANT", "Ce matricule est déjà attribué à un autre dossier");
        }
        audit.enregistrer("DOSSIER_PERSONNEL_CREE", d.nom() + " " + d.prenoms(), null);
        return dossier(id);
    }

    @Transactional
    public DossierVue modifier(UUID id, DonneesAgent d) {
        Personnel.exigerGestionnaire();
        agents.charger(id);
        try {
            jdbc.update("update agent set nom = ?, prenoms = ?, sexe = ?, categorie = coalesce(?, categorie) where id = ?",
                    Personnel.obligatoire(d.nom(), "Nom", 80).toUpperCase(java.util.Locale.FRENCH),
                    Personnel.obligatoire(d.prenoms(), "Prénoms", 120), sexe(d.sexe()),
                    d.categorie() == null ? null : d.categorie().name(), id);
            ecrire(id, d);
        } catch (DuplicateKeyException e) {
            throw new RegleMetierException("MATRICULE_EXISTANT", "Ce matricule est déjà attribué à un autre dossier");
        }
        audit.enregistrer("DOSSIER_PERSONNEL_MODIFIE", d.nom() + " " + d.prenoms(), null);
        return dossier(id);
    }

    private void ecrire(UUID id, DonneesAgent d) {
        LocalDate jour = aujourdhui();
        if (d.dateNaissance() != null && (d.dateNaissance().isAfter(jour.minusYears(15)) || d.dateNaissance().getYear() < 1930)) {
            throw new IllegalArgumentException("Date de naissance invalide");
        }
        for (LocalDate x : new LocalDate[] { d.datePremierePriseService(), d.dateEntreeGrade(), d.datePriseService() }) {
            if (x != null && x.isAfter(jour.plusYears(1))) {
                throw new IllegalArgumentException("Date de service trop lointaine");
            }
        }
        if (d.dateCessation() != null && d.datePriseService() != null && d.dateCessation().isBefore(d.datePriseService())) {
            throw new IllegalArgumentException("La cessation précède la prise de service");
        }
        if (d.datePremierePriseService() != null && d.datePriseService() != null
                && d.datePriseService().isBefore(d.datePremierePriseService())) {
            throw new IllegalArgumentException("La prise de service dans l'établissement précède l'entrée dans la fonction");
        }
        String matricule = Personnel.facultatif(d.matricule(), "Matricule", 30);
        jdbc.update("""
                update agent set date_naissance = ?, lieu_naissance = ?, telephone = ?, adresse = ?, matricule = ?,
                       situation = ?, emploi = ?, grade = ?, categorie_echelle = ?, echelon = ?, fonction = ?,
                       date_premiere_prise_service = ?, date_entree_grade = ?, date_prise_service = ?, date_cessation = ?,
                       motif_cessation = ?, observations = ?, modifie_le = now(), modifie_par = ?
                 where id = ?""",
                d.dateNaissance(), Personnel.facultatif(d.lieuNaissance(), "Lieu de naissance", 100),
                Personnel.facultatif(d.telephone(), "Téléphone", 20), Personnel.facultatif(d.adresse(), "Adresse", 200),
                matricule == null ? null : matricule.toUpperCase(java.util.Locale.ROOT),
                d.situation() == null ? null : d.situation().name(), Personnel.facultatif(d.emploi(), "Emploi", 120),
                Personnel.facultatif(d.grade(), "Grade", 60), Personnel.facultatif(d.categorieEchelle(), "Catégorie", 20),
                Personnel.facultatif(d.echelon(), "Échelon", 10), Personnel.facultatif(d.fonction(), "Fonction", 120),
                d.datePremierePriseService(), d.dateEntreeGrade(), d.datePriseService(), d.dateCessation(),
                Personnel.facultatif(d.motifCessation(), "Motif de cessation", 200),
                Personnel.facultatif(d.observations(), "Observations", 1000), UtilisateurConnecte.id(), id);
    }

    @Transactional(readOnly = true)
    public DossierVue dossier(UUID id) {
        Agent a = agents.lisible(id);
        return vue(a, !UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "SECRETARIAT", "CENSEUR"));
    }

    /** « Mon dossier » : le dossier de l'agent connecté, sans les observations internes, actes signés seulement. */
    @Transactional(readOnly = true)
    public DossierVue monDossier() {
        return vue(agents.moi(), true);
    }

    private DossierVue vue(Agent a, boolean personnel) {
        LocalDate jour = aujourdhui();
        LocalDate fin = fin(a, jour);
        Anciennete anc = new Anciennete(Personnel.duree(a.datePremierePriseService(), fin),
                Personnel.duree(a.dateEntreeGrade(), fin), Personnel.duree(a.datePriseService(), fin));
        List<DiplomeVue> diplomes = jdbc.query("""
                select id, nature, intitule, specialite, delivre_par, annee, piece_id from diplome_agent
                where agent_id = ? order by annee desc nulls last, intitule""",
                (r, n) -> new DiplomeVue(r.getObject(1, UUID.class), NatureDiplome.valueOf(r.getString(2)), r.getString(3),
                        r.getString(4), r.getString(5), (Integer) r.getObject(6), r.getObject(7, UUID.class)),
                a.id());
        List<PieceVue> pieces = jdbc.query("""
                select p.id, p.nature, p.reference, p.date_piece, p.objet, p.nom_fichier, p.type_contenu, p.taille,
                       u.nom || ' ' || u.prenoms, p.depose_le
                from piece_agent p left join utilisateur u on u.id = p.depose_par
                where p.agent_id = ? order by p.date_piece desc nulls last, p.depose_le desc""",
                (r, n) -> new PieceVue(r.getObject(1, UUID.class), NaturePiece.valueOf(r.getString(2)), r.getString(3),
                        r.getObject(4, LocalDate.class), r.getString(5), r.getString(6), r.getString(7), r.getInt(8),
                        r.getString(9), r.getObject(10, OffsetDateTime.class).toInstant()),
                a.id());
        List<SignalementVue> signalements = jdbc.query("""
                select s.id, s.texte, us.nom || ' ' || us.prenoms, s.signale_le, ut.nom || ' ' || ut.prenoms,
                       s.traite_le, s.reponse
                from signalement_dossier s left join utilisateur us on us.id = s.signale_par
                left join utilisateur ut on ut.id = s.traite_par
                where s.agent_id = ? order by s.traite_le is not null, s.signale_le desc""",
                (r, n) -> new SignalementVue(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                        r.getObject(4, OffsetDateTime.class).toInstant(), r.getString(5),
                        ActesService.instant(r.getObject(6, OffsetDateTime.class)), r.getString(7)),
                a.id());
        boolean gestion = !personnel && Personnel.gestionnaire();
        boolean chef = !personnel && Personnel.chef();
        return new DossierVue(a.id(), a.categorie(), a.utilisateurId(), a.nom(), a.prenoms(), a.sexe(), a.dateNaissance(),
                a.lieuNaissance(), a.telephone(), a.adresse(), a.matricule(), a.situation(), a.emploi(), a.grade(),
                a.categorieEchelle(), a.echelon(), a.fonction(), a.datePremierePriseService(), a.dateEntreeGrade(),
                a.datePriseService(), a.dateCessation(), a.motifCessation(), personnel ? null : a.observations(), anc,
                manquants(a, diplomes.size()), diplomes, pieces, actes.lister(a.id(), personnel), absences.lister(a.id()),
                signalements, new Droits(gestion, gestion, gestion, chef, chef), a.modifieLe());
    }

    // ------------------------------------------------------------------ diplômes

    @Transactional
    public DossierVue ajouterDiplome(UUID agentId, DonneesDiplome d) {
        Personnel.exigerGestionnaire();
        agents.charger(agentId);
        if (d.nature() == null) {
            throw new IllegalArgumentException("Nature du diplôme obligatoire (académique ou professionnel)");
        }
        if (d.annee() != null && (d.annee() < 1950 || d.annee() > aujourdhui().getYear())) {
            throw new IllegalArgumentException("Année d'obtention invalide");
        }
        if (d.pieceId() != null) {
            exigerPieceDe(d.pieceId(), agentId);
        }
        jdbc.update("""
                insert into diplome_agent (tenant_id, id, agent_id, nature, intitule, specialite, delivre_par, annee, piece_id)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), agentId, d.nature().name(), Personnel.obligatoire(d.intitule(), "Intitulé", 150),
                Personnel.facultatif(d.specialite(), "Spécialité", 120), Personnel.facultatif(d.delivrePar(), "Délivré par", 150),
                d.annee() == null ? null : d.annee().shortValue(), d.pieceId());
        return dossier(agentId);
    }

    @Transactional
    public DossierVue retirerDiplome(UUID diplomeId) {
        Personnel.exigerGestionnaire();
        UUID agentId = jdbc.query("select agent_id from diplome_agent where id = ?", (r, n) -> r.getObject(1, UUID.class),
                diplomeId).stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Diplôme introuvable"));
        jdbc.update("delete from diplome_agent where id = ?", diplomeId);
        return dossier(agentId);
    }

    // ------------------------------------------------------------------ pièces

    @Transactional
    public DossierVue deposerPiece(UUID agentId, NaturePiece nature, String reference, LocalDate datePiece, String objet,
            byte[] contenu, String nomFichier) {
        Personnel.exigerGestionnaire();
        Agent a = agents.charger(agentId);
        if (nature == null) {
            throw new IllegalArgumentException("Nature de la pièce obligatoire");
        }
        Personnel.Fichier f = Personnel.fichier(contenu, nomFichier);
        jdbc.update("""
                insert into piece_agent (tenant_id, id, agent_id, nature, reference, date_piece, objet, fichier,
                                         type_contenu, nom_fichier, taille, depose_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), agentId, nature.name(), Personnel.facultatif(reference, "Référence", 80), datePiece,
                Personnel.obligatoire(objet, "Objet", 200), f.contenu(), f.type(), f.nom(), f.contenu().length,
                UtilisateurConnecte.id());
        audit.enregistrer("PIECE_PERSONNEL_DEPOSEE", a.nomComplet(), Map.of("nature", nature.name()));
        return dossier(agentId);
    }

    /** Pièce : la gestion du dossier, ou l'agent lui-même. Chaque consultation est tracée (données personnelles). */
    @Transactional
    public Telechargement piece(UUID pieceId) {
        UUID agentId = jdbc.query("select agent_id from piece_agent where id = ?", (r, n) -> r.getObject(1, UUID.class),
                pieceId).stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Pièce introuvable"));
        Agent a = agents.charger(agentId);
        if (!Personnel.gestionnaire() && !UtilisateurConnecte.id().equals(a.utilisateurId())) {
            throw new AccesRefuseException("Pièce réservée au secrétariat, au chef d'établissement et à l'intéressé");
        }
        audit.enregistrer("PIECE_PERSONNEL_CONSULTEE", a.nomComplet(), Map.of("piece", pieceId.toString()));
        return jdbc.query("select fichier, type_contenu, nom_fichier from piece_agent where id = ?",
                (r, n) -> new Telechargement(r.getBytes(1), r.getString(2), r.getString(3)), pieceId).get(0);
    }

    @Transactional
    public DossierVue retirerPiece(UUID pieceId) {
        Personnel.exigerGestionnaire();
        UUID agentId = jdbc.query("select agent_id from piece_agent where id = ?", (r, n) -> r.getObject(1, UUID.class),
                pieceId).stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Pièce introuvable"));
        jdbc.update("delete from piece_agent where id = ?", pieceId);
        audit.enregistrer("PIECE_PERSONNEL_RETIREE", agentId.toString(), Map.of("piece", pieceId.toString()));
        return dossier(agentId);
    }

    private void exigerPieceDe(UUID pieceId, UUID agentId) {
        Integer n = jdbc.queryForObject("select count(*) from piece_agent where id = ? and agent_id = ?", Integer.class,
                pieceId, agentId);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("Pièce introuvable dans ce dossier");
        }
    }

    // ------------------------------------------------------------------ signalements

    /** L'agent signale une erreur dans son dossier ; le secrétariat et le chef d'établissement sont prévenus. */
    @Transactional
    public DossierVue signaler(String texte) {
        Agent a = agents.moi();
        jdbc.update("""
                insert into signalement_dossier (tenant_id, id, agent_id, texte, signale_par)
                values (tenant_courant(), ?, ?, ?, ?)""",
                UUID.randomUUID(), a.id(), Personnel.obligatoire(texte, "Texte", 1000), UtilisateurConnecte.id());
        return monDossier();
    }

    @Transactional
    public DossierVue traiterSignalement(UUID signalementId, String reponse) {
        Personnel.exigerGestionnaire();
        UUID agentId = jdbc.query("select agent_id from signalement_dossier where id = ? and traite_le is null",
                (r, n) -> r.getObject(1, UUID.class), signalementId).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Signalement introuvable ou déjà traité"));
        jdbc.update("update signalement_dossier set traite_le = now(), traite_par = ?, reponse = ? where id = ?",
                UtilisateurConnecte.id(), Personnel.obligatoire(reponse, "Réponse", 600), signalementId);
        return dossier(agentId);
    }

    // ------------------------------------------------------------------ outils

    private LocalDate aujourdhui() {
        return LocalDate.now(horloge.withZone(Personnel.FUSEAU));
    }

    private static LocalDate fin(Agent a, LocalDate jour) {
        return a.dateCessation() != null && a.dateCessation().isBefore(jour) ? a.dateCessation() : jour;
    }

    private static String sexe(String s) {
        if (!"M".equals(s) && !"F".equals(s)) {
            throw new IllegalArgumentException("Sexe : M ou F");
        }
        return s;
    }

    /** Ce qui manque au dossier pour établir les actes et calculer l'ancienneté. */
    static List<String> manquants(Agent a, int diplomes) {
        List<String> m = new ArrayList<>();
        if (a.dateNaissance() == null) {
            m.add("date de naissance");
        }
        if (a.datePriseService() == null) {
            m.add("date de prise de service");
        }
        if (a.situation() == Situation.FONCTIONNAIRE && a.matricule() == null) {
            m.add("matricule");
        }
        if (a.situation() == null) {
            m.add("situation administrative");
        }
        if (a.categorie() != Categorie.SOUTIEN && diplomes == 0) {
            m.add("diplômes");
        }
        return m;
    }
}
