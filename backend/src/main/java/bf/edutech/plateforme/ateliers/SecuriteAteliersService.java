package bf.edutech.plateforme.ateliers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.DroitsAtelier;
import bf.edutech.plateforme.messagerie.MessagerieService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembreVue;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Hygiène, sécurité et environnement dans les ateliers (v0.47.2). Le chef d'atelier, garant des normes de sécurité,
 * tient les consignes de son atelier (zones de danger, extincteurs, arrêts d'urgence, EPI obligatoires, consignes
 * propres à une machine) ; chaque enseignant de l'atelier en prend connaissance (briefing) avant d'y demander une
 * séance, et à nouveau quand elles changent. Le chef d'atelier consigne ses observations et interventions (danger
 * imminent, accident) ; un danger imminent ou un accident est signalé aussitôt au chef des travaux et à l'enseignant
 * de la séance.
 */
@Service
public class SecuriteAteliersService {

    private static final DateTimeFormatter HORODATE = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm")
            .withZone(ZoneId.of("Africa/Ouagadougou"));

    public enum CategorieConsigne {
        ZONE_DANGER, EXTINCTEUR, ARRET_URGENCE, EPI, MACHINE, HYGIENE, AUTRE
    }

    public enum Gravite {
        OBSERVATION, DANGER_IMMINENT, ACCIDENT
    }

    public record DonneesConsigne(CategorieConsigne categorie, UUID equipementId, String texte) {
    }

    public record ConsigneVue(UUID id, CategorieConsigne categorie, UUID equipementId, String equipement,
            String texte) {
    }

    public record ConsignesVue(UUID atelierId, List<ConsigneVue> consignes, Instant modifieesLe, boolean aLire,
            Instant luLe, boolean modifier) {
    }

    public record LectureVue(UUID engagementId, String enseignant, String matieres, Instant luLe, boolean aJour) {
    }

    public record DonneesEvenement(UUID seanceId, Instant survenuLe, Gravite gravite, String description,
            String mesures) {
    }

    public record EvenementVue(UUID id, UUID seanceId, String seance, Instant survenuLe, Gravite gravite,
            String description, String mesures, String auteur) {
    }

    private final JdbcTemplate jdbc;
    private final AccesAteliers acces;
    private final AteliersService ateliers;
    private final MembresService membres;
    private final MessagerieService messagerie;
    private final Noms noms;
    private final AuditService audit;
    private final Clock horloge;

    SecuriteAteliersService(JdbcTemplate jdbc, AccesAteliers acces, AteliersService ateliers, MembresService membres,
            MessagerieService messagerie, Noms noms, AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.acces = acces;
        this.ateliers = ateliers;
        this.membres = membres;
        this.messagerie = messagerie;
        this.noms = noms;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ consignes

    @Transactional(readOnly = true)
    public ConsignesVue consignes(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        DroitsAtelier d = acces.exigerLecture(atelierId);
        List<ConsigneVue> liste = jdbc.query("""
                select c.id, c.categorie, c.equipement_id, e.designation || ' (' || e.numero_inventaire || ')', c.texte
                from consigne_securite c
                left join equipement e on e.tenant_id = c.tenant_id and e.id = c.equipement_id
                where c.atelier_id = ? order by c.ordre""", (rs, n) -> new ConsigneVue(rs.getObject(1, UUID.class),
                CategorieConsigne.valueOf(rs.getString(2)), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getString(5)), atelierId);
        Timestamp modifiees = jdbc.queryForObject("select consignes_modifiees_le from atelier where id = ?",
                Timestamp.class, atelierId);
        Instant luLe = null;
        UUID moi = acces.monEngagement().orElse(null);
        if (moi != null) {
            List<Timestamp> l = jdbc.queryForList(
                    "select lu_le from lecture_consignes where atelier_id = ? and engagement_id = ?", Timestamp.class,
                    atelierId, moi);
            luLe = l.isEmpty() ? null : l.get(0).toInstant();
        }
        boolean aLire = moi != null && !d.tenir() && acces.enseignantDe(atelierId) && !liste.isEmpty()
                && (luLe == null || (modifiees != null && luLe.isBefore(modifiees.toInstant())));
        return new ConsignesVue(atelierId, liste, modifiees == null ? null : modifiees.toInstant(), aLire, luLe,
                d.tenir());
    }

    /** Le chef d'atelier remplace les consignes ; chaque enseignant devra en reprendre connaissance. */
    @Transactional
    public ConsignesVue definir(UUID atelierId, List<DonneesConsigne> consignes) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerTenue(atelierId);
        List<DonneesConsigne> liste = consignes == null ? List.of() : consignes;
        if (liste.size() > 100) {
            throw new IllegalArgumentException("100 consignes au plus");
        }
        jdbc.update("delete from consigne_securite where atelier_id = ?", atelierId);
        int ordre = 1;
        for (DonneesConsigne c : liste) {
            if (c == null || c.categorie() == null) {
                throw new IllegalArgumentException("Indiquez la catégorie de chaque consigne");
            }
            String texte = Textes.obligatoire(c.texte(), "Le texte de la consigne", 500);
            if (c.equipementId() != null) {
                Integer n = jdbc.queryForObject("select count(*) from equipement where id = ? and atelier_id = ?",
                        Integer.class, c.equipementId(), atelierId);
                if (n == null || n == 0) {
                    throw new IllegalArgumentException("Équipement inconnu dans cet atelier");
                }
            }
            jdbc.update("""
                    insert into consigne_securite (tenant_id, id, atelier_id, categorie, equipement_id, texte, ordre)
                    values (tenant_courant(), ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), atelierId, c.categorie().name(),
                    c.equipementId(), texte, ordre++);
        }
        jdbc.update("update atelier set consignes_modifiees_le = ? where id = ?", Timestamp.from(horloge.instant()),
                atelierId);
        audit.enregistrer("CONSIGNES_SECURITE", atelierId.toString(), Map.of("consignes", liste.size()));
        return consignes(atelierId);
    }

    /** L'enseignant atteste avoir pris connaissance des consignes (briefing du chef d'atelier). */
    @Transactional
    public ConsignesVue lire(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        UUID moi = acces.monEngagement().orElse(null);
        if (moi == null || !acces.enseignantDe(atelierId)) {
            throw new AccesRefuseException("Réservé aux enseignants de l'atelier");
        }
        jdbc.update("""
                insert into lecture_consignes (tenant_id, atelier_id, engagement_id, lu_le)
                values (tenant_courant(), ?, ?, ?)
                on conflict (tenant_id, atelier_id, engagement_id) do update set lu_le = excluded.lu_le""",
                atelierId, moi, Timestamp.from(horloge.instant()));
        return consignes(atelierId);
    }

    /** Qui a pris connaissance des consignes en vigueur (chef d'atelier, chef des travaux). */
    @Transactional(readOnly = true)
    public List<LectureVue> lectures(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerTenue(atelierId);
        Map<UUID, java.util.Set<String>> techniques = acces.enseignantsTechniques(acces.filieresDe(atelierId));
        Timestamp modifiees = jdbc.queryForObject("select consignes_modifiees_le from atelier where id = ?",
                Timestamp.class, atelierId);
        Map<UUID, Instant> lus = new HashMap<>();
        jdbc.query("select engagement_id, lu_le from lecture_consignes where atelier_id = ?",
                (java.sql.ResultSet rs) -> {
                    lus.put(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant());
                }, atelierId);
        Map<UUID, String> nomsEns = noms.engagements(techniques.keySet());
        List<LectureVue> r = new ArrayList<>();
        techniques.forEach((engagement, matieres) -> {
            Instant lu = lus.get(engagement);
            boolean aJour = lu != null && (modifiees == null || !lu.isBefore(modifiees.toInstant()));
            r.add(new LectureVue(engagement, nomsEns.get(engagement), String.join(", ", matieres), lu, aJour));
        });
        r.sort(java.util.Comparator.comparing((LectureVue l) -> l.enseignant() == null ? "" : l.enseignant()));
        return r;
    }

    // ------------------------------------------------------------------ journal HSE

    @Transactional(readOnly = true)
    public List<EvenementVue> journal(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerLecture(atelierId);
        Map<UUID, String> utilisateurs = noms.utilisateurs();
        return jdbc.query("""
                select h.id, h.seance_id, c.code || ' · ' || to_char(s.date_seance, 'DD/MM/YYYY') || ' · ' || s.titre,
                       h.survenu_le, h.gravite, h.description, h.mesures, h.auteur
                from evenement_hse h
                left join seance_tp s on s.tenant_id = h.tenant_id and s.id = h.seance_id
                left join classe c on c.tenant_id = s.tenant_id and c.id = s.classe_id
                where h.atelier_id = ? order by h.survenu_le desc limit 200""", (rs, n) -> new EvenementVue(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getTimestamp(4).toInstant(), Gravite.valueOf(rs.getString(5)), rs.getString(6), rs.getString(7),
                utilisateurs.get(rs.getObject(8, UUID.class))), atelierId);
    }

    /** Observation ou intervention du chef d'atelier ; danger imminent ou accident : alerte immédiate. */
    @Transactional
    public EvenementVue consigner(UUID atelierId, DonneesEvenement d) {
        UtilisateurConnecte.etablissementActif();
        Atelier atelier = ateliers.charger(atelierId);
        acces.exigerTenue(atelierId);
        if (d == null || d.gravite() == null) {
            throw new IllegalArgumentException("Indiquez la gravité (observation, danger imminent, accident)");
        }
        String description = Textes.obligatoire(d.description(), "La description", 1000);
        String mesures = Textes.facultatif(d.mesures(), "Les mesures prises", 1000);
        Instant quand = d.survenuLe() != null ? d.survenuLe() : horloge.instant();
        if (quand.isAfter(horloge.instant().plusSeconds(300))) {
            throw new IllegalArgumentException("L'événement ne peut pas être dans le futur");
        }
        UUID engagementSeance = null;
        if (d.seanceId() != null) {
            List<Map<String, Object>> s = jdbc.queryForList(
                    "select atelier_id, engagement_id from seance_tp where id = ?", d.seanceId());
            if (s.isEmpty() || !atelierId.equals(s.get(0).get("atelier_id"))) {
                throw new IllegalArgumentException("Séance inconnue dans cet atelier");
            }
            engagementSeance = (UUID) s.get(0).get("engagement_id");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into evenement_hse (tenant_id, id, atelier_id, seance_id, survenu_le, gravite, description,
                    mesures, auteur) values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?)""", id, atelierId, d.seanceId(),
                Timestamp.from(quand), d.gravite().name(), description, mesures, UtilisateurConnecte.id());
        audit.enregistrer("EVENEMENT_HSE", atelier.getCode(), Map.of("gravite", d.gravite().name()));
        if (d.gravite() != Gravite.OBSERVATION) {
            alerter(atelier, d.gravite(), description, mesures, quand, engagementSeance);
        }
        return journal(atelierId).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    private void alerter(Atelier atelier, Gravite gravite, String description, String mesures, Instant quand,
            UUID engagementSeance) {
        String objet = (gravite == Gravite.ACCIDENT ? "ACCIDENT" : "DANGER IMMINENT") + " — atelier "
                + atelier.getCode();
        String corps = gravite.name().replace('_', ' ').toLowerCase(Locale.ROOT) + " le " + HORODATE.format(quand)
                + " dans l'atelier " + atelier.getNom() + " : " + description
                + (mesures == null ? "" : "\nMesures prises : " + mesures);
        java.util.Set<UUID> destinataires = new java.util.LinkedHashSet<>();
        for (MembreVue m : membres.lister()) {
            if (m.actif() && (m.role() == Role.CHEF_TRAVAUX || (m.role() == Role.ADMIN_ECOLE
                    && !acces.chefDesTravauxEnFonction()))) {
                destinataires.add(m.utilisateurId());
            }
        }
        if (engagementSeance != null) {
            UUID e = engagementSeance;
            jdbc.query("""
                    select s.utilisateur_id from engagement_enseignant g join enseignant s on s.id = g.enseignant_id
                    where g.id = ?""", (java.sql.ResultSet rs) -> {
                destinataires.add(rs.getObject(1, UUID.class));
            }, e);
        }
        destinataires.remove(UtilisateurConnecte.idSiConnecte().orElse(null));
        for (UUID u : destinataires) {
            try {
                messagerie.transmettre(u, objet, corps, "Sécurité de l'atelier " + atelier.getCode());
            } catch (IllegalArgumentException e) {
                // Compte qui n'est plus membre du personnel
            }
        }
    }
}
