package bf.edutech.plateforme.personnel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.personnel.Agents.Agent;
import bf.edutech.plateforme.personnel.Vues.ActeVue;
import bf.edutech.plateforme.personnel.Vues.TypeAbsence;
import bf.edutech.plateforme.personnel.Vues.TypeActe;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.documents.RenduDocument;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;
import bf.edutech.plateforme.utilisateurs.Signatures;

/**
 * Actes administratifs établis par l'établissement pour un agent (v0.51). Le secrétariat les prépare
 * (projets) ; le chef d'établissement les signe : l'acte reçoit alors son numéro ({@code AP-2026-0001})
 * et le texte est figé, pour que chaque réimpression soit identique. Un acte signé ne se modifie pas ; il
 * s'annule, avec un motif, et la mention figure sur le document.
 */
@Service
public class ActesService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    record ActeStocke(UUID id, UUID agentId, TypeActe type, String numero, String titre, String texte,
            UUID signePar, Instant signeLe, String signataire, Instant annuleLe, String motifAnnulation) {
    }

    private final JdbcTemplate jdbc;
    private final Agents agents;
    private final Compteurs compteurs;
    private final RenduDocument rendu;
    private final EntetesOfficiels entetes;
    private final MembresService membres;
    private final Signatures signatures;
    private final AuditService audit;
    private final Clock horloge;

    ActesService(JdbcTemplate jdbc, Agents agents, Compteurs compteurs, RenduDocument rendu, EntetesOfficiels entetes,
            MembresService membres, Signatures signatures, AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.compteurs = compteurs;
        this.rendu = rendu;
        this.entetes = entetes;
        this.membres = membres;
        this.signatures = signatures;
        this.audit = audit;
        this.horloge = horloge;
    }

    List<ActeVue> lister(UUID agentId, boolean seulementSignes) {
        return jdbc.query("""
                select a.id, a.type, a.numero, a.titre, a.date_effet, ue.nom || ' ' || ue.prenoms, a.etabli_le,
                       a.signataire, a.signe_le, a.annule_le, a.motif_annulation, a.absence_id
                from acte_agent a left join utilisateur ue on ue.id = a.etabli_par
                where a.agent_id = ? and (a.signe_le is not null or not ?)
                order by a.etabli_le desc""",
                (r, n) -> new ActeVue(r.getObject(1, UUID.class), TypeActe.valueOf(r.getString(2)), r.getString(3),
                        r.getString(4), r.getObject(5, LocalDate.class), r.getString(6), instant(r.getObject(7, OffsetDateTime.class)),
                        r.getString(8), instant(r.getObject(9, OffsetDateTime.class)),
                        instant(r.getObject(10, OffsetDateTime.class)), r.getString(11), r.getObject(12, UUID.class)),
                agentId, seulementSignes);
    }

    /**
     * Prépare un acte. {@code dateEffet} : date de prise ou de cessation de service ; {@code complement} : motif
     * de la cessation, ou référence de l'acte d'affectation pour une prise de service. Établi par le chef
     * d'établissement, l'acte est signé aussitôt.
     */
    @Transactional
    public ActeVue etablir(UUID agentId, TypeActe type, LocalDate dateEffet, String complement) {
        Personnel.exigerGestionnaire();
        if (type == TypeActe.AUTORISATION_ABSENCE) {
            throw new IllegalArgumentException("L'autorisation d'absence s'établit en accordant la demande d'absence");
        }
        Agent a = agents.charger(agentId);
        String c = Personnel.facultatif(complement, "Complément", 300);
        LocalDate effet = switch (type) {
            case PRISE_SERVICE -> dateEffet != null ? dateEffet : a.datePriseService();
            case CESSATION_SERVICE -> dateEffet != null ? dateEffet : a.dateCessation();
            default -> null;
        };
        if ((type == TypeActe.PRISE_SERVICE || type == TypeActe.CESSATION_SERVICE) && effet == null) {
            throw new IllegalArgumentException("Date d'effet obligatoire");
        }
        if (type == TypeActe.CESSATION_SERVICE && c == null && a.motifCessation() == null) {
            throw new IllegalArgumentException("Motif de la cessation obligatoire (mutation, retraite, fin de contrat…)");
        }
        if (type == TypeActe.PRESENCE_POSTE && a.dateCessation() != null) {
            throw new RegleMetierException("AGENT_PARTI", "L'agent a cessé service : établissez une attestation de travail");
        }
        if ((type == TypeActe.PRESENCE_POSTE || type == TypeActe.ATTESTATION_TRAVAIL) && a.datePriseService() == null) {
            throw new RegleMetierException("PRISE_SERVICE_INCONNUE",
                    "Renseignez d'abord la date de prise de service dans l'établissement");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into acte_agent (tenant_id, id, agent_id, type, titre, texte, date_effet, etabli_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?)""",
                id, agentId, type.name(), type.titre, texte(a, type, effet, c), effet, UtilisateurConnecte.id());
        audit.enregistrer("ACTE_PERSONNEL_ETABLI", type.name(), Map.of("agent", a.nomComplet()));
        if (Personnel.chef()) {
            signerInterne(id);
        }
        return vue(id);
    }

    /** Autorisation d'absence pour une absence accordée par le chef d'établissement : signée aussitôt. */
    UUID etablirAutorisation(Agent a, UUID absenceId, TypeAbsence type, LocalDate debut, LocalDate fin, int jours,
            LocalDate reprise, String motif) {
        UUID id = UUID.randomUUID();
        String civ = Personnel.civilite(a.sexe());
        String texte = "Une autorisation d'absence de " + jours + (jours > 1 ? " jours" : " jour") + ", au titre : "
                + type.libelle + ", est accordée à " + civ + " " + a.nomComplet() + identite(a)
                + ", du " + debut.format(JOUR) + " au " + fin.format(JOUR) + " inclus.\n\nMotif : " + motif + "\n\n"
                + "L'intéressé" + Personnel.accord(a.sexe(), "", "e") + " reprendra service le " + reprise.format(JOUR)
                + ". Toute prolongation doit faire l'objet d'une nouvelle demande.";
        jdbc.update("""
                insert into acte_agent (tenant_id, id, agent_id, type, titre, texte, date_effet, absence_id, etabli_par)
                values (tenant_courant(), ?, ?, 'AUTORISATION_ABSENCE', ?, ?, ?, ?, ?)""",
                id, a.id(), TypeActe.AUTORISATION_ABSENCE.titre, texte, debut, absenceId, UtilisateurConnecte.id());
        signerInterne(id);
        return id;
    }

    @Transactional
    public ActeVue signer(UUID acteId) {
        Personnel.exigerChef();
        signerInterne(acteId);
        return vue(acteId);
    }

    private void signerInterne(UUID acteId) {
        ActeStocke a = stocke(acteId);
        if (a.signeLe() != null) {
            throw new RegleMetierException("ACTE_SIGNE", "Cet acte est déjà signé");
        }
        if (a.annuleLe() != null) {
            throw new RegleMetierException("ACTE_ANNULE", "Cet acte est annulé");
        }
        LocalDate jour = LocalDate.now(horloge.withZone(Personnel.FUSEAU));
        String numero = "AP-" + jour.getYear() + "-" + String.format("%04d", compteurs.suivant("acte-personnel:" + jour.getYear()));
        UUID moi = UtilisateurConnecte.id();
        String signataire = membres.lister().stream()
                .filter(m -> m.actif() && m.role() == Role.ADMIN_ECOLE && m.utilisateurId().equals(moi)).findFirst()
                .map(m -> m.prenoms() + " " + m.nom()).orElse(null);
        jdbc.update("""
                update acte_agent set numero = ?, signe_par = ?, signe_le = now(), signataire = ? where id = ?""",
                numero, moi, signataire, acteId);
        // Effets sur le dossier : dates de prise ou de cessation de service si elles manquent
        Agent ag = agents.charger(a.agentId());
        LocalDate effet = jdbc.queryForObject("select date_effet from acte_agent where id = ?", LocalDate.class, acteId);
        if (a.type() == TypeActe.PRISE_SERVICE && ag.datePriseService() == null) {
            jdbc.update("update agent set date_prise_service = ?, modifie_le = now() where id = ?", effet, ag.id());
        }
        if (a.type() == TypeActe.CESSATION_SERVICE && ag.dateCessation() == null) {
            jdbc.update("update agent set date_cessation = ?, modifie_le = now() where id = ?", effet, ag.id());
        }
        audit.enregistrer("ACTE_PERSONNEL_SIGNE", numero, Map.of("agent", ag.nomComplet()));
    }

    @Transactional
    public ActeVue annuler(UUID acteId, String motif) {
        Personnel.exigerChef();
        String m = Personnel.obligatoire(motif, "Motif de l'annulation", 300);
        ActeStocke a = stocke(acteId);
        if (a.annuleLe() != null) {
            throw new RegleMetierException("ACTE_ANNULE", "Cet acte est déjà annulé");
        }
        annulerInterne(acteId, m);
        return vue(acteId);
    }

    void annulerInterne(UUID acteId, String motif) {
        jdbc.update("""
                update acte_agent set annule_le = now(), annule_par = ?, motif_annulation = ?
                 where id = ? and annule_le is null""", UtilisateurConnecte.id(), motif, acteId);
        audit.enregistrer("ACTE_PERSONNEL_ANNULE", acteId.toString(), Map.of("motif", motif));
    }

    /** Document PDF : gestion du dossier, ou l'agent lui-même pour un acte signé. */
    @Transactional(readOnly = true)
    public Document pdf(UUID acteId) {
        ActeStocke a = stocke(acteId);
        Agent ag = agents.lisible(a.agentId());
        boolean proprietaire = UtilisateurConnecte.id().equals(ag.utilisateurId());
        if (!Personnel.gestionnaire() && !(proprietaire && a.signeLe() != null)) {
            throw new AccesRefuseException("Acte non disponible");
        }
        byte[] signature = a.signePar() == null || a.annuleLe() != null ? null
                : signatures.signature(a.signePar()).orElse(null);
        LocalDate date = a.signeLe() != null ? a.signeLe().atZone(Personnel.FUSEAU).toLocalDate()
                : LocalDate.now(horloge.withZone(Personnel.FUSEAU));
        String pied = a.annuleLe() != null ? "ACTE ANNULÉ le " + a.annuleLe().atZone(Personnel.FUSEAU).toLocalDate().format(JOUR)
                + " : " + a.motifAnnulation() : null;
        byte[] contenu = rendu.rendre(new RenduDocument.Contenu(a.numero(), a.titre(), List.of(), a.texte(), date,
                "Le Chef d'établissement", a.signataire(), signature, a.signeLe() == null, pied));
        String nom = (a.numero() != null ? a.numero() : "projet") + "-" + a.type().name().toLowerCase().replace('_', '-')
                + ".pdf";
        return new Document(contenu, nom);
    }

    public record Document(byte[] contenu, String nom) {
    }

    // ------------------------------------------------------------------ textes

    String texte(Agent a, TypeActe type, LocalDate effet, String complement) {
        String etab = entetes.courant().etablissement();
        String civ = Personnel.civilite(a.sexe());
        String soussigne = "Je soussigné(e), Chef d'établissement du " + etab + ", ";
        String qui = civ + " " + a.nomComplet() + identite(a);
        String fin = "\n\nEn foi de quoi, le présent " + (type.titre.startsWith("Certificat") ? "certificat" : "document")
                + " lui est délivré pour servir et valoir ce que de droit.";
        return switch (type) {
            case PRISE_SERVICE -> soussigne + "certifie que " + qui + ", "
                    + (complement != null ? "affecté" + Personnel.accord(a.sexe(), "", "e") + " par " + complement + ", " : "")
                    + "a effectivement pris service dans l'établissement le " + effet.format(JOUR)
                    + (a.fonction() != null ? " en qualité de " + a.fonction() : "") + "." + fin;
            case CESSATION_SERVICE -> soussigne + "certifie que " + qui + ", en service dans l'établissement"
                    + (a.datePriseService() != null ? " depuis le " + a.datePriseService().format(JOUR) : "")
                    + ", a cessé service le " + effet.format(JOUR) + ".\n\nMotif : "
                    + (complement != null ? complement : a.motifCessation()) + "." + fin;
            case PRESENCE_POSTE -> soussigne + "atteste que " + qui + " est en service dans l'établissement depuis le "
                    + a.datePriseService().format(JOUR) + (a.fonction() != null ? " en qualité de " + a.fonction() : "")
                    + " et y exerce effectivement ses fonctions à la date de la présente attestation." + fin;
            case ATTESTATION_TRAVAIL -> soussigne + "atteste que " + qui + " "
                    + (a.dateCessation() == null ? "travaille dans l'établissement depuis le " + a.datePriseService().format(JOUR)
                            : "a travaillé dans l'établissement du " + a.datePriseService().format(JOUR) + " au "
                                    + a.dateCessation().format(JOUR))
                    + (a.fonction() != null ? " en qualité de " + a.fonction()
                            : a.emploi() != null ? " en qualité de " + a.emploi() : "")
                    + "." + fin;
            case AUTORISATION_ABSENCE -> throw new IllegalStateException();
        };
    }

    /** « , Professeur certifié, matricule 123456 A, catégorie A1, échelon 3 » selon ce qui est renseigné. */
    static String identite(Agent a) {
        StringBuilder s = new StringBuilder();
        if (a.emploi() != null) {
            s.append(", ").append(a.emploi());
        }
        if (a.matricule() != null) {
            s.append(", matricule ").append(a.matricule());
        }
        if (a.grade() != null) {
            s.append(", grade ").append(a.grade());
        }
        if (a.categorieEchelle() != null) {
            s.append(", catégorie ").append(a.categorieEchelle());
        }
        if (a.echelon() != null) {
            s.append(", échelon ").append(a.echelon());
        }
        return s.toString();
    }

    // ------------------------------------------------------------------ lecture

    ActeStocke stocke(UUID id) {
        return jdbc.query("""
                select id, agent_id, type, numero, titre, texte, signe_par, signe_le, signataire, annule_le, motif_annulation
                from acte_agent where id = ?""",
                (r, n) -> new ActeStocke(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                        TypeActe.valueOf(r.getString(3)), r.getString(4), r.getString(5), r.getString(6),
                        r.getObject(7, UUID.class), instant(r.getObject(8, OffsetDateTime.class)), r.getString(9),
                        instant(r.getObject(10, OffsetDateTime.class)), r.getString(11)),
                id).stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Acte introuvable"));
    }

    ActeVue vue(UUID id) {
        UUID agent = stocke(id).agentId();
        return lister(agent, false).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
