package bf.edutech.plateforme.reversibilite;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Trace d'un export complet des données de l'établissement. */
@Entity
@Table(name = "export_donnees")
class ExportDonnees extends EntiteCloisonnee {

    @Column(name = "demande_par", updatable = false)
    private UUID demandePar;

    @Column(name = "par_plateforme", nullable = false, updatable = false)
    private boolean parPlateforme;

    @Column(name = "demande_le", nullable = false, updatable = false)
    private Instant demandeLe;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private StatutExport statut = StatutExport.EN_COURS;

    @Column(name = "termine_le")
    private Instant termineLe;

    @Column(name = "expire_le")
    private Instant expireLe;

    @Column(name = "taille")
    private Long taille;

    @Column(name = "empreinte", length = 64)
    private String empreinte;

    @Column(name = "nombre_tables")
    private Integer nombreTables;

    @Column(name = "nombre_lignes")
    private Long nombreLignes;

    @Column(name = "erreur", length = 300)
    private String erreur;

    @Column(name = "telechargements", nullable = false)
    private int telechargements;

    @Column(name = "dernier_telechargement")
    private Instant dernierTelechargement;

    protected ExportDonnees() {
    }

    ExportDonnees(UUID demandePar, boolean parPlateforme, Instant demandeLe) {
        this.demandePar = demandePar;
        this.parPlateforme = parPlateforme;
        this.demandeLe = demandeLe;
    }

    void terminer(Instant maintenant, Instant expiration, long taille, String empreinte, int tables, long lignes) {
        this.statut = StatutExport.PRET;
        this.termineLe = maintenant;
        this.expireLe = expiration;
        this.taille = taille;
        this.empreinte = empreinte;
        this.nombreTables = tables;
        this.nombreLignes = lignes;
    }

    void echouer(Instant maintenant, String message) {
        this.statut = StatutExport.ECHEC;
        this.termineLe = maintenant;
        this.erreur = message == null ? null : message.substring(0, Math.min(message.length(), 300));
    }

    /** Archive effacée avant la fin de sa durée de conservation (export plus récent). */
    void expirer(Instant maintenant) {
        if (expireLe == null || expireLe.isAfter(maintenant)) {
            expireLe = maintenant;
        }
    }

    void compterTelechargement(Instant maintenant) {
        telechargements++;
        dernierTelechargement = maintenant;
    }

    /** État vu par l'utilisateur à l'instant donné. */
    StatutExport etat(Instant maintenant, java.time.Duration dureeMax) {
        if (statut == StatutExport.EN_COURS && demandeLe.plus(dureeMax).isBefore(maintenant)) {
            return StatutExport.INTERROMPU;
        }
        if (statut == StatutExport.PRET && expireLe != null && !expireLe.isAfter(maintenant)) {
            return StatutExport.EXPIRE;
        }
        return statut;
    }

    UUID getDemandePar() {
        return demandePar;
    }

    boolean isParPlateforme() {
        return parPlateforme;
    }

    Instant getDemandeLe() {
        return demandeLe;
    }

    StatutExport getStatut() {
        return statut;
    }

    Instant getTermineLe() {
        return termineLe;
    }

    Instant getExpireLe() {
        return expireLe;
    }

    Long getTaille() {
        return taille;
    }

    String getEmpreinte() {
        return empreinte;
    }

    Integer getNombreTables() {
        return nombreTables;
    }

    Long getNombreLignes() {
        return nombreLignes;
    }

    String getErreur() {
        return erreur;
    }

    int getTelechargements() {
        return telechargements;
    }

    Instant getDernierTelechargement() {
        return dernierTelechargement;
    }
}
