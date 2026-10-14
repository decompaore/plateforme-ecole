package bf.edutech.plateforme.eleves;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Dossier d'inscription (v0.47) : le mode d'admission de l'élève et les pièces reçues par l'intendance.
 * <p>
 * Les pièces attendues se règlent par établissement (liste par défaut tirée de la pratique des lycées du Burkina
 * Faso) et dépendent du mode d'admission : un élève affecté par l'État n'apporte que la décision d'affectation et
 * son état civil ; un candidat au complément d'effectif un dossier complet. L'intendant enregistre les pièces
 * reçues ; une fois enregistrées, seul le chef d'établissement peut en retirer ou changer le mode d'admission.
 */
@Service
public class DossiersInscriptionService {

    public record PieceVue(UUID id, String libelle, List<ModeAdmission> modes, boolean obligatoire, int ordre) {
    }

    public record DonneesPiece(UUID id, String libelle, List<ModeAdmission> modes, Boolean obligatoire) {
    }

    public record PieceDossierVue(UUID pieceId, String libelle, boolean obligatoire, boolean recue, LocalDate recueLe) {
    }

    public record DossierInscriptionVue(UUID inscriptionId, ModeAdmission modeAdmission, String modeLibelle,
            List<PieceDossierVue> pieces, boolean complet, int manquantes) {
    }

    public record LigneDossierVue(UUID inscriptionId, UUID eleveId, String matricule, String nom, String prenoms,
            String classeCode, ModeAdmission modeAdmission, List<String> manquantes) {
    }

    private record Defaut(String libelle, boolean obligatoire, ModeAdmission... modes) {
    }

    private static final ModeAdmission[] ENTREES = { ModeAdmission.AFFECTATION_ETAT, ModeAdmission.COMPLEMENT_EFFECTIF,
            ModeAdmission.TRANSFERT, ModeAdmission.PRIVE, ModeAdmission.AUTRE };

    /** Pièces usuelles d'un dossier d'inscription dans un lycée du Burkina Faso. */
    private static final List<Defaut> DEFAUTS = List.of(
            new Defaut("Demande de place adressée au chef d'établissement", true, ModeAdmission.COMPLEMENT_EFFECTIF,
                    ModeAdmission.TRANSFERT, ModeAdmission.PRIVE, ModeAdmission.AUTRE),
            new Defaut("Extrait d'acte de naissance légalisé (ou jugement supplétif)", true, ENTREES),
            new Defaut("Décision d'affectation de l'État (orientation)", true, ModeAdmission.AFFECTATION_ETAT),
            new Defaut("Bulletins de notes de l'année écoulée", true, ModeAdmission.COMPLEMENT_EFFECTIF,
                    ModeAdmission.TRANSFERT, ModeAdmission.PRIVE, ModeAdmission.AUTRE),
            new Defaut("Certificat de scolarité de l'établissement d'origine", true, ModeAdmission.COMPLEMENT_EFFECTIF,
                    ModeAdmission.TRANSFERT),
            new Defaut("Copie légalisée du CEP ou du BEPC (entrée en 6e ou en 2nde)", false,
                    ModeAdmission.COMPLEMENT_EFFECTIF, ModeAdmission.PRIVE),
            new Defaut("Certificat de non-redevance", true, ModeAdmission.COMPLEMENT_EFFECTIF, ModeAdmission.TRANSFERT),
            new Defaut("Photos d'identité", true, ModeAdmission.values()));

    private final JdbcTemplate jdbc;
    private final InscriptionRepository inscriptions;
    private final RegistreInscriptions registre;
    private final AuditService audit;
    private final Clock horloge;

    DossiersInscriptionService(JdbcTemplate jdbc, InscriptionRepository inscriptions, RegistreInscriptions registre,
            AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.inscriptions = inscriptions;
        this.registre = registre;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ pièces attendues

    /** Pièces attendues de l'établissement ; la liste par défaut est créée à la première consultation. */
    @Transactional
    public List<PieceVue> pieces() {
        UtilisateurConnecte.etablissementActif();
        List<PieceVue> liste = lirePieces();
        if (!liste.isEmpty() || existeUnePiece()) {
            return liste;
        }
        int ordre = 1;
        for (Defaut d : DEFAUTS) {
            jdbc.update("""
                    insert into piece_dossier (tenant_id, id, libelle, modes, obligatoire, ordre)
                    values (tenant_courant(), ?, ?, ?, ?, ?) on conflict do nothing""",
                    UUID.randomUUID(), d.libelle(), modes(Arrays.asList(d.modes())), d.obligatoire(), ordre++);
        }
        return lirePieces();
    }

    /** Remplace la liste des pièces attendues (chef d'établissement) ; une pièce retirée n'est plus demandée. */
    @Transactional
    public List<PieceVue> definirPieces(List<DonneesPiece> donnees) {
        pieces();
        if (donnees == null || donnees.isEmpty()) {
            throw new IllegalArgumentException("Indiquez au moins une pièce");
        }
        Set<String> libelles = new HashSet<>();
        Set<UUID> gardees = new HashSet<>();
        int ordre = 1;
        for (DonneesPiece d : donnees) {
            String libelle = d.libelle() == null ? "" : d.libelle().strip();
            if (libelle.isEmpty() || libelle.length() > 150) {
                throw new IllegalArgumentException("Le libellé d'une pièce est obligatoire (150 caractères au plus)");
            }
            if (!libelles.add(libelle.toUpperCase())) {
                throw new IllegalArgumentException("La pièce « " + libelle + " » figure deux fois");
            }
            if (d.modes() == null || d.modes().isEmpty()) {
                throw new IllegalArgumentException("Indiquez pour quels modes d'admission « " + libelle
                        + " » est demandée");
            }
            boolean obligatoire = !Boolean.FALSE.equals(d.obligatoire());
            int modifiees = d.id() == null ? 0 : jdbc.update("""
                    update piece_dossier set libelle = ?, modes = ?, obligatoire = ?, ordre = ?, actif = true
                    where id = ?""", libelle, modes(d.modes()), obligatoire, ordre, d.id());
            UUID id = d.id();
            if (modifiees == 0) {
                id = UUID.randomUUID();
                // Une pièce retirée puis remise sous le même libellé est réactivée
                int reactivees = jdbc.update("""
                        update piece_dossier set modes = ?, obligatoire = ?, ordre = ?, actif = true
                        where upper(libelle) = upper(?)""", modes(d.modes()), obligatoire, ordre, libelle);
                if (reactivees == 0) {
                    jdbc.update("""
                            insert into piece_dossier (tenant_id, id, libelle, modes, obligatoire, ordre)
                            values (tenant_courant(), ?, ?, ?, ?, ?)""", id, libelle, modes(d.modes()), obligatoire,
                            ordre);
                } else {
                    id = jdbc.queryForObject("select id from piece_dossier where upper(libelle) = upper(?)",
                            UUID.class, libelle);
                }
            }
            gardees.add(id);
            ordre++;
        }
        for (PieceVue p : lirePieces()) {
            if (!gardees.contains(p.id())) {
                jdbc.update("update piece_dossier set actif = false where id = ?", p.id());
            }
        }
        audit.enregistrer("PIECES_DOSSIER_MODIFIEES", "pièces du dossier", Map.of("pieces", gardees.size()));
        return lirePieces();
    }

    // ------------------------------------------------------------------ dossier d'un élève

    @Transactional
    public DossierInscriptionVue dossier(UUID inscriptionId) {
        Inscription inscription = charger(inscriptionId);
        return vue(inscription, pieces(), recues(List.of(inscriptionId)).getOrDefault(inscriptionId, Map.of()));
    }

    /**
     * Enregistre le mode d'admission et les pièces reçues. L'intendant complète le dossier (mode encore non
     * renseigné, nouvelles pièces) ; retirer une pièce ou changer le mode est réservé au chef d'établissement.
     */
    @Transactional
    public DossierInscriptionVue enregistrer(UUID inscriptionId, ModeAdmission mode, List<UUID> piecesRecues) {
        Inscription inscription = charger(inscriptionId);
        registre.anneeOuverte(inscription.getAnneeId());
        boolean chef = UtilisateurConnecte.aUnRole("ADMIN_ECOLE");
        List<PieceVue> attendues = pieces();
        Map<UUID, LocalDate> avant = recues(List.of(inscriptionId)).getOrDefault(inscriptionId, Map.of());
        Set<UUID> voulues = new HashSet<>(piecesRecues == null ? List.of() : piecesRecues);
        Set<UUID> connues = new HashSet<>(attendues.stream().map(PieceVue::id).toList());
        for (UUID p : voulues) {
            if (!connues.contains(p)) {
                throw new IllegalArgumentException("Pièce inconnue ou retirée de la liste de l'établissement");
            }
        }
        if (mode != null && mode != inscription.getModeAdmission()) {
            if (inscription.getModeAdmission() != null && !chef) {
                throw new AccesRefuseException("Le mode d'admission est enregistré : seul le chef d'établissement "
                        + "peut le changer");
            }
            audit.enregistrer("MODE_ADMISSION", inscriptionId.toString(), Map.of("mode", mode.name()));
            inscription.changerModeAdmission(mode);
        }
        List<UUID> retirees = avant.keySet().stream().filter(p -> !voulues.contains(p)).toList();
        if (!retirees.isEmpty() && !chef) {
            throw new AccesRefuseException("Une pièce déjà enregistrée ne peut être retirée que par le chef "
                    + "d'établissement");
        }
        for (UUID p : retirees) {
            jdbc.update("delete from piece_recue where inscription_id = ? and piece_id = ?", inscriptionId, p);
        }
        LocalDate aujourdhui = LocalDate.now(horloge);
        for (UUID p : voulues) {
            if (!avant.containsKey(p)) {
                jdbc.update("""
                        insert into piece_recue (tenant_id, inscription_id, piece_id, recue_le, saisie_par)
                        values (tenant_courant(), ?, ?, ?, ?)""", inscriptionId, p, Date.valueOf(aujourdhui),
                        UtilisateurConnecte.idSiConnecte().orElse(null));
            }
        }
        return vue(inscription, attendues, recues(List.of(inscriptionId)).getOrDefault(inscriptionId, Map.of()));
    }

    /** Dossiers d'une année (élèves inscrits), avec les pièces manquantes ; {@code incomplets} : seulement eux. */
    @Transactional
    public List<LigneDossierVue> dossiers(UUID anneeId, boolean incomplets) {
        List<PieceVue> attendues = pieces();
        List<LigneDossierVue> lignes = new ArrayList<>();
        Map<UUID, Object[]> parInscription = new LinkedHashMap<>();
        jdbc.query("""
                select i.id, i.eleve_id, e.matricule, e.nom, e.prenoms, c.code, i.mode_admission
                from inscription i
                join eleve e on e.tenant_id = i.tenant_id and e.id = i.eleve_id
                join classe c on c.tenant_id = i.tenant_id and c.id = i.classe_id
                where i.annee_id = ? and i.statut = 'ACTIVE'
                order by c.code, e.nom, e.prenoms""", (java.sql.ResultSet rs) -> {
            parInscription.put(rs.getObject(1, UUID.class), new Object[] { rs.getObject(2, UUID.class),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7) });
        }, anneeId);
        Map<UUID, Map<UUID, LocalDate>> recues = recues(parInscription.keySet());
        parInscription.forEach((id, l) -> {
            ModeAdmission mode = l[5] == null ? null : ModeAdmission.valueOf((String) l[5]);
            List<String> manquantes = manquantes(mode, attendues, recues.getOrDefault(id, Map.of()));
            if (!incomplets || !manquantes.isEmpty()) {
                lignes.add(new LigneDossierVue(id, (UUID) l[0], (String) l[1], (String) l[2], (String) l[3],
                        (String) l[4], mode, manquantes));
            }
        });
        return lignes;
    }

    // ------------------------------------------------------------------

    private DossierInscriptionVue vue(Inscription i, List<PieceVue> attendues, Map<UUID, LocalDate> recues) {
        ModeAdmission mode = i.getModeAdmission();
        List<PieceDossierVue> pieces = attendues.stream()
                .filter(p -> mode == null || p.modes().contains(mode) || recues.containsKey(p.id()))
                .map(p -> new PieceDossierVue(p.id(), p.libelle(), mode != null && p.modes().contains(mode)
                        && p.obligatoire(), recues.containsKey(p.id()), recues.get(p.id())))
                .toList();
        List<String> manquantes = manquantes(mode, attendues, recues);
        return new DossierInscriptionVue(i.getId(), mode, mode == null ? null : mode.libelle(), pieces,
                manquantes.isEmpty(), manquantes.size());
    }

    /** Pièces obligatoires non reçues ; sans mode d'admission, le dossier n'est pas complet. */
    private static List<String> manquantes(ModeAdmission mode, List<PieceVue> attendues, Map<UUID, LocalDate> recues) {
        if (mode == null) {
            return List.of("Mode d'admission à préciser");
        }
        return attendues.stream()
                .filter(p -> p.obligatoire() && p.modes().contains(mode) && !recues.containsKey(p.id()))
                .map(PieceVue::libelle).toList();
    }

    private Map<UUID, Map<UUID, LocalDate>> recues(java.util.Collection<UUID> inscriptionIds) {
        Map<UUID, Map<UUID, LocalDate>> r = new HashMap<>();
        if (inscriptionIds.isEmpty()) {
            return r;
        }
        String tableau = String.join(",", inscriptionIds.stream().map(UUID::toString).toList());
        jdbc.query("select inscription_id, piece_id, recue_le from piece_recue where inscription_id = any (?::uuid[])",
                (java.sql.ResultSet rs) -> {
                    r.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>())
                            .put(rs.getObject(2, UUID.class), rs.getDate(3).toLocalDate());
                }, "{" + tableau + "}");
        return r;
    }

    private List<PieceVue> lirePieces() {
        return jdbc.query("select id, libelle, modes, obligatoire, ordre from piece_dossier where actif order by ordre",
                (rs, n) -> new PieceVue(rs.getObject(1, UUID.class), rs.getString(2), lireModes(rs.getString(3)),
                        rs.getBoolean(4), rs.getInt(5)))
                .stream().sorted(Comparator.comparingInt(PieceVue::ordre)).toList();
    }

    private boolean existeUnePiece() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from piece_dossier)", Boolean.class));
    }

    private Inscription charger(UUID id) {
        UtilisateurConnecte.etablissementActif();
        return inscriptions.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Inscription introuvable"));
    }

    private static String modes(List<ModeAdmission> modes) {
        return String.join(",", modes.stream().distinct().map(Enum::name).toList());
    }

    private static List<ModeAdmission> lireModes(String texte) {
        return Arrays.stream(texte.split(",")).filter(s -> !s.isBlank()).map(ModeAdmission::valueOf).toList();
    }
}
